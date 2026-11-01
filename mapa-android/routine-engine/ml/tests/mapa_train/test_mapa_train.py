"""Tests for mapa_train: phone parity, the leak guard, and a tiny train-then-test run on fixture data."""
import argparse
import csv
import gzip
from pathlib import Path

import numpy as np
import pytest

from mapa_rhythm.features import BINS_PER_DAY, Profile, featurize, featurize_fast
from mapa_train import data as D
from mapa_train import test as T
from mapa_train import train as TR
from mapa_train.parity import check

MODELS = Path(__file__).resolve().parents[3] / "models" / "cnn"


@pytest.mark.parametrize("size", ["xs", "s"])
def test_python_matches_the_phone_golden_file(size):
    r = check(MODELS / f"golden_cnn_{size}.txt", MODELS / f"rhythm_cnn_{size}.weights.txt")
    assert r["features_max_error"] == 0.0
    assert r["logits_max_error"] < 1e-3


def test_fast_features_equal_the_reference_with_movement():
    rng = np.random.default_rng(1)
    n = 5 * BINS_PER_DAY
    inter = rng.choice([-1, 0, 0, 0, 1, 3], n)
    moved = rng.choice([-1, 0, 1], n)
    ch, bat = rng.integers(0, 2, n), rng.integers(0, 101, n)
    prof = Profile(rng.uniform(0, .5, (3, BINS_PER_DAY)), 9)
    slow = featurize(inter, ch, bat, 3, lambda d: prof, moved=moved)
    fast = featurize_fast(inter, ch, bat, 3, [prof] * 5, moved=moved)
    assert np.abs(slow - fast).max() < 1e-6


def write_fixture(root: Path, n_syn=8, n_real=4, days=24):
    """Tiny MaPa-Syn layout: synthetic parents (train/validate/test) and real people (real_check)."""
    rng = np.random.default_rng(0)
    splits = []
    for folder, prefix, n, src in (("converted/synthetic", "syn", n_syn, "synthetic"),
                                   ("converted/ubiqlog/heartbeat_inferred", "ubiqlog-p", n_real, "ubiqlog")):
        d = root / folder
        d.mkdir(parents=True)
        with open(d / "parents.csv", "w", newline="") as f:
            w = csv.writer(f); w.writerow(["pid", "start_dow", "days", "observed_days"])
            for i in range(n):
                w.writerow([f"{prefix}{i:03d}", i % 7, days, days])
        with gzip.open(d / "bins.csv.gz", "wt", newline="") as f:
            w = csv.writer(f); w.writerow(["pid", "t", "interactions", "phone_on", "usage_ok", "network_ok", "charging",
                                           "battery", "paused", "moved", "present", "coverage_min", "observation_basis"])
            for i in range(n):
                for t in range(days * BINS_PER_DAY):
                    b = t % BINS_PER_DAY
                    awake = 28 <= b < 88
                    c = int(rng.random() < (0.35 if awake else 0.02))
                    w.writerow([f"{prefix}{i:03d}", t, c, 1, 1, 1, int(not awake), 80, 0, int(rng.random() < .05), 1, 15, "synthetic"])
        for i in range(n):
            split = (["train"] * 4 + ["validate"] * 2 + ["test"] * 2)[i] if src == "synthetic" else (
                "real_check" if i < 2 else None)
            if split:
                splits.append((f"{prefix}{i:03d}", src, split))
    v = root / D.VERSION
    v.mkdir(parents=True)
    with open(v / "splits.csv", "w", newline="") as f:
        w = csv.writer(f); w.writerow(["pid", "source", "split"]); w.writerows(splits)
    return root


def test_leak_guard_stops_training_on_held_out_people(tmp_path):
    root = write_fixture(tmp_path)
    with pytest.raises(D.LeakError):
        D.load(root, "synth_hand", "test", purpose="train")
    gen_fit = D.load(root, "ubiqlog", "gen_fit", purpose="train")
    assert {p.pid for p in gen_fit} == {"ubiqlog-p002", "ubiqlog-p003"}   # real_check people are never read


def test_missing_set_is_reported_not_invented(tmp_path):
    root = write_fixture(tmp_path)
    with pytest.raises(LookupError):
        D.load(root, "synth_learned", "test")


def test_train_then_test_end_to_end(tmp_path):
    root = write_fixture(tmp_path)
    runs = tmp_path / "runs"
    a = TR.parser().parse_args(["--exp", "A", "--data", str(root), "--out", str(runs), "--size", "xs",
                                "--epochs", "1", "--windows-per-epoch", "64", "--batch", "16"])
    m = TR.run(a)
    assert m["streaming_max_abs_error"] < 1e-4
    out = runs / "A-xs"
    assert check(out / "golden_cnn.txt", out / "rhythm_cnn.weights.txt")["pass"]
    assert "ubiqlog-p000" not in (out / "manifest.json").read_text()
    t = T.parser().parse_args(["--runs", str(runs), "--data", str(root), "--shipped", str(MODELS / "rhythm_cnn_xs.weights.txt"),
                               "--out", str(tmp_path / "report")])
    r = T.run(t)
    assert r["sets"]["synth_learned/test"].get("skipped")
    assert r["sets"]["ubiqlog/real_check"]["A-xs"]["people"] == 2
    assert (tmp_path / "report" / "REPORT.md").exists()
