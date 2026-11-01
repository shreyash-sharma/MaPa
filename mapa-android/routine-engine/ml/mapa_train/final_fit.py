"""Fit a CV-selected hybrid on train only and lock it before confirmation."""
import hashlib
import json
import os
from pathlib import Path
import torch
from mapa_release.common import ROOT, trial
from .protocol import freeze, SEEDS
from .zoo import load, train_neural


def fit_selected(cv_folder, destination, protocol_version=16, decision_report=None):
    cv_folder, destination = Path(cv_folder), Path(destination)
    protocol = freeze(protocol_version)
    report_path = cv_folder / 'cv-decision-results.json'
    model_report = json.loads(report_path.read_text())
    decision_path = Path(decision_report) if decision_report is not None else report_path
    decision_eval = json.loads(decision_path.read_text())
    cv_meta = json.loads((cv_folder / 'complete.json').read_text())
    if decision_path.resolve() != report_path.resolve():
        if decision_eval.get('model') != cv_folder.name:
            raise RuntimeError('Separate decision report does not identify this model-fit folder')
        for name, expected in decision_eval.get('source_artifacts_sha256', {}).items():
            artifact = (ROOT / f"learning-{cv_meta['data_version']}-final-v2" / name
                        if name == 'train.groups.json' else cv_folder / name)
            if not artifact.is_file() or hashlib.sha256(artifact.read_bytes()).hexdigest() != expected:
                raise RuntimeError(f'Saved OOF artifact hash mismatch: {name}')
    chosen = decision_eval['selected_on_train_oof_only']
    if not chosen.get('minimum_mean'):
        raise RuntimeError('Selected system has not passed the train-CV minimum gate')
    if not chosen.get('C4_mean'):
        raise RuntimeError('Selected system has not passed the train-CV C4 gate')
    family = model_report['family']
    if not family.startswith('hybrid'):
        raise ValueError('The final model must be the approved hybrid')
    destination.mkdir(parents=True, exist_ok=True)
    if any((destination / name).exists() for name in ['selection.json', 'model.pt', 'validation-results.json']):
        raise RuntimeError('Final fit destination already contains locked or partial work; use a new run folder')
    selection = {'family': family, 'decision_config': chosen['config'],
                 'cv_report_sha256': hashlib.sha256(report_path.read_bytes()).hexdigest(),
                 'decision_report_path': str(decision_path.resolve()),
                 'decision_report_sha256': hashlib.sha256(decision_path.read_bytes()).hexdigest(),
                 'seed': SEEDS[0], 'ablation_row': cv_meta.get('ablation_row'),
                 'selection_data': 'train person-OOF only', 'test_opened': False,
                 'selection_protocol_sha256': cv_meta.get('training_protocol_sha256')
                     or cv_meta.get('training_protocol_v9_sha256'),
                 'decision_selection_artifact': 'train-OOF decision report; no validation/test data',
                 'final_fit_protocol_version': protocol['protocol_version'],
                 'final_fit_protocol_sha256': hashlib.sha256(
                     (ROOT / f"training-protocol-v{protocol_version}.json").read_bytes()).hexdigest(),
                 'final_fit_source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (destination / 'selection.pending.json').write_text(json.dumps(selection, indent=2))
    trial('final-fit', destination.name, 'STARTED', 'Train only; first frozen seed; no validation/test reading')
    try:
        arrays, info = load('train', cv_meta['data_version'])
        groups = json.loads((ROOT / f"learning-{cv_meta['data_version']}-final-v2/train.groups.json").read_text())
        device = 'cuda' if torch.cuda.is_available() else 'cpu'
        fit_tr = (*arrays[:8], groups, *arrays[8:])
        fit_info, model = train_neural(family, fit_tr, 15, device,
                                       seed=SEEDS[0], ablation_row=cv_meta.get('ablation_row'))
        torch.save({'state_dict': model.state_dict(), 'family': family,
                    'ablation_config': model.ablation_config}, destination / 'model.pt')
        if hasattr(model, 'cold_start_priors'):
            priors = {k: v.tolist() if hasattr(v, 'tolist') else v for k, v in model.cold_start_priors.items()}
            (destination / 'cold-start-priors.json').write_text(json.dumps(priors, indent=2))
        (destination / 'complete.json').write_text(json.dumps({**cv_meta, 'name': destination.name,
            'final_training': fit_info, 'dataset_manifest_sha256': info['dataset_manifest_sha256'],
            'final_fit_protocol_version': protocol['protocol_version'],
            'final_fit_protocol_sha256': selection['final_fit_protocol_sha256']}, indent=2))
        selection['checkpoint_sha256'] = hashlib.sha256((destination / 'model.pt').read_bytes()).hexdigest()
        selection['artifacts_sha256'] = {name: hashlib.sha256((destination/name).read_bytes()).hexdigest()
            for name in ['model.pt', 'complete.json', 'cold-start-priors.json'] if (destination/name).exists()}
        (destination / 'selection.pending.json').write_text(json.dumps(selection, indent=2))
        os.replace(destination / 'selection.pending.json', destination / 'selection.json')
        trial('final-fit', destination.name, 'COMPLETE', 'Weights and decision config locked before confirmation')
        return selection
    except Exception as error:
        trial('final-fit', destination.name, 'FAIL', repr(error))
        raise
