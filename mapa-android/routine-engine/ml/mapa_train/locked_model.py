"""Load only a finalized, hash-locked hybrid; never load dataset rows."""
import hashlib
import json
from pathlib import Path
import numpy as np
import torch
from .models import Hybrid


def load_locked_hybrid(folder, device='cpu'):
    folder = Path(folder)
    selection = json.loads((folder / 'selection.json').read_text())
    checkpoint = folder / 'model.pt'
    if hashlib.sha256(checkpoint.read_bytes()).hexdigest() != selection['checkpoint_sha256']:
        raise RuntimeError('Locked checkpoint hash mismatch')
    for name, expected in selection.get('artifacts_sha256', {}).items():
        if name not in {'model.pt', 'complete.json', 'cold-start-priors.json'}:
            raise ValueError('Unknown locked model artifact')
        if hashlib.sha256((folder/name).read_bytes()).hexdigest() != expected:
            raise RuntimeError(f'Locked artifact hash mismatch: {name}')
    metadata = json.loads((folder / 'complete.json').read_text())
    if metadata['feature_contract'] != 21:
        raise ValueError('Locked hybrid requires the confirmed 21-input contract')
    saved = torch.load(checkpoint, map_location='cpu', weights_only=True)
    if not saved['family'].startswith('hybrid'):
        raise ValueError('Locked checkpoint is not the approved hybrid')
    cfg = saved['ablation_config']
    model = Hybrid(attention=cfg.get('attention', False), personal=cfg.get('personal', False),
                   slow=cfg.get('slow', False))
    model.load_state_dict(saved['state_dict'], strict=True)
    model.ablation_config = cfg
    model.cold_start_enabled = bool(cfg.get('cold_start', False))
    row = selection.get('ablation_row')
    model.behaviour_enabled = row is None or row >= 3
    if model.cold_start_enabled:
        raw = json.loads((folder / 'cold-start-priors.json').read_text())
        model.cold_start_priors = {key: np.asarray(value) if isinstance(value, list) and key != 'fit_pids' else value
                                   for key, value in raw.items()}
    return model.to(device).eval(), selection, metadata
