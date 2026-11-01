"""Golden file for the phone: the full Hybrid pipeline on raw 15-minute counts, exactly as tested.

Raw counts in (interactions per bin, -1 = not observed). Everything else follows the evaluated path:
- phone inputs 0..20 from featurize + stream_quiet_history; movement unknown, charging = the night mask
  (slots < 28 or >= 88), battery = 80, as in the frozen data;
- slow lane: the last <= 30 quality days (>= 56 observed bins in 06:00-24:00), nine daily summaries each;
- day-one start: hourly use profile and quiet p90/p99.5 from those days, shrunk to the archetype prior with
  weight n / (n + 7); archetype from the mean summaries when n >= 3, else the population prior;
- model streaming state resets at each midnight;
- decision (selected config): score = 0.5 * (behaviour + surprise); line = max(0.5, p90 of the scores of the
  last 14 full days, once >= 96 samples); threshold = min(1, line + 0.1 * (1 - confidence));
  "please call" = score >= threshold or 32 silent daytime bins; "worth a look" = score >= 0.7 * threshold.

    python -m mapa_train.device_golden --out ../models/hybrid/golden_hybrid_pipeline.txt
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import torch

from mapa_release.learning import daily_summary
from mapa_rhythm.features import Profile, _quiet_runs, featurize, stream_quiet_history

RELEASE = Path(__file__).resolve().parents[4] / "data" / "MaPaData" / "mapa-rhythm-release"
PACKAGE = RELEASE / "huggingface" / "MaPa-Rhythm-Base"
DAY = (np.arange(96) >= 28) & (np.arange(96) < 88)
NIGHT_CHARGING = ((np.arange(96) < 28) | (np.arange(96) >= 88)).astype(np.int64)
LOG_CAP = np.log1p(192.0)


def load_model():
    from .hybrid_streaming import DailyEncoder, HybridStep
    from .models import Hybrid as PackagedHybrid
    selection = json.loads((PACKAGE / "selection.json").read_text())
    calibration = json.loads((PACKAGE / "forecast-calibration.json").read_text())
    path = PACKAGE / "model.pt"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == selection["checkpoint_sha256"]
    state = torch.load(path, map_location="cpu", weights_only=False)
    cfg = state["ablation_config"]
    model = PackagedHybrid(attention=bool(cfg["attention"]), personal=bool(cfg["personal"]), slow=bool(cfg["slow"]))
    model.load_state_dict(state["state_dict"], strict=True)
    model.next_use_blend = float(calibration["model_weight"])
    model.eval()
    return model, DailyEncoder(model), HybridStep(model)


def context(history_use, summaries, priors):
    """Columns 11, 12, 13, 18, 19 and the weight (column 15) for one day, from n prior quality days."""
    n = len(summaries)
    population = np.asarray(priors["population_prior"], np.float32)
    if n >= 3:
        z = (np.mean(summaries, axis=0) - np.asarray(priors["center"], np.float32)) / np.asarray(priors["scale"], np.float32)
        cluster = int(np.argmin(((np.asarray(priors["centroids"], np.float32) - z) ** 2).sum(axis=1)))
        prior = np.asarray(priors["archetype_priors"][cluster], np.float32)
    else:
        cluster, prior = -1, population
    if n == 0:
        return np.tile(prior, (96, 1)), 0.0, cluster
    weight = n / (n + 7.0)
    use = np.asarray(history_use, np.float64)
    known = use >= 0
    hourly = np.divide(np.where(known, use, 0).sum(axis=0), known.sum(axis=0),
                       out=np.full(96, float(prior[0])), where=known.sum(axis=0) > 0)
    slots = np.arange(96)
    personal = np.zeros((96, 5), np.float32)
    personal[:, 0] = hourly[(slots + 1) % 96]
    personal[:, 1] = np.stack([hourly[(slots + h) % 96] for h in range(1, 5)]).mean(axis=0)
    personal[:, 2] = np.stack([hourly[(slots + h) % 96] for h in range(5, 9)]).mean(axis=0)
    pooled = [r for day in use for r in _quiet_runs(day)]
    q = np.quantile(pooled, [.90, .995]) if len(pooled) >= 5 else np.array([16., 32.])
    personal[:, 3:] = (np.log1p(q) / LOG_CAP).astype(np.float32)
    return (weight * personal + (1 - weight) * prior).astype(np.float32), weight, cluster


def run(counts: np.ndarray, dow0: int, priors: dict):
    """counts: [days, 96] raw interactions (-1 unknown). Returns per-day records."""
    model, encoder, step = load_model()
    days = len(counts)
    flat = counts.reshape(-1)
    moved = np.full(flat.shape, -1, np.int64)
    charging = np.tile(NIGHT_CHARGING, days)
    battery = np.full(flat.shape, 80, np.int64)
    profile = Profile(np.full((3, 96), 0.1), 0)
    phone = featurize(flat, charging, battery, dow0, lambda _: profile, moved=moved)
    x_all = stream_quiet_history(phone, flat)
    quality, summaries, use_rows, score_history, records = [], [], [], [], []
    for d in range(days):
        c = counts[d]
        observed = c >= 0
        prior_days = [k for k in quality][-30:]
        n = len(prior_days)
        ctx, weight, cluster = context([use_rows[k] for k in prior_days], [summaries[k] for k in prior_days], priors)
        rows = torch.zeros(1, 30, 9)
        for i, k in enumerate(prior_days):
            rows[0, i] = torch.from_numpy(summaries[k])
        with torch.no_grad():
            slow = encoder(rows, torch.tensor([n], dtype=torch.int64))
        x = x_all[d * 96:(d + 1) * 96].copy()
        x[:, [11, 12, 13, 18, 19]] = ctx
        x[:, 15] = weight
        conv = torch.zeros(1, step.conv_state_size)
        gru = torch.zeros(1, 1, model.gru.hidden_size)
        memory = torch.zeros(1, 96, model.gru.hidden_size)
        mcount = torch.zeros(1, dtype=torch.int64)
        logits = np.zeros((96, 19), np.float32)
        with torch.no_grad():
            for s in range(96):
                out, conv, gru, memory, mcount = step(torch.from_numpy(x[s:s + 1]), conv, gru, memory, mcount, slow)
                logits[s] = out[0].numpy()
        sig = 1 / (1 + np.exp(-logits.astype(np.float64)))
        behaviour, confidence, hazard = sig[:, 17], sig[:, 18], sig[:, 0]
        silence = np.zeros(96)
        surprise = np.zeros(96)
        streak, survival = 0, 1.0
        for s in range(96):
            quiet = observed[s] and DAY[s] and c[s] == 0
            streak = streak + 1 if quiet else 0
            silence[s] = streak
            if s == 28:
                survival = 1.0
            if quiet:
                survival *= 1.0 - hazard[max(0, s - 1)]
                surprise[s] = 1.0 - survival
            else:
                survival = 1.0
        score = 0.5 * (behaviour + surprise)
        past = np.concatenate(score_history[-14:]) if score_history else np.zeros(0)
        line = max(0.5, float(np.quantile(past, 0.9))) if len(past) >= 96 else 0.5
        threshold = np.minimum(1.0, line + (1.0 - np.clip(confidence, 0, 1)) * 0.1)
        eligible = observed & DAY & (c == 0)
        call = ((score >= threshold) | (silence >= 32)) & eligible
        look = (score >= 0.7 * threshold) & eligible & ~call
        records.append(dict(day=d, n=n, weight=weight, cluster=cluster, line=line, x=x, logits=logits,
                            score=score, threshold=threshold, call=call, look=look))
        # After the day closes: its history entries, exactly as the evaluator stores them.
        full = int(observed[24:96].sum()) >= 56
        if full:
            score_history.append(score[eligible])
            quality.append(d)
        summaries.append(daily_summary(c, observed, np.full(96, -1), NIGHT_CHARGING.astype(np.float32),
                                       np.full(96, 80.0, np.float32), (dow0 + d) % 7))
        use_rows.append(np.where(observed, (c > 0).astype(np.float64), -1.0))
    return records


def sample_counts(days: int):
    """One derived training parent (frozen train split), with two edits that exercise the alert paths."""
    table = pq.read_table(RELEASE / "final-v2" / "train.parquet",
                          columns=["pid", "episode_id", "subset", "day_index", "t", "interactions", "observed", "dow"])
    df = table.to_pandas()
    df = df[(df.subset == "derived") & (df.episode_id.isna() | (df.episode_id == ""))]
    pid = sorted(df.pid.unique())[0]
    p = df[df.pid == pid]
    counts = np.full((days, 96), -1, np.int64)
    dow0 = None
    for day, g in p.groupby("day_index"):
        if day >= days:
            continue
        dow0 = int(g.dow.iloc[0]) - int(day) if dow0 is None else dow0
        slots = g.t.to_numpy() % 96
        counts[int(day), slots] = np.where(g.observed.to_numpy(bool), g.interactions.to_numpy(), -1)
    counts[days - 2, 40:52] = -1            # a gap: not observed, never treated as quiet
    counts[days - 1, 28:72] = 0             # a long quiet morning and midday
    return pid, counts, dow0 % 7


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=40)
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    priors = json.loads((PACKAGE / "cold-start-priors.json").read_text())
    pid, counts, dow0 = sample_counts(a.days)
    records = run(counts, dow0, priors)
    dump = {0, 1, 2, 3, 5, 10, 20, a.days - 3, a.days - 2, a.days - 1}
    lines = ["# MaPa hybrid phone pipeline golden. Source person: derived train parent (not identifying).",
             f"dow_offset {dow0}", f"days {a.days}"]
    lines += [f"counts {d} " + " ".join(str(int(v)) for v in counts[d]) for d in range(a.days)]
    for r in records:
        lines.append(f"day {r['day']} n {r['n']} weight {r['weight']:.9g} cluster {r['cluster']} line {r['line']:.9g} "
                     f"calls {int(r['call'].sum())} looks {int(r['look'].sum())}")
        if r["day"] in dump:
            for s in range(96):
                vals = list(r["x"][s]) + list(r["logits"][s]) + [r["score"][s], r["threshold"][s]]
                lines.append(f"bin {r['day']} {s} " + " ".join(f"{v:.9g}" for v in vals)
                             + f" {int(r['call'][s])} {int(r['look'][s])}")
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text("\n".join(lines) + "\n")
    print(json.dumps({"pid_hash": hashlib.sha256(pid.encode()).hexdigest()[:8], "days": a.days,
                      "calls": [int(r["call"].sum()) for r in records][-5:],
                      "looks": [int(r["look"].sum()) for r in records][-5:],
                      "lines": [round(r["line"], 3) for r in records][-5:],
                      "n": [r["n"] for r in records][-5:]}))


if __name__ == "__main__":
    main()
