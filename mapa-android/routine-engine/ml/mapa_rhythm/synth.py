"""Synthetic parent phone-activity generator.

Produces 15-minute bins of what the parent's phone would observe (interaction
counts, charging, battery) plus the monitoring plumbing (phone on, usage access,
network, pause) and a ground-truth list of injected episodes.

Everything here is invented: personas are drawn from hand-set ranges, not from
real elderly phone-use data. The generator exists to exercise the engine and to
compare rules against the model on equal footing, not to prove real-world accuracy.
"""
from __future__ import annotations

import csv
import gzip
import json
from dataclasses import dataclass, asdict, field
from pathlib import Path

import numpy as np

BINS_PER_DAY = 96
BIN_MIN = 15


def bins(minutes: float) -> int:
    return int(round(minutes / BIN_MIN))


# Episode labels
POSITIVE = "positive"      # phone on, untouched for hours: worth a check-in call
BENIGN = "benign"          # ordinary life; an alert here is a false alert
AMBIGUOUS = "ambiguous"    # unwell stretches: neither counted as a miss nor a false alert
MONITORING = "monitoring"  # plumbing gaps; inactivity alerts must not come from these
ROUTINE = "routine"        # permanent routine shift; should trigger the new-normal question


@dataclass
class Persona:
    wake_mu: float          # minutes after midnight
    wake_sd: float
    sleep_mu: float
    sleep_sd: float
    sunday_shift: float     # extra minutes of sleep-in on Sunday
    saturday_shift: float
    p_awake: float          # share of awake bins with at least one interaction
    intensity: float        # mean interactions in an active bin
    persist: float          # how sticky phone sessions are
    morning_mult: float
    evening_mult: float
    nap_p: float
    walk_p: float           # daily morning walk without the phone
    walk_len: float
    night_wake_p: float
    phone_off_at_night: bool
    forget_charge_p: float
    drain_idle: float       # % per bin
    drain_use: float        # % per interaction


@dataclass
class Episode:
    kind: str
    label: str
    start: int              # absolute bin, inclusive
    end: int                # absolute bin, exclusive
    tagged: bool = False
    tag_entered: int = -1   # absolute bin the family or parent tagged it


@dataclass
class ParentSim:
    pid: str
    start_dow: int                      # weekday of day 0, 0 = Monday
    days: int
    persona: Persona
    interactions: np.ndarray            # int16, -1 when not observed
    phone_on: np.ndarray
    usage_ok: np.ndarray
    network_ok: np.ndarray
    charging: np.ndarray
    battery: np.ndarray                 # uint8 percent
    paused: np.ndarray
    episodes: list = field(default_factory=list)

    def dow(self, day: int) -> int:
        return (self.start_dow + day) % 7

    @property
    def observed(self) -> np.ndarray:
        return self.interactions >= 0


def sample_persona(rng: np.random.Generator) -> Persona:
    return Persona(
        wake_mu=float(np.clip(rng.normal(390, 50), 300, 540)),
        wake_sd=float(rng.uniform(10, 40)),
        sleep_mu=float(np.clip(rng.normal(1350, 45), 1260, 1425)),
        sleep_sd=float(rng.uniform(10, 40)),
        sunday_shift=float(rng.uniform(0, 60)),
        saturday_shift=float(rng.uniform(0, 25)),
        p_awake=float(rng.uniform(0.15, 0.6)),
        intensity=float(rng.uniform(1.0, 4.0)),
        persist=float(rng.uniform(0.2, 0.55)),
        morning_mult=float(rng.uniform(1.1, 2.0)),
        evening_mult=float(rng.uniform(1.0, 1.8)),
        nap_p=float(rng.uniform(0.0, 0.8)),
        walk_p=float(rng.uniform(0.0, 0.7)),
        walk_len=float(rng.uniform(30, 90)),
        night_wake_p=float(rng.uniform(0.05, 0.35)),
        phone_off_at_night=bool(rng.random() < 0.08),
        forget_charge_p=float(rng.uniform(0.0, 0.08)),
        drain_idle=float(rng.uniform(0.3, 0.6)),
        drain_use=float(rng.uniform(0.05, 0.2)),
    )


def _poisson_starts(rng, days, per_day_rate, min_day=0):
    starts = []
    for d in range(min_day, days):
        if rng.random() < per_day_rate:
            starts.append(d)
    return starts


