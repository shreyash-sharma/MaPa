"""Finalize the dataset-only deliverable after model training was deferred."""
import os
import shutil
import subprocess
from pathlib import Path
from .common import ROOT,CLAIM,trial,progress
from .checks import audit_data,audit_monitoring_policy
from .final_loader import load_splits
from mapa_syn.io import read_json,write_json,sha256


def finish():
    os.environ.update(HF_HUB_OFFLINE='1',HF_DATASETS_OFFLINE='1',HF_HUB_DISABLE_TELEMETRY='1')
    from datasets import load_dataset
    folder=ROOT/'huggingface/MaPa-Rhythm';final=ROOT/'final-v2'
    commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
    write_json(ROOT/'scope-change.json',dict(scope='dataset only',
        instruction='we dont need to train right now, we just need the data',
        training_stopped=True,model_delivery_deferred=True,C4='Deferred with model evaluation; not claimed as passing'))
    trial('scope','dataset-only','UPDATED','User deferred training and model delivery; dataset checks remain required.')
    stopped=ROOT/'models/cnn-v35-profile-survival'
    if stopped.exists() and not (stopped/'complete.json').exists():
        write_json(stopped/'STOPPED.json',dict(reason='User requested data only',
            training_incomplete=True,release_eligible=False,checkpoint_files=[p.name for p in stopped.glob('*.pt')]))
        trial('model','cnn-v35-profile-survival','STOPPED','Stopped during derived/episode fine-tuning at user request. Partial run is not included in the dataset deliverable.')
    audit=audit_data('v2');policy=audit_monitoring_policy()
    tables=load_splits(final)
    for version in ['final','final-v2']:
        anchor=read_json(ROOT/('C8-'+version+'.json'))
        assert sha256(ROOT/version/'manifest.json')==anchor['manifest_sha256']
        load_splits(ROOT/version)
    manifest=read_json(final/'manifest.json')
    staged=folder/final.name
    assert sha256(staged/'manifest.json')==sha256(final/'manifest.json')
    load_splits(staged)
    views=read_json(folder/'subset-views.json')
    for relative,digest in views['sha256'].items():assert sha256(folder/relative)==digest
    for config in ['all','observed','derived','episodes']:
        data=load_dataset(str(folder),name=config)
        expected={s:len(tables[s]) if config=='all' else views['rows'][s][config] for s in tables}
        assert {s:len(data[s]) for s in tables}==expected
    default=load_dataset(str(folder));assert all(len(default[s])==len(tables[s]) for s in tables)
    for source in ['s3','ubiqlog']:
        assert (folder/'licenses'/f'{source}-CC-BY-4.0.txt').stat().st_size>100
    card=(folder/'README.md').read_text(encoding='utf-8')
    assert CLAIM in card
    card=card.replace('Test evaluation has not run. This folder is not release-ready.',
        'Model training and detector evaluation are deferred at the user’s request. This deliverable is the dataset only; no detector-performance gate is claimed as passing.')
    card+='\n## Dataset release status\n\nThe active frozen version is final-v2/. It contains 4,230,516 training rows, 495,523 validation rows and 492,687 test rows. The original final/ is retained as an immutable earlier version. Validation and test payloads are byte-identical across the two versions. Model training is deferred; C4 detector comparisons are not a dataset-release acceptance claim. Known fidelity failures, censored-tail assumptions, historical holdout exposure and paired-label limitations remain disclosed above. See DATASET_CHECKS.json for the completed data audit.\n'
    (folder/'README.md').write_text(card,encoding='utf-8')
    subprocess.run([os.sys.executable,str(folder/'load_example.py')],cwd=folder,check=True)
    for p in Path(__file__).parent.rglob('*.py'):
        target=folder/'code/mapa_release'/p.relative_to(Path(__file__).parent)
        target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
    result=dict(dataset_ready=True,scope='dataset only',model_training='DEFERRED_BY_USER',
        C1=audit['C1'],C2=audit['C2'],C3='Full fidelity table retained, including worse quiet KS',
        C4='DEFERRED_BY_USER; no model-performance success claimed',C5=policy['C5'],
        C6=audit['C6_schema'],C7=True,C8=True,splits=manifest['splits'],
        code_commit=commit,frozen_data_code_commit=manifest['code_commit'],
        frozen_manifest_sha256=sha256(final/'manifest.json'),staged_folder=str(folder),
        final_dataset=str(final),upload_performed=False,email_sent=False)
    write_json(ROOT/'dataset-checks.json',result);write_json(folder/'DATASET_CHECKS.json',result)
    trial('dataset-release','final-v2','PASS',str(result))
    shutil.copy2(ROOT.parent/'logs/trials.csv',folder/'trials.csv')
    write_json(folder/'SHA256.json',{str(p.relative_to(folder)):sha256(p) for p in sorted(folder.rglob('*'))
        if p.is_file() and p.name!='SHA256.json' and '__pycache__' not in p.parts})
    for relative,digest in read_json(folder/'SHA256.json').items():assert sha256(folder/relative)==digest
    summary='Dataset-only deliverable ready. Active final-v2/: train 4,230,516 rows / 162 people; val 495,523 / 39; test 492,687 / 39. Observed: 10,150 bins; derived: 200 parents × 120 days; all ten programmatically added scenarios retained. Person separation, provenance, licences, forbidden-field scan, all HF configurations, loader, frozen manifests and payload hashes verified. C4 and model delivery deferred at user request, not marked passing. No upload or email. Known fidelity and paired-label limitations remain in the card.'
    (ROOT/'FINAL-SUMMARY.md').write_text(summary+'\n',encoding='utf-8')
    (ROOT/'huggingface/RELEASE_STATUS.md').write_text(summary+'\n\nMaPa-Rhythm/ is the dataset deliverable. MaPa-Rhythm-Base/ remains an unfinished diagnostic draft and is not part of this delivery.\n',encoding='utf-8')
    progress(summary)
    status=ROOT.parent/'logs/build-status.md';text=status.read_text(encoding='utf-8')
    if text.startswith('> MaPa-Rhythm release:'):text=text.split('\n\n',1)[1]
    status.write_text('> MaPa-Rhythm dataset-only release: READY. Model training and C4 deferred by user. Frozen datasets unchanged; no upload or email.\n\n'+text,encoding='utf-8')
    return result


if __name__=='__main__':print(finish())
