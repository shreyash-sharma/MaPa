"""Turn people into model inputs the way the phone would see them (mirrors mapa_rhythm.dataset, with movement)."""
from __future__ import annotations

import numpy as np

from mapa_rhythm.dataset import LEARNING_DAYS, MIN_COVERAGE, QUARANTINE_DAYS
from mapa_rhythm.features import (BINS_PER_DAY, HORIZON, PROFILE_WINDOW_DAYS, Profile, activity_code, daytype,
                                  featurize_fast, smooth_days, survival_targets)

from .data import Person


def day_matrix(p: Person) -> np.ndarray:
    """Sign of life per day and bin: 1 used or moved, 0 seen and neither, NaN unknown."""
    code = activity_code(p.interactions, p.moved).reshape(p.days, BINS_PER_DAY).astype(np.float64)
    return np.where(code < 0, np.nan, code)


def clean_days(p: Person) -> np.ndarray:
    """Days the phone's guards would let into the profile: enough coverage, nothing injected, not tagged."""
    a = day_matrix(p)
    cov = (~np.isnan(a)).mean(1)
    injected = p.exclude.reshape(p.days, BINS_PER_DAY).any(1)
    tagged = np.zeros(p.days, dtype=bool)
    for d in p.tagged_days:
        if 0 <= d < p.days:
            tagged[d] = True
    return (cov >= MIN_COVERAGE) & ~injected & ~tagged


def population_prior(people: list[Person]) -> np.ndarray:
    num = np.zeros((3, BINS_PER_DAY))
    den = np.zeros((3, BINS_PER_DAY))
    for p in people:
        a = day_matrix(p)
        for d in np.nonzero(clean_days(p))[0]:
            dt = daytype(p.dow(d))
            o = ~np.isnan(a[d])
            num[dt, o] += a[d, o]
            den[dt, o] += 1
    return num / np.maximum(den, 1)


def daily_profiles(p: Person, prior: np.ndarray) -> list[Profile]:
    """Profile in force on each day, from days that cleared the quarantine (1 day while learning, then 7)."""
    sm = smooth_days(day_matrix(p))
    ok = clean_days(p)
    dts = np.array([daytype(p.dow(d)) for d in range(p.days)])
    out = []
    for d in range(p.days):
        lag = 1 if d + 1 <= LEARNING_DAYS else QUARANTINE_DAYS
        newest = d - lag
        oldest = max(0, newest - PROFILE_WINDOW_DAYS + 1)
        idx = [j for j in range(oldest, newest + 1) if ok[j]]
        out.append(Profile.from_smoothed(sm[idx], dts[idx], prior))
    return out


def person_arrays(p: Person, prior: np.ndarray):
    """x [n,18], hazard targets y [n,16], mask m [n,16], profile-only rates r [n,16] for one person."""
    profiles = daily_profiles(p, prior)
    charging = np.where(p.charging > 0, 1, 0)
    battery = np.where(p.battery >= 0, p.battery, 0)      # unknown battery reads as 0, as on a phone with no reading
    x = featurize_fast(p.interactions, charging, battery, p.start_dow, profiles, moved=p.moved)
    y, m = survival_targets(p.interactions, exclude=p.exclude, moved=p.moved)
    rates = np.zeros((len(x), HORIZON), dtype=np.float32)
    dts = np.array([daytype(p.dow(q)) for q in range(p.days + 1)])
    for d, prof in enumerate(profiles):
        tt = np.arange(d * BINS_PER_DAY, (d + 1) * BINS_PER_DAY)
        tgt = tt[:, None] + np.arange(1, HORIZON + 1)[None, :]
        tday, tbod = np.divmod(tgt, BINS_PER_DAY)
        rates[tt] = prof.rate[dts[np.minimum(tday, p.days)], tbod]
    return x, y, m, rates


def build(people: list[Person], prior: np.ndarray):
    """Arrays per person (lengths differ between people, so they are kept as lists)."""
    return [person_arrays(p, prior) for p in people]