def simulate_parent(pid: str, days: int, rng: np.random.Generator,
                    with_events: bool = True, persona: Persona | None = None) -> ParentSim:
    p = persona or sample_persona(rng)
    start_dow = int(rng.integers(0, 7))
    n = days * BINS_PER_DAY
    episodes: list[Episode] = []

    # ---- day-level modifiers -------------------------------------------------
    wake_shift = np.zeros(days)
    sleep_shift = np.zeros(days)
    act_mult = np.ones(days)
    nap_extra = np.zeros(days)
    night_mult = np.ones(days)
    forced_silence = np.zeros(n, dtype=bool)   # phone on, nobody touches it
    phone_off = np.zeros(n, dtype=bool)
    net_down = np.zeros(n, dtype=bool)
    usage_lost = np.zeros(n, dtype=bool)
    paused = np.zeros(n, dtype=bool)

    def day_span(d0, d1):
        return d0 * BINS_PER_DAY, d1 * BINS_PER_DAY

    def add_tagged(kind, label, d0, length, tag_p):
        d1 = min(days, d0 + length)
        s, e = day_span(d0, d1)
        tagged = bool(rng.random() < tag_p)
        entered = s + int(rng.integers(0, 4 * BINS_PER_DAY)) if tagged else -1
        episodes.append(Episode(kind, label, s, e, tagged, entered))
        return d1

    if with_events:
        # Permanent routine shift for some parents, after the first month.
        if rng.random() < 0.3 and days > 50:
            d0 = int(rng.integers(35, max(36, days - 25)))
            delta = float(rng.choice([-1, 1]) * rng.uniform(45, 120))
            wake_shift[d0:] += delta
            sleep_shift[d0:] += delta * 0.6
            s, _ = day_span(d0, d0)
            episodes.append(Episode("routine_shift", ROUTINE, s, n))

        busy = np.zeros(days, dtype=bool)
        for d in _poisson_starts(rng, days, 1 / 70, min_day=5):
            if busy[d]:
                continue
            ln = int(rng.integers(5, 11))
            d1 = add_tagged("unwell", AMBIGUOUS, d, ln, 0.4)
            busy[d:d1] = True
            wake_shift[d:d1] += rng.uniform(30, 120)
            act_mult[d:d1] *= rng.uniform(0.4, 0.7)
            nap_extra[d:d1] += 0.5
        for d in _poisson_starts(rng, days, 1 / 60):
            if busy[d]:
                continue
            ln = int(rng.integers(2, 7))
            d1 = add_tagged("travel", BENIGN, d, ln, 0.5)
            busy[d:d1] = True
            wake_shift[d:d1] += rng.normal(0, 60)
            act_mult[d:d1] *= rng.uniform(0.5, 1.2)
            s, e = day_span(d, d1)
            for _ in range(int(rng.integers(1, 3))):
                o = int(rng.integers(s, e))
                net_down[o:o + int(rng.integers(4, 24))] = True
        for d in _poisson_starts(rng, days, 1 / 50):
            if busy[d]:
                continue
            ln = int(rng.integers(3, 8))
            d1 = add_tagged("guests", BENIGN, d, ln, 0.2)
            busy[d:d1] = True
            act_mult[d:d1] *= rng.uniform(0.4, 0.7)
        for d in _poisson_starts(rng, days, 1 / 45):
            if busy[d]:
                continue
            ln = int(rng.integers(1, 3))
            d1 = add_tagged("festival", BENIGN, d, ln, 0.3)
            busy[d:d1] = True
            sleep_shift[d:d1] += rng.uniform(30, 90)
            act_mult[d:d1] *= rng.uniform(1.0, 1.5)
            night_mult[d:d1] *= 3
        for d in _poisson_starts(rng, days, 1 / 14):
            wake_shift[d] += rng.uniform(60, 150)
            s, _ = day_span(d, d)
            episodes.append(Episode("sleep_in", BENIGN, s, s + BINS_PER_DAY // 2))

    # ---- per-day wake/sleep times ------------------------------------------
    wake = np.zeros(days)
    sleep = np.zeros(days)
    for d in range(days):
        dow = (start_dow + d) % 7
        ws = p.sunday_shift if dow == 6 else (p.saturday_shift if dow == 5 else 0.0)
        wake[d] = rng.normal(p.wake_mu + ws, p.wake_sd) + wake_shift[d]
        sleep[d] = rng.normal(p.sleep_mu + ws * 0.3, p.sleep_sd) + sleep_shift[d]
        sleep[d] = max(sleep[d], wake[d] + 10 * 60)

    # ---- activity probability per bin ---------------------------------------
    p_on = np.zeros(n)
    awake = np.zeros(n, dtype=bool)
    for d in range(days):
        base = d * BINS_PER_DAY
        w, s = wake[d], sleep[d]
        for b in range(BINS_PER_DAY):
            m = b * BIN_MIN + BIN_MIN / 2
            t = base + b
            # previous day's sleep can run past midnight
            if d > 0 and sleep[d - 1] > 1440 and m < sleep[d - 1] - 1440:
                awake[t] = True
                p_on[t] = p.p_awake * act_mult[d - 1]
                continue
            if w <= m < min(s, 1440):
                awake[t] = True
                mult = act_mult[d]
                if m < w + 120:
                    mult *= p.morning_mult
                if 19 * 60 <= m < 21.5 * 60:
                    mult *= p.evening_mult
                if 13.5 * 60 <= m < 15 * 60 and rng.random() < min(1.0, p.nap_p + nap_extra[d]):
                    mult *= 0.1
                p_on[t] = p.p_awake * mult
            else:
                p_on[t] = 0.004 * night_mult[d]
        # morning walk without the phone
        if rng.random() < p.walk_p:
            ws_ = base + bins(w + rng.uniform(5, 40))
            forced_silence[ws_:ws_ + bins(p.walk_len)] = True
        # night wake-up: glance at the phone
        if rng.random() < p.night_wake_p:
            t = base + bins(rng.uniform(60, max(61, w - 60)))
            if t < n:
                p_on[t] = 0.9

    p_on = np.clip(p_on, 0, 0.97)

    if with_events:
        # benign outing with the phone left at home
        for d in _poisson_starts(rng, days, 1 / 7):
            s = d * BINS_PER_DAY + bins(rng.uniform(max(wake[d] + 90, 9 * 60), max(wake[d] + 91, 17 * 60)))
            e = s + bins(rng.uniform(120, 240))
            forced_silence[s:e] = True
            episodes.append(Episode("outing_phone_home", BENIGN, s, e))
        # positive: phone on, untouched for hours
        for d in _poisson_starts(rng, days, 1 / 25, min_day=3):
            if rng.random() < 0.4:
                s = d * BINS_PER_DAY + bins(wake[d])
            else:
                s = d * BINS_PER_DAY + bins(rng.uniform(wake[d] + 60, max(wake[d] + 61, min(sleep[d], 1440) - 120)))
            e = min(n, s + bins(rng.uniform(180, 840)))
            forced_silence[s:e] = True
            episodes.append(Episode("halt", POSITIVE, s, e))
        # monitoring plumbing
        for d in _poisson_starts(rng, days, 1 / 15):
            s = d * BINS_PER_DAY + int(rng.integers(0, BINS_PER_DAY))
            e = min(n, s + int(rng.integers(4, 33)))
            net_down[s:e] = True
            episodes.append(Episode("network_outage", MONITORING, s, e))
        for d in _poisson_starts(rng, days, 1 / 40):
            s = d * BINS_PER_DAY + int(rng.integers(0, BINS_PER_DAY))
            e = min(n, s + int(rng.integers(8, 49)))
            phone_off[s:e] = True
            episodes.append(Episode("phone_switched_off", MONITORING, s, e))
        for d in _poisson_starts(rng, days, 1 / 150):
            s = d * BINS_PER_DAY + int(rng.integers(0, BINS_PER_DAY))
            e = min(n, s + 2 * BINS_PER_DAY)
            usage_lost[s:e] = True
            episodes.append(Episode("usage_access_lost", MONITORING, s, e))
        for d in _poisson_starts(rng, days, 1 / 90):
            s = d * BINS_PER_DAY + int(rng.integers(0, BINS_PER_DAY))
            e = min(n, s + int(rng.integers(12, 97)))
            paused[s:e] = True
            episodes.append(Episode("paused", MONITORING, s, e))

    # ---- Markov sessions ---------------------------------------------------
    act = np.zeros(n, dtype=bool)
    u = rng.random(n)
    prev = False
    for t in range(n):
        q = p_on[t]
        if q <= 0:
            prev = False
            continue
        b = min(0.95, q + p.persist * (1 - q))
        a = q * (1 - b) / max(1e-6, 1 - q)
        a = min(a, 0.97)
        cur = u[t] < (b if prev else a)
        act[t] = cur
        prev = cur
    act &= ~forced_silence
    counts = np.where(act, 1 + rng.poisson(max(0.0, p.intensity - 1), n), 0).astype(np.int16)

    # ---- battery and charging ---------------------------------------------
    charging = np.zeros(n, dtype=bool)
    battery = np.zeros(n, dtype=np.float32)
    on = np.ones(n, dtype=bool)
    level = float(rng.uniform(60, 100))
    plug_nights = rng.random(days + 1) >= p.forget_charge_p
    for t in range(n):
        d, b = divmod(t, BINS_PER_DAY)
        m = b * BIN_MIN
        asleep = not awake[t]
        # charges overnight unless forgot; halts stop new plug-ins
        plugging = asleep and plug_nights[d] and not forced_silence[t]
        if plugging or (charging[t - 1] if t > 0 else False) and asleep:
            charging[t] = True
        if level <= 0 and not charging[t]:
            on[t] = False
            # someone notices in the morning and plugs it in
            if awake[t] and not forced_silence[t] and rng.random() < 0.15:
                charging[t] = True
        if charging[t]:
            level = min(100.0, level + 6.0)
        elif on[t]:
            level = max(0.0, level - p.drain_idle - p.drain_use * counts[t])
        battery[t] = level
        if p.phone_off_at_night and asleep and m < 5 * 60 + 30:
            on[t] = False

    on &= ~phone_off
    counts[~on] = 0
    usage_ok = ~usage_lost
    net_ok = ~net_down
    observed = on & usage_ok
    inter = np.where(observed, counts, -1).astype(np.int16)

    episodes.sort(key=lambda e: e.start)
    return ParentSim(pid, start_dow, days, p, inter, on, usage_ok, net_ok & on,
                     charging & on, np.round(battery).astype(np.uint8), paused, episodes)


def generate(n_parents: int, days: int, seed: int, prefix: str, with_events=True) -> list[ParentSim]:
    rng = np.random.default_rng(seed)
    return [simulate_parent(f"{prefix}{i:04d}", days, rng, with_events) for i in range(n_parents)]


def positive_mask(sim: ParentSim) -> np.ndarray:
    m = np.zeros(sim.days * BINS_PER_DAY, dtype=bool)
    for e in sim.episodes:
        if e.label == POSITIVE:
            m[e.start:e.end] = True
    return m


def write_harness_csv(sims: list[ParentSim], out_dir: Path) -> None:
    """Write the files the Kotlin harness reads."""
    out_dir.mkdir(parents=True, exist_ok=True)
    with gzip.open(out_dir / "bins.csv.gz", "wt", newline="") as f:
        w = csv.writer(f)
        w.writerow(["pid", "t", "interactions", "phone_on", "usage_ok", "network_ok", "charging", "battery", "paused"])
        for s in sims:
            for t in range(s.days * BINS_PER_DAY):
                w.writerow([s.pid, t, int(s.interactions[t]), int(s.phone_on[t]), int(s.usage_ok[t]),
                            int(s.network_ok[t]), int(s.charging[t]), int(s.battery[t]), int(s.paused[t])])
    with open(out_dir / "parents.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["pid", "start_dow", "days"])
        for s in sims:
            w.writerow([s.pid, s.start_dow, s.days])
    with open(out_dir / "episodes.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["pid", "kind", "label", "start", "end", "tagged", "tag_entered"])
        for s in sims:
            for e in s.episodes:
                w.writerow([s.pid, e.kind, e.label, e.start, e.end, int(e.tagged), e.tag_entered])
    with open(out_dir / "personas.json", "w") as f:
        json.dump({s.pid: asdict(s.persona) for s in sims}, f, indent=1)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--parents", type=int, default=200)
    ap.add_argument("--days", type=int, default=120)
    ap.add_argument("--seed", type=int, default=3)
    ap.add_argument("--out", type=Path, default=Path("data/harness"))
    a = ap.parse_args()
    sims = generate(a.parents, a.days, a.seed, "h")
    write_harness_csv(sims, a.out)
    print(f"wrote {len(sims)} parents x {a.days} days to {a.out}")
