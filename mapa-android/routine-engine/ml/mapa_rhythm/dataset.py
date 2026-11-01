"""Turns simulated parents into model inputs, emulating what the phone would know."""
from __future__ import annotations

import numpy as np

from .features import (BINS_PER_DAY, HORIZON, PROFILE_WINDOW_DAYS, Profile, daytype, featurize_fast,
                       smooth_days, survival_targets)
from .synth import POSITIVE, ParentSim, positive_mask

QUARANTINE_DAYS = 7
LEARNING_DAYS = 14
MIN_COVERAGE = 0.8


def day_matrix(sim: ParentSim) -> np.ndarray:
    c = sim.interactions.reshape(sim.days, BINS_PER_DAY).astype(np.float64)
    return np.where(c < 0, np.nan, (c > 0).astype(np.float64))


def clean_days(sim: ParentSim) -> np.ndarray:
    """Days the phone's guards would let into training: no positive episode, decent coverage, untagged."""
    pos_day = positive_mask(sim).reshape(sim.days, BINS_PER_DAY).any(1)
    cov = (sim.interactions.reshape(sim.days, BINS_PER_DAY) >= 0).mean(1)
    tagged = np.zeros(sim.days, dtype=bool)
    for e in sim.episodes:
        if e.tagged:
            tagged[e.start // BINS_PER_DAY:(e.end - 1) // BINS_PER_DAY + 1] = True
    return ~pos_day & (cov >= MIN_COVERAGE) & ~tagged


def population_prior(sims: list[ParentSim]) -> np.ndarray:
    num = np.zeros((3, BINS_PER_DAY))
    den = np.zeros((3, BINS_PER_DAY))
    for s in sims:
        a = day_matrix(s)
        ok = clean_days(s)
        for d in np.nonzero(ok)[0]:
            dt = daytype(s.dow(d))
            o = ~np.isnan(a[d])
            num[dt, o] += a[d, o]
            den[dt, o] += 1
    return num / np.maximum(den, 1)


def daily_profiles(sim: ParentSim, prior: np.ndarray) -> list[Profile]:
    """Profile in force on each day, built from days that cleared quarantine."""
    sm = smooth_days(day_matrix(sim))
    ok = clean_days(sim)
    dts = np.array([daytype(sim.dow(d)) for d in range(sim.days)])
    out = []
    for d in range(sim.days):
        lag = 1 if d + 1 <= LEARNING_DAYS else QUARANTINE_DAYS
        newest = d - lag
        oldest = max(0, newest - PROFILE_WINDOW_DAYS + 1)
        idx = [j for j in range(oldest, newest + 1) if ok[j]]
        out.append(Profile.from_smoothed(sm[idx], dts[idx], prior))
    return out


def parent_arrays(sim: ParentSim, prior: np.ndarray):
    profiles = daily_profiles(sim, prior)
    x = featurize_fast(sim.interactions, sim.charging, sim.battery, sim.start_dow, profiles)
    y, m = survival_targets(sim.interactions, exclude=positive_mask(sim))
    # profile-only baseline hazard for comparison: the marginal rate of bin t+k
    rates = np.zeros((len(x), HORIZON), dtype=np.float32)
    dts = np.array([daytype(sim.dow(q)) for q in range(sim.days + 1)])
    for d, prof in enumerate(profiles):
        tt = np.arange(d * BINS_PER_DAY, (d + 1) * BINS_PER_DAY)
        tgt = tt[:, None] + np.arange(1, HORIZON + 1)[None, :]
        tday, tbod = np.divmod(tgt, BINS_PER_DAY)
        rates[tt] = prof.rate[dts[tday], tbod]
    return x, y, m, rates
