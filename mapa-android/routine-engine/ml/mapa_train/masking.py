"""Mask phone-use inputs without leaking their deterministic derived features."""
import numpy as np


def mask_use_inputs(x, hidden):
    out = x.copy()
    out[..., :4] = np.where(hidden[..., None], 0., out[..., :4])
    # Entropy includes the current state; suppress it for the corrupted day.
    out[np.any(hidden, axis=1), :, 14] = 0.
    # A session start depends on the current and immediately preceding use bin.
    affected = hidden.copy()
    affected[:, 1:] |= hidden[:, :-1]
    out[..., 20] = np.where(affected, 0., out[..., 20])
    return out
