"""Feature and profile definitions shared with the Kotlin engine.

The Kotlin side (`Features.kt`, `Profile.kt`) must produce the same numbers; the
golden-vector test in `engine/src/test` pins that. Change both or neither.
"""
from __future__ import annotations

import math

import numpy as np

BINS_PER_DAY = 96
N_FEATURES = 18             # 16 day/phone states plus movement and movement-known
TRAIN_FEATURES = 21         # append historical quiet p90/p99.5 and session start
HORIZON = 16                 # hazards for the next 16 bins = 4 hours
SILENCE_CAP_BINS = 192
PROFILE_M = 4.0              # pseudo-days pulling the all-days profile toward the prior
PROFILE_M2 = 4.0             # pseudo-days pulling a day type toward the all-days profile
PROFILE_FULL_DAYS = 28
PROFILE_WINDOW_DAYS = 42
RATE_MIN, RATE_MAX = 0.002, 0.998


def training_inputs(phone_x: np.ndarray, quiet_history: np.ndarray) -> np.ndarray:
    """Keep phone features 0..17; append quiet history and session-start at 18..20."""
    x = np.asarray(phone_x, dtype=np.float32)
    q = np.asarray(quiet_history, dtype=np.float32)
    if x.shape[:-1] != q.shape[:-1] or x.shape[-1] != N_FEATURES or q.shape[-1] != 2:
        raise ValueError(f"expected phone [...,{N_FEATURES}] and quiet [...,2], got {x.shape}, {q.shape}")
    starts = np.zeros(x.shape[:-1] + (1,), dtype=np.float32)
    if x.shape[-2] > 1:
        starts[..., 1:, 0] = ((x[..., 1:, 0] > .5) & (x[..., 1:, 3] > .5) &
                               (x[..., :-1, 3] > .5) & (x[..., :-1, 0] <= .5))
    return np.concatenate((x, q, starts), axis=-1)


