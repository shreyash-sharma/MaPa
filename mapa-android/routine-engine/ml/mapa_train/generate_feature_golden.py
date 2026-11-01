"""Create a small deterministic feature-contract vector; it contains no phone-log data."""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np

from mapa_rhythm.features import Profile, featurize, stream_quiet_history, TRAIN_FEATURES


def generate(path: Path) -> None:
    days, dow = 10, 3
    n = days * 96
    interactions = np.full(n, -1, dtype=np.int64)
    moved = np.full(n, -1, dtype=np.int64)
    charging = np.zeros(n, dtype=np.int64)
    battery = np.zeros(n, dtype=np.int64)
    for t in range(n):
        slot = t % 96
        if 28 <= slot < 88:
            interactions[t] = 0
        day = t // 96
        if 28 <= slot < 88 and slot in (30, 40 + day % 2, 55, 75 - day % 3, 82):
            interactions[t] = 1 + (day + slot) % 3
        if 32 <= slot < 86:
            moved[t] = int((slot + day) % 17 == 0)
        charging[t] = int((slot + 3 * day) % 31 < 3)
        battery[t] = 35 + ((t * 13) % 66)

    rate = np.empty((3, 96), dtype=np.float64)
    for dt in range(3):
        for slot in range(96):
            rate[dt, slot] = .03 + .9 * ((1 + math.sin((slot + 2 * dt) * math.pi / 48)) / 2)
    profile = Profile(rate, n_days=18)
    phone = featurize(interactions, charging, battery, dow, lambda _: profile, moved=moved)
    x = stream_quiet_history(phone, interactions)
    if x.shape != (n, TRAIN_FEATURES):
        raise ValueError(f"unexpected feature shape: {x.shape}")

    out = ["feature-contract-v1", f"dow {dow}", f"history {profile.n_days}"]
    for dt in range(3):
        out.append("rate " + str(dt) + " " + " ".join(f"{v:.9g}" for v in rate[dt]))
    for t in range(n):
        vals = [int(t), int(interactions[t]), int(moved[t]), int(charging[t]), int(battery[t])]
        out.append("bin " + " ".join(map(str, vals)) + " " + " ".join(f"{v:.9g}" for v in x[t]))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


if __name__ == "__main__":
    generate(Path(__file__).resolve().parents[2] / "models" / "golden_features_21.txt")
