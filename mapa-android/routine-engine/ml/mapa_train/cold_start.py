"""Train-fold priors and causal shrinkage for histories of zero to thirty days."""
from __future__ import annotations

import numpy as np
from sklearn.cluster import KMeans

CONTEXT = [11, 12, 13, 18, 19]


def sample_history_prefix(histories, counts, rng):
    """Mask a history after its first k available days, k uniformly 0..30."""
    selected = np.minimum(np.asarray(counts), rng.integers(0, 31, size=len(counts)))
    result = histories.copy()
    for i, n in enumerate(selected):
        result[i, int(n):] = 0
    return result, selected


def fit_priors(x, summaries, groups, normal_rows, seed):
    """Fit archetypes on derived people from the fitting fold only."""
    by_pid = {}
    for i in normal_rows:
        g = groups[int(i)]
        if g.get("source") == "derived":
            by_pid.setdefault(str(g["pid"]), []).append(int(i))
    if len(by_pid) < 5:
        raise ValueError("Five derived training people are required for rhythm archetypes")
    pids = sorted(by_pid)
    daily = np.stack([summaries[by_pid[p]].mean(axis=0) for p in pids])
    context = np.stack([x[by_pid[p]][..., CONTEXT].mean(axis=(0, 1)) for p in pids])
    center, scale = daily.mean(axis=0), daily.std(axis=0)
    scale = np.maximum(scale, 1e-5)
    km = KMeans(n_clusters=5, random_state=seed, n_init=10).fit((daily-center)/scale)
    priors = np.stack([context[km.labels_ == k].mean(axis=0) for k in range(5)])
    return {"center": center, "scale": scale, "centroids": km.cluster_centers_,
            "archetype_priors": priors, "population_prior": context.mean(axis=0),
            "fit_pids": pids, "pseudo_days": 7.0}


def apply_priors(x, histories, counts, fitted, quiet_quantiles=None):
    """Use only available prior-day summaries; change no observed phone signal."""
    if len(x) != len(histories) or len(x) != len(counts):
        raise ValueError("Batch, history and count lengths differ")
    if np.any(np.asarray(counts) < 0) or np.any(np.asarray(counts) > histories.shape[1]):
        raise ValueError("History count is outside the available history window")
    if quiet_quantiles is not None:
        quiet_quantiles = np.asarray(quiet_quantiles)
        if quiet_quantiles.shape != (len(x), histories.shape[1] + 1, 2):
            raise ValueError("quiet_quantiles must have shape [batch, 31, 2]")
    out = x.copy()
    for i, n in enumerate(counts):
        prior = fitted["population_prior"]
        if n >= 3:
            summary = histories[i, :int(n), :9].mean(axis=0)
            z = (summary-fitted["center"])/fitted["scale"]
            cluster = int(np.argmin(((fitted["centroids"]-z)**2).sum(axis=1)))
            prior = fitted["archetype_priors"][cluster]
        weight = float(n)/(float(n)+fitted["pseudo_days"])
        if histories.shape[-1] != 105:
            raise ValueError("Personal context requires nine summaries plus 96 historical use bins")
        if n:
            use = histories[i, :int(n), 9:]
            known = use >= 0
            hourly = np.divide(np.where(known, use, 0).sum(axis=0), known.sum(axis=0),
                               out=np.full(96, float(prior[0])), where=known.sum(axis=0) > 0)
            slots = np.arange(96)
            personal = np.zeros((96, 5), dtype=np.float32)
            personal[:, 0] = hourly[(slots + 1) % 96]
            personal[:, 1] = np.stack([hourly[(slots + h) % 96] for h in range(1, 5)]).mean(axis=0)
            personal[:, 2] = np.stack([hourly[(slots + h) % 96] for h in range(5, 9)]).mean(axis=0)
            if quiet_quantiles is None:
                from mapa_rhythm.features import _quiet_runs
                quiet = [r for day in use for r in _quiet_runs(day)]
                quiet_q = np.quantile(quiet, [.90, .995]) if len(quiet) >= 5 else [16., 32.]
            else:
                quiet_q = quiet_quantiles[i, int(n)]
            personal[:, 3:] = np.log1p(quiet_q)/np.log1p(192.)
            out[i][:, CONTEXT] = weight*personal + (1-weight)*prior
        else:
            out[i][:, CONTEXT] = prior
        out[i, :, 15] = weight
    return out
