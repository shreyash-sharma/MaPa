"""Four-state HSMM with lognormal sojourns and a latent quiet-state mixture.

The two quiet states have identical zero emissions. Their mixture is fitted by
censored likelihood; a truncated observed run is a survival bound, not an event.
"""
import numpy as np
from scipy.optimize import minimize
from scipy.special import expit, logsumexp
from scipy.stats import lognorm

STATES = ["in_use", "short_break", "long_quiet", "night"]


def runs(counts, observed, start_dow=0):
    counts, observed = np.asarray(counts), np.asarray(observed, dtype=bool)
    t = np.arange(len(counts))
    # Administrative night boundaries are censoring, never complete quiet events.
    mask = observed & (counts >= 0) & (t % 96 >= 28) & (t % 96 < 88)
    edges = np.diff(np.r_[False, mask, False].astype(np.int8))
    records = []
    for begin, end in zip(np.flatnonzero(edges == 1), np.flatnonzero(edges == -1)):
        active = counts[begin:end] > 0
        boundaries = np.r_[0, np.flatnonzero(active[1:] != active[:-1]) + 1, end - begin]
        for a, b in zip(boundaries[:-1], boundaries[1:]):
            start = int(begin + a)
            weekend = ((start // 96 + start_dow) % 7) >= 5
            records.append((int(active[a]), int(b - a), bool(a == 0 or b == end - begin), int(weekend) * 6 + (start % 96) // 16,
                            bool(a == 0), bool(b == end - begin)))
    return np.array(records, dtype=float).reshape(-1, 6)


def duration_pmf(mu, sigma, cap=96):
    """Exact law of round(lognormal), clamped to [1, cap], including tail mass."""
    edges = np.arange(1, cap, dtype=float) + .5
    cdf = lognorm.cdf(edges, sigma, scale=np.exp(mu))
    pmf = np.maximum(np.diff(np.r_[0., cdf, 1.]), 1e-300)
    return pmf / pmf.sum()


def likelihood_from_pmf(durations, censored, pmf, left_censored=None, right_censored=None):
    d = np.clip(np.asarray(durations, dtype=int), 1, len(pmf)) - 1
    left = np.zeros(len(d), bool) if left_censored is None else np.asarray(left_censored, bool)
    right = np.asarray(censored, bool) if right_censored is None else np.asarray(right_censored, bool)
    survival = np.cumsum(pmf[::-1])[::-1]
    mean = float(survival.sum())
    # A random observation starts in the equilibrium residual-life law, not
    # at a freshly begun interval. Both-censored observations integrate it.
    residual_survival = np.cumsum(survival[::-1])[::-1] / mean
    probs = np.where(left, np.where(right, residual_survival[d], survival[d] / mean),
                     np.where(right, survival[d], pmf[d]))
    return np.log(np.maximum(probs, 1e-300))


def log_likelihood(durations, censored, mu, sigma, left_censored=None, right_censored=None):
    return likelihood_from_pmf(durations, censored, duration_pmf(mu, sigma), left_censored, right_censored)


def fit_duration(durations, censored, fallback=(0., .6), left_censored=None, right_censored=None):
    d, c = np.asarray(durations), np.asarray(censored, bool)
    if len(d) < 3 or (~c).sum() == 0:
        # Non-identifiable tails are explicitly pooled, not falsely completed.
        return list(fallback)
    initial = [float(np.mean(np.log(d))), max(.25, float(np.std(np.log(d))))]
    result = minimize(lambda p: -np.maximum(log_likelihood(d, c, p[0], np.exp(p[1]), left_censored, right_censored), -700).sum(),
                      [initial[0], np.log(initial[1])], method="L-BFGS-B", bounds=[(-2, np.log(96)), (np.log(.15), np.log(3))])
    if not result.success or not np.isfinite(result.fun):
        return list(fallback)
    return [float(result.x[0]), float(np.exp(result.x[1]))]


def fit_quiet(durations, censored, fallback=None, left_censored=None, right_censored=None):
    d, c = np.asarray(durations), np.asarray(censored, bool)
    if len(d) < 8 or (~c).sum() < 3:
        return fallback or dict(weight=.7, short=[0., .5], long=[np.log(4), .8], identifiable=False)
    logs = np.log(d)
    def objective(p):
        w = expit(p[0])
        # Mixing fresh-duration PMFs before length-biasing gives the correct
        # residual mixture weights at unknown starts.
        pmf = w * duration_pmf(p[1], np.exp(p[2])) + (1 - w) * duration_pmf(p[3], np.exp(p[4]))
        ll = likelihood_from_pmf(d, c, pmf, left_censored, right_censored)
        return -np.maximum(ll, -700).sum()
    initial = [0., float(np.quantile(logs, .25)), np.log(.5), float(np.quantile(logs, .75)), np.log(.8)]
    result = minimize(objective, initial, method="L-BFGS-B", bounds=[(-3, 3), (-2, np.log(96)), (np.log(.15), np.log(3)), (-2, np.log(96)), (np.log(.15), np.log(3))], options={"maxiter": 150})
    if not np.isfinite(result.fun):
        raise RuntimeError("Quiet censored likelihood is non-finite")
    p = result.x
    w, short, long = float(expit(p[0])), [float(p[1]), float(np.exp(p[2]))], [float(p[3]), float(np.exp(p[4]))]
    if short[0] > long[0]:
        short, long, w = long, short, 1 - w
    return dict(weight=w, short=short, long=long, identifiable=True, optimizer_converged=bool(result.success))


def km_curve(durations, censored, grid):
    d, c = np.asarray(durations), np.asarray(censored, bool)
    survival = 1.
    times, values = [], []
    for time in np.unique(d):
        risk = (d >= time).sum()
        events = ((d == time) & ~c).sum()
        survival *= 1 - events / risk
        times.append(time); values.append(survival)
    pos = np.searchsorted(times, grid, side="right") - 1
    return np.where(pos >= 0, np.asarray(values)[np.maximum(pos, 0)], 1.) if len(values) else np.ones(len(grid))