def stream_quiet_history(phone_x: np.ndarray, interactions: np.ndarray) -> np.ndarray:
    """Append prior-day completed quiet p90/p99.5 to phone features.

    Quiet lengths use the same daytime censoring as the release generator.
    A run touching an unknown bin or a 06:00/22:00 boundary is censored.
    This mirrors the per-person rhythm context used by the training data.
    """
    x = np.asarray(phone_x, dtype=np.float32)
    c = np.asarray(interactions, dtype=np.int64)
    if x.ndim != 2 or x.shape[1] != N_FEATURES or len(x) != len(c) or len(c) % BINS_PER_DAY:
        raise ValueError("expected complete days of 18 phone features and matching interactions")
    out = np.zeros((len(x), TRAIN_FEATURES), dtype=np.float32)
    out[:, :N_FEATURES] = x
    complete = []
    for d in range(len(c) // BINS_PER_DAY):
        lo, hi = d * BINS_PER_DAY, (d + 1) * BINS_PER_DAY
        q = np.quantile(complete, [.90, .995]) if len(complete) >= 5 else (16., 32.)
        out[lo:hi, 18:20] = np.log1p(q) / math.log1p(SILENCE_CAP_BINS)
        records = _quiet_runs(c[lo:hi])
        complete.extend(int(r) for r in records)
    out[1:, 20] = ((x[1:, 0] > .5) & (x[1:, 3] > .5) &
                   (x[:-1, 3] > .5) & (x[:-1, 0] <= .5)).astype(np.float32)
    return out


def _quiet_runs(counts: np.ndarray) -> list[int]:
    """Completed zero-use spells strictly inside observed daytime segments."""
    c = np.asarray(counts)
    known = (c >= 0) & (np.arange(len(c)) >= 28) & (np.arange(len(c)) < 88)
    edges = np.diff(np.r_[False, known, False].astype(np.int8))
    found = []
    for begin, end in zip(np.flatnonzero(edges == 1), np.flatnonzero(edges == -1)):
        segment = c[begin:end] > 0
        boundaries = np.r_[0, np.flatnonzero(segment[1:] != segment[:-1]) + 1, end - begin]
        for a, b in zip(boundaries[:-1], boundaries[1:]):
            if not segment[a] and a > 0 and b < end - begin:
                found.append(int(b - a))
    return found


def daytype(dow: int) -> int:
    """0 = Monday to Friday, 1 = Saturday, 2 = Sunday."""
    return 1 if dow == 5 else (2 if dow == 6 else 0)


def smooth_day(active: np.ndarray) -> np.ndarray:
    """active: 96 values in {0,1} or NaN when unobserved. Circular 1-2-1 smoothing
    over observed neighbours; NaN where the centre bin is unobserved."""
    out = np.full(BINS_PER_DAY, np.nan)
    w = (0.25, 0.5, 0.25)
    for b in range(BINS_PER_DAY):
        if np.isnan(active[b]):
            continue
        num = 0.0
        den = 0.0
        for j, wj in zip((b - 1, b, b + 1), w):
            v = active[j % BINS_PER_DAY]
            if not np.isnan(v):
                num += wj * v
                den += wj
        out[b] = num / den
    return out


def smooth_days(active: np.ndarray) -> np.ndarray:
    """Vectorised smooth_day over [D, 96]."""
    obs = ~np.isnan(active)
    a = np.where(obs, active, 0.0)
    w = obs.astype(np.float64)
    num = 0.5 * a + 0.25 * np.roll(a, 1, axis=1) + 0.25 * np.roll(a, -1, axis=1)
    den = 0.5 * w + 0.25 * np.roll(w, 1, axis=1) + 0.25 * np.roll(w, -1, axis=1)
    out = np.full(active.shape, np.nan)
    out[obs] = num[obs] / den[obs]
    return out


class Profile:
    """Personal probability that a 15-minute bin has phone interaction, per day type."""

    def __init__(self, rate: np.ndarray, n_days: int):
        self.rate = rate                 # [3, 96]
        self.n_days = n_days

    @property
    def confidence(self) -> float:
        return min(self.n_days, PROFILE_FULL_DAYS) / PROFILE_FULL_DAYS

    @staticmethod
    def build(days: list[tuple[int, np.ndarray]], prior: np.ndarray) -> "Profile":
        """days: (dow, active[96] with NaN for unobserved). prior: [3, 96]."""
        if days:
            sm = smooth_days(np.stack([a for _, a in days]))
            dts = [daytype(dow) for dow, _ in days]
        else:
            sm, dts = np.zeros((0, BINS_PER_DAY)), []
        return Profile.from_smoothed(sm, np.array(dts, dtype=int), prior)

    @staticmethod
    def from_smoothed(sm: np.ndarray, dts: np.ndarray, prior: np.ndarray) -> "Profile":
        ok = ~np.isnan(sm)
        v = np.where(ok, sm, 0.0)
        sum_all = v.sum(0)
        n_all = ok.sum(0).astype(np.float64)
        sum_dt = np.zeros((3, BINS_PER_DAY))
        n_dt = np.zeros((3, BINS_PER_DAY))
        for dt in range(3):
            sel = dts == dt
            sum_dt[dt] = v[sel].sum(0)
            n_dt[dt] = ok[sel].sum(0)
        rate = np.zeros((3, BINS_PER_DAY))
        for dt in range(3):
            pall = (sum_all + PROFILE_M * prior[dt]) / (n_all + PROFILE_M)
            rate[dt] = (sum_dt[dt] + PROFILE_M2 * pall) / (n_dt[dt] + PROFILE_M2)
        return Profile(rate, len(dts))

    @staticmethod
    def prior_only(prior: np.ndarray) -> "Profile":
        return Profile.build([], prior)


def activity_code(interactions: np.ndarray, moved: np.ndarray | None = None) -> np.ndarray:
    """Sign of life per bin, as the phone sees it (`BinObservation.activityCode`):
    1 used or moved, 0 seen and neither, -1 nothing known. `moved` uses 1 / 0 / -1 (no signal)."""
    c = np.asarray(interactions).astype(np.int64)
    alive = c > 0
    if moved is not None:
        alive = alive | (np.asarray(moved) == 1)
    return np.where(alive, 1, np.where(c >= 0, 0, -1)).astype(np.int64)


def featurize(interactions: np.ndarray, charging: np.ndarray, battery: np.ndarray,
              start_dow: int, profile_for_day, t0: int = 0, t1: int | None = None,
              last_active_init: int | None = None, moved: np.ndarray | None = None) -> np.ndarray:
    """Feature rows for ticks t0..t1-1. Row t describes the world after bin t and
    is used to predict bins t+1..t+HORIZON.

    profile_for_day(day) -> Profile in force while that day's ticks are processed.
    """
    n = len(interactions)
    t1 = n if t1 is None else t1
    out = np.zeros((t1 - t0, N_FEATURES), dtype=np.float32)
    last_active = last_active_init
    log21 = math.log(21.0)
    logcap = math.log1p(SILENCE_CAP_BINS)
    state_counts = np.zeros(4, dtype=np.float64)  # use, short break, long quiet, night
    log4 = math.log(4.0)
    for t in range(t0, t1):
        if t > t0 and t % BINS_PER_DAY == 0:
            state_counts.fill(0.0)
        x = out[t - t0]
        c = int(interactions[t])
        observed = c >= 0
        active = c > 0
        mv = -1 if moved is None else int(moved[t])
        if active or mv == 1:          # silence is counted between signs of life (use or movement)
            last_active = t
        sil = SILENCE_CAP_BINS if last_active is None else min(t - last_active, SILENCE_CAP_BINS)
        slot = t % BINS_PER_DAY
        daytime = 24 <= slot < BINS_PER_DAY
        x[0] = 1.0 if active else 0.0
        x[1] = 1.0 if observed and daytime and not active and (mv == 1 or sil < 16) else 0.0
        x[2] = 1.0 if observed and daytime and not active and mv != 1 and sil >= 16 else 0.0
        x[3] = 1.0 if observed else 0.0
        if observed:
            state = 3 if not daytime else 0 if active else 1 if (mv == 1 or sil < 16) else 2
            state_counts[state] += 1.0
        state_total = state_counts.sum()
        entropy = 0.0
        if state_total > 0:
            probs = state_counts[state_counts > 0] / state_total
            entropy = -float(np.sum(probs * np.log(probs))) / log4
        nxt = t + 1
        bod = nxt % BINS_PER_DAY
        dow = (start_dow + nxt // BINS_PER_DAY) % 7
        x[4] = math.sin(2 * math.pi * bod / BINS_PER_DAY)
        x[5] = math.cos(2 * math.pi * bod / BINS_PER_DAY)
        x[6] = math.sin(2 * math.pi * dow / 7)
        x[7] = math.cos(2 * math.pi * dow / 7)
        x[8] = 0.0 if daytime else 1.0
        x[9] = 1.0 if charging[t] else 0.0
        x[10] = float(battery[t]) / 100.0
        prof = profile_for_day(t // BINS_PER_DAY)
        r = [profile_rate(prof, start_dow, t + k) for k in range(1, HORIZON + 1)]
        x[11] = r[0]
        x[12] = sum(r[0:4]) / 4
        x[13] = sum(r[4:8]) / 4
        x[14] = entropy
        x[15] = prof.confidence
        x[16] = 1.0 if mv == 1 else 0.0
        x[17] = 1.0 if mv >= 0 else 0.0
    return out


def profile_rate(prof: Profile, start_dow: int, t: int) -> float:
    day, bod = divmod(t, BINS_PER_DAY)
    dt = daytype((start_dow + day) % 7)
    return float(prof.rate[dt, bod])


def survival_targets(interactions: np.ndarray, exclude: np.ndarray | None = None,
                     moved: np.ndarray | None = None):
    """Discrete-time hazard labels on the sign of life (use or movement). For tick t and horizon k (1..H):
    mask[t,k-1] = bins t+1..t+k observed, t+1..t+k-1 without a sign of life, t+k not excluded.
    y[t,k-1] = bin t+k shows a sign of life."""
    code = activity_code(interactions, moved)
    n = len(code)
    obs = code >= 0
    act = code > 0
    ex = np.zeros(n, dtype=bool) if exclude is None else exclude
    y = np.zeros((n, HORIZON), dtype=np.float32)
    mask = np.zeros((n, HORIZON), dtype=bool)
    alive = np.ones(n, dtype=bool)   # still silent and observed through t+k-1
    for k in range(1, HORIZON + 1):
        idx = np.arange(n) + k
        valid = idx < n
        idxc = np.minimum(idx, n - 1)
        o = obs[idxc] & valid
        a = act[idxc] & valid
        e = ex[idxc] & valid
        alive = alive & o
        mask[:, k - 1] = alive & ~e
        y[:, k - 1] = a
        alive = alive & ~a & ~e
    return y, mask


def featurize_fast(interactions, charging, battery, start_dow, profiles, moved=None) -> np.ndarray:
    """Vectorised featurize for a whole sequence starting at day 0.
    profiles: list of Profile, one per day (in force during that day's ticks)."""
    n = len(interactions)
    t = np.arange(n)
    c = interactions.astype(np.int64)
    observed = c >= 0
    active = c > 0
    mv = np.full(n, -1, dtype=np.int64) if moved is None else np.asarray(moved).astype(np.int64)[:n]
    out = np.zeros((n, N_FEATURES), dtype=np.float32)
    out[:, 0] = active
    out[:, 1] = np.minimum(np.log1p(np.where(observed, c, 0)) / math.log(21.0), 1.0)
    out[:, 2] = observed
    last = np.where(active | (mv == 1), t, -1)
    last = np.maximum.accumulate(last)
    sil = np.where(last < 0, SILENCE_CAP_BINS, np.minimum(t - last, SILENCE_CAP_BINS))
    out[:, 3] = np.minimum(np.log1p(sil) / math.log1p(SILENCE_CAP_BINS), 1.0)
    nxt = t + 1
    bod = nxt % BINS_PER_DAY
    dow = (start_dow + nxt // BINS_PER_DAY) % 7
    out[:, 4] = np.sin(2 * np.pi * bod / BINS_PER_DAY)
    out[:, 5] = np.cos(2 * np.pi * bod / BINS_PER_DAY)
    out[:, 6] = np.sin(2 * np.pi * dow / 7)
    out[:, 7] = np.cos(2 * np.pi * dow / 7)
    out[:, 8] = (dow >= 5)
    out[:, 9] = charging[:n]
    out[:, 10] = battery[:n].astype(np.float32) / 100.0
    days = n // BINS_PER_DAY
    ks = np.arange(1, HORIZON + 1)
    dts = np.array([daytype((start_dow + d) % 7) for d in range(days + 2)])
    for d in range(days):
        prof = profiles[d]
        tt = np.arange(d * BINS_PER_DAY, (d + 1) * BINS_PER_DAY)
        tgt = tt[:, None] + ks[None, :]
        tday, tbod = np.divmod(tgt, BINS_PER_DAY)
        r = prof.rate[dts[tday], tbod]
        out[tt, 11] = r[:, 0]
        out[tt, 12] = r[:, 0:4].mean(1)
        out[tt, 13] = r[:, 4:8].mean(1)
        out[tt, 14] = r[:, 8:16].mean(1)
        out[tt, 15] = prof.confidence
    out[:, 16] = mv == 1
    out[:, 17] = mv >= 0
    return out


def day_active(interactions: np.ndarray, day: int, moved: np.ndarray | None = None) -> np.ndarray:
    """One day's sign of life for the profile: 1 used or moved, 0 seen and neither, NaN unknown."""
    sl = slice(day * BINS_PER_DAY, (day + 1) * BINS_PER_DAY)
    code = activity_code(interactions[sl], None if moved is None else moved[sl]).astype(np.float64)
    return np.where(code < 0, np.nan, code)
