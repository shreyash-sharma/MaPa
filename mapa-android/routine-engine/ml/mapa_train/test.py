"""Test trained models on the held-out sets of spec v0.1.2 and write results.json and REPORT.md.

    python -m mapa_train.test --runs runs --data D:\\GitHub\\MaPa\\data\\MaPaData

Sets: synth_hand/test, synth_learned/test, real_check (headline without clock-anomaly people, and all).
Models: every runs/<exp>-<size>/rhythm_cnn.weights.txt, the shipped model, and a profile-only baseline
(the personal statistics with no network, the "statistics only" baseline). The metric is next-interaction
log-loss on untouched bins; each number comes with people and bin counts and a 95% per-person bootstrap.

Alert metrics (catch rate, false alerts per person-week, stale-as-fresh) come from the harness traces in
MaPa-Syn stage S6/S7, not from this script.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd
import torch

from mapa_rhythm.features import BINS_PER_DAY

from . import data as D
from .prep import build
from .weights_io import load_cnn

SETS = [("synth_hand", "test"), ("synth_learned", "test"), ("ubiqlog", "real_check")]
HONEST = ("MaPa is pretrained on real smartphone logs. Its non-use data comes from a statistical model "
          "learned from real phone use.")


def per_person(prob_fn, arrays):
    """Per-person (sum of log-loss, scored targets) for all horizons, next bin and 4 h ahead."""
    eps = 1e-6
    rows = []
    for arr in arrays:
        x, y, m, r = arr
        p = np.clip(prob_fn(arr), eps, 1 - eps)
        m = m.copy(); m[:BINS_PER_DAY] = False                  # first day is burn-in
        ll = -(y * np.log(p) + (1 - y) * np.log(1 - p))
        rows.append([float(ll[m].sum()), int(m.sum()),
                     float(ll[:, :1][m[:, :1]].sum()), int(m[:, :1].sum()),
                     float(ll[:, -1:][m[:, -1:]].sum()), int(m[:, -1:].sum())])
    return np.array(rows) if rows else np.zeros((0, 6))


def summarise(rows: np.ndarray, rng, reps=1000) -> dict:
    out = {"people": int(len(rows))}
    for j, k in ((0, "all"), (2, "next_bin"), (4, "4h_ahead")):
        s, n = rows[:, j], rows[:, j + 1]
        out[f"bins_{k}"] = int(n.sum())
        if n.sum() == 0:
            out[f"logloss_{k}"] = None
            continue
        est = float(s.sum() / n.sum())
        boot = []
        for _ in range(reps):
            i = rng.integers(0, len(rows), len(rows))
            if n[i].sum() > 0:
                boot.append(s[i].sum() / n[i].sum())
        lo, hi = np.percentile(boot, [2.5, 97.5])
        out[f"logloss_{k}"] = est
        out[f"ci95_{k}"] = [float(lo), float(hi)]
    return out


def models(runs: Path, shipped: Path | None):
    found = []
    if runs.exists():
        for d in sorted(runs.iterdir()):
            w = d / "rhythm_cnn.weights.txt"
            if w.exists():
                found.append((d.name, w))
    if shipped and shipped.exists():
        found.append(("shipped-" + shipped.stem.replace(".weights", ""), shipped))
    return found


def run(a) -> dict:
    rng = np.random.default_rng(a.seed)
    splits = D.read_splits(a.data)
    people_csv = Path(a.data) / D.VERSION / "people.csv"
    anomalies = set()
    if people_csv.exists():
        pc = pd.read_csv(people_csv, dtype=str)
        if "clock_anomaly" in pc:
            anomalies = set(pc.loc[pc["clock_anomaly"] == "1", "pid"])
    ms = models(a.runs, a.shipped)
    if not ms:
        raise SystemExit("no models found: train first (python -m mapa_train.train) or pass --shipped")
    results = {"honest_wording": HONEST, "sets": {}, "models": [m for m, _ in ms]}
    for dataset, split in SETS:
        try:
            people = D.load(a.data, dataset, split, splits, purpose="test", limit=a.limit)
        except (LookupError, FileNotFoundError) as e:
            results["sets"][f"{dataset}/{split}"] = {"skipped": str(e)}
            print(f"skip {dataset}/{split}: {e}", flush=True)
            continue
        groups = {f"{dataset}/{split}": people}
        if split == "real_check" and anomalies:
            groups[f"{dataset}/{split} (headline, no clock anomalies)"] = [p for p in people if p.pid not in anomalies]
        for name, ps in groups.items():
            res = {}
            for mname, w in ms:
                net, t = load_cnn(w)
                arrays = build(ps, t["prior"])        # features depend on the prior the model was trained with
                def cnn_prob(arr, net=net):
                    with torch.no_grad():
                        return torch.sigmoid(net(torch.from_numpy(arr[0])[None])[0])[0].numpy()
                res[mname] = summarise(per_person(cnn_prob, arrays), rng)
                res[f"profile-only (prior of {mname})"] = summarise(per_person(lambda arr: arr[3], arrays), rng)
                print(f"{name} {mname}: {res[mname].get('logloss_all')}", flush=True)
            results["sets"][name] = res
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out / "results.json").write_text(json.dumps(results, indent=1))
    (a.out / "REPORT.md").write_text(report(results))
    return results


def fmt(m: dict, k: str) -> str:
    v = m.get(f"logloss_{k}")
    if v is None:
        return "–"
    lo, hi = m[f"ci95_{k}"]
    return f"{v:.4f} [{lo:.4f}, {hi:.4f}]"


def report(r: dict) -> str:
    lines = ["# Rhythm model test report (next-interaction log-loss, lower is better)", "",
             f"Wording for the card and the pitch: \"{r['honest_wording']}\"", "",
             "Every number: mean masked hazard log-loss on untouched bins after a 1-day burn-in, "
             "with a 95% per-person bootstrap interval. Real and synthetic sets are reported separately; "
             "nothing is averaged across them. Alert metrics come from the MaPa-Syn harness (S6/S7).", ""]
    labels = {"synth_hand/test": "parents from the hand-written generator (baseline)",
              "synth_learned/test": "parents from the generator learned from real phone logs",
              "ubiqlog/real_check": "real people, inferred observation, never trained on"}
    for name, res in r["sets"].items():
        base = name.split(" (")[0]
        lines.append(f"## {name}: {labels.get(base, '')}".rstrip(": "))
        if "skipped" in res:
            lines += ["", f"Skipped: {res['skipped']}", ""]
            continue
        any_m = next(iter(res.values()))
        lines += ["", f"People: {any_m['people']}. Scored targets (all horizons): {any_m['bins_all']}.", "",
                  "| Model | All horizons | Next 15 min | 4 h ahead |", "| --- | --- | --- | --- |"]
        for m, v in res.items():
            lines.append(f"| {m} | {fmt(v, 'all')} | {fmt(v, 'next_bin')} | {fmt(v, '4h_ahead')} |")
        lines.append("")
    a_b = [n for n in r["sets"] if "real_check" in n and "headline" in n]
    lines += ["## A vs B", "", "Headline: model B on synth_learned/test and on real_check. "
              "If B is not better than A on real_check, this report says so; compare the rows above."]
    for n in a_b:
        res = r["sets"][n]
        a = [k for k in res if k.startswith("A-") and "profile" not in k]
        b = [k for k in res if k.startswith("B-") and "profile" not in k]
        if a and b:
            va, vb = res[a[0]]["logloss_all"], res[b[0]]["logloss_all"]
            lo_b, hi_b = res[b[0]]["ci95_all"]
            lo_a, hi_a = res[a[0]]["ci95_all"]
            overlap = not (hi_b < lo_a or hi_a < lo_b)
            verdict = ("B is better than A" if vb < va else "B is NOT better than A") + (
                " (the 95% intervals overlap, so this is not clear)" if overlap else "")
            lines += ["", f"{n}: A {va:.4f}, B {vb:.4f}. {verdict}."]
    return "\n".join(lines) + "\n"


def parser():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=Path, default=Path("runs"))
    ap.add_argument("--data", type=Path, default=Path(r"D:\GitHub\MaPa\data\MaPaData"))
    ap.add_argument("--shipped", type=Path, default=Path("../models/cnn/rhythm_cnn_s.weights.txt"))
    ap.add_argument("--out", type=Path, default=Path("runs/report"))
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--seed", type=int, default=20261101)
    return ap


if __name__ == "__main__":
    torch.set_num_threads(4)
    run(parser().parse_args())
