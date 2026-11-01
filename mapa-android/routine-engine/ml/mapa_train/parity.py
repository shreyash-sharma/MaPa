"""Check that the Python features and CNN give the phone's numbers, using the golden files the Kotlin
CnnParityTest reads. Any training run is only valid if this passes first.

    python -m mapa_train.parity --models ../models/cnn
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import torch

from mapa_rhythm.cnn import CnnStep
from mapa_rhythm.features import N_FEATURES, TRAIN_FEATURES, Profile, featurize, stream_quiet_history

from .weights_io import load_cnn


def read_golden(path: Path):
    g = {"days": [], "moved": [], "rate": [], "ticks": []}
    for line in Path(path).read_text().splitlines():
        p = line.split()
        if not p:
            continue
        if p[0] == "dow_offset":
            g["dow"] = int(p[1])
        elif p[0] == "history":
            g["history"] = int(p[1])
        elif p[0] == "day":
            g["days"].append([int(v) for v in p[3:]])
        elif p[0] == "moved":
            g["moved"].append([int(v) for v in p[1:]])
        elif p[0] == "rate":
            g["rate"].append([float(v) for v in p[1:]])
        elif p[0] == "tick":
            g["ticks"].append([float(v) for v in p[1:]])
    return g


def check(golden: Path, weights: Path, tol: float = 1e-4) -> dict:
    g = read_golden(golden)
    ticks = np.array(g["ticks"])
    t0 = int(ticks[0, 0])
    n = t0 + len(ticks)
    inter = np.full(n, -1, dtype=np.int64)
    moved = np.full(n, -1, dtype=np.int64)
    charging = np.zeros(n, dtype=np.int64)
    battery = np.zeros(n, dtype=np.int64)
    for d, (c, mv) in enumerate(zip(g["days"], g["moved"])):
        inter[d * 96:(d + 1) * 96] = c
        moved[d * 96:(d + 1) * 96] = mv
    t = ticks[:, 0].astype(int)
    inter[t], moved[t], charging[t], battery[t] = ticks[:, 1], ticks[:, 2], ticks[:, 3], ticks[:, 4]
    prof = Profile(np.array(g["rate"]), g["history"])
    net, _ = load_cnn(weights)
    x18 = featurize(inter, charging, battery, g["dow"], lambda d: prof, t0, n, moved=moved)
    x = stream_quiet_history(x18, inter) if net.input_features == TRAIN_FEATURES else x18
    n_features = net.input_features
    want_x = ticks[:, 5:5 + n_features]
    want_z = ticks[:, 5 + n_features:]
    step = CnnStep(net).eval()
    h = torch.zeros(1, step.state_size)
    zs = []
    with torch.no_grad():
        for i in range(len(x)):
            z, h = step(torch.from_numpy(want_x[i:i + 1].astype(np.float32)), h)
            zs.append(z[0].numpy())
    fx = float(np.abs(x - want_x).max())
    fz = float(np.abs(np.stack(zs) - want_z).max())
    return {"golden": Path(golden).name, "ticks": len(x), "features_max_error": fx, "logits_max_error": fz,
            "pass": fx <= tol and fz <= 1e-3}


def check_feature_contract(golden: Path, tol: float = 1e-5) -> dict:
    """Rebuild all 21 inputs from the raw deterministic fixture."""
    dow = history = None
    rates = np.zeros((3, 96), dtype=np.float64)
    rows = []
    for line in Path(golden).read_text().splitlines():
        p = line.split()
        if not p:
            continue
        if p[0] == "dow":
            dow = int(p[1])
        elif p[0] == "history":
            history = int(p[1])
        elif p[0] == "rate":
            rates[int(p[1])] = np.asarray(p[2:], dtype=np.float64)
        elif p[0] == "bin":
            rows.append([float(v) for v in p[1:]])
    if dow is None or history is None or len(rows) % 96:
        raise ValueError("invalid feature contract golden")
    rows = np.asarray(rows, dtype=np.float64)
    t = rows[:, 0].astype(np.int64)
    inter = rows[:, 1].astype(np.int64)
    moved = rows[:, 2].astype(np.int64)
    charging = rows[:, 3].astype(np.int64)
    battery = rows[:, 4].astype(np.int64)
    expected = rows[:, 5:].astype(np.float32)
    profile = Profile(rates, history)
    phone = featurize(inter, charging, battery, dow, lambda _: profile, moved=moved)
    actual = stream_quiet_history(phone, inter)
    error = float(np.abs(actual - expected).max())
    movement_error = float(np.abs(actual[:, 16] - (moved == 1).astype(np.float32)).max())
    known_error = float(np.abs(actual[:, 17] - (moved >= 0).astype(np.float32)).max())
    return {"golden": Path(golden).name, "rows": len(actual), "features": actual.shape[-1],
            "max_error": error, "movement_error": movement_error, "movement_known_error": known_error,
            "pass": actual.shape[-1] == TRAIN_FEATURES and error <= tol and movement_error == 0 and known_error == 0}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", type=Path, default=Path("../models/cnn"))
    ap.add_argument("--sizes", default="xs,s,m,l")
    ap.add_argument("--feature-golden", type=Path,
                    default=Path(__file__).resolve().parents[2] / "models" / "golden_features_21.txt")
    ap.add_argument("--features-only", action="store_true")
    a = ap.parse_args()
    feature_result = check_feature_contract(a.feature_golden)
    print(feature_result)
    ok = feature_result["pass"]
    if a.features_only:
        sys.exit(0 if ok else 1)
    for s in a.sizes.split(","):
        r = check(a.models / f"golden_cnn_{s}.txt", a.models / f"rhythm_cnn_{s}.weights.txt")
        ok &= r["pass"]
        print(r)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
