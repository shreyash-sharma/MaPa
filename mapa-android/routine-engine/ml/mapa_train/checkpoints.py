"""Resume only completed fits with matching protocol and intact artifacts."""
import hashlib
import json
import numpy as np


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resume_fit(folder, protocol_hash, held_indices):
    record = folder / 'fit.json'
    if not record.exists():
        return None
    info = json.loads(record.read_text())
    if info.get('protocol_sha256') != protocol_hash:
        raise RuntimeError('Saved fit belongs to a different protocol')
    artifacts = info.get('artifacts_sha256')
    if not artifacts:
        raise RuntimeError('Saved fit has no artifact integrity record')
    for name, expected in artifacts.items():
        if not (folder / name).exists() or digest(folder / name) != expected:
            raise RuntimeError(f'Saved fit artifact mismatch: {name}')
    with np.load(folder / 'held-predictions.npz', allow_pickle=False) as arrays:
        np.testing.assert_array_equal(arrays['indices'], held_indices)
        scores = {key: arrays[key].copy() for key in ['behaviour', 'hazard', 'confidence']}
    if not all(np.isfinite(v).all() for v in scores.values()):
        raise RuntimeError('Saved held-person scores are not finite')
    return info, scores
