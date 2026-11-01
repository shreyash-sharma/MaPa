"""Exact, reusable quiet-length statistics for sampled cold-start histories."""
from __future__ import annotations

import numpy as np


def precompute_quiet_quantiles(histories: np.ndarray, counts: np.ndarray) -> np.ndarray:
    """Return ``[row, prefix_length, (p90,p995)]`` for every available prefix.

    ``histories`` uses the training contract ``[rows, 30, 105]``: nine daily
    summary values followed by 96 observed-use bins (unknown bins are -1).
    The result exactly follows ``cold_start.apply_priors``' old path, including
    its fewer-than-five-spells fallback. Equal day vectors share one cached
    ``_quiet_runs`` calculation across rows, days, folds and seeds.
    """
    from mapa_rhythm.features import _quiet_runs

    h = np.asarray(histories)
    c = np.asarray(counts, dtype=np.int64)
    if h.ndim != 3 or h.shape[1] != 30 or h.shape[2] != 105:
        raise ValueError("histories must have shape [rows, 30, 105]")
    if c.shape != (len(h),) or np.any(c < 0) or np.any(c > 30):
        raise ValueError("counts must have one value per row in [0,30]")

    # Prefix zero, and every insufficiently observed prefix, use this exact
    # fallback in the original implementation.
    # Preserve NumPy's float64 quantile precision until apply_priors performs
    # the same log transform and final float32 assignment as the old path.
    out = np.empty((len(h), 31, 2), dtype=np.float64)
    out[:] = (16.0, 32.0)
    day_runs: dict[bytes, tuple[int, ...]] = {}
    for i, n in enumerate(c.tolist()):
        if n == 0:
            continue
        pooled: list[int] = []
        for k in range(1, n + 1):
            day = np.ascontiguousarray(h[i, k - 1, 9:])
            key = day.tobytes()
            runs = day_runs.get(key)
            if runs is None:
                runs = tuple(_quiet_runs(day))
                day_runs[key] = runs
            pooled.extend(runs)
            if len(pooled) >= 5:
                out[i, k] = np.quantile(pooled, [.90, .995])
    return out
