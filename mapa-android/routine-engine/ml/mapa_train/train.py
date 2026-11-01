"""Train the on-device rhythm CNN for one experiment of spec v0.1.2.

    python -m mapa_train.train --exp A --data D:\\GitHub\\MaPa\\data\\MaPaData --out runs
    python -m mapa_train.train --exp B --data D:\\GitHub\\MaPa\\data\\MaPaData --out runs --size s

A: pretrain on synth_hand (the old hand-written generator; baseline), validate on synth_hand (validate).
B: pretrain on gen_fit real bins (every real person outside real_check) + synth_learned (parents sampled
   from the generator learned from real phone logs; train split),
   validate on synth_learned (validate).
Neither reads a `test` or `real_check` person: `data.guard` stops the run if one would be read.

Writes runs/<exp>-<size>/: rhythm_cnn.pt, rhythm_cnn.weights.txt (what the phone loads),
golden_cnn.txt (parity vectors), metrics.json and manifest.json (people, files and settings used).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

from mapa_rhythm.cnn import RECEPTIVE_FIELD, CausalCnn, CnnStep
from mapa_rhythm.features import BINS_PER_DAY, HORIZON, Profile, day_active, featurize
from mapa_rhythm.train import SILENCE_BIN, SILENCE_BINS, WINDOW, masked_bce, stretch_surprises
from .export import SIZES, check_streaming, export_weights

from . import data as D
from .prep import build, clean_days, day_matrix, population_prior

EXPERIMENTS = {
    "A": {"train": [("synth_hand", "train")], "validate": [("synth_hand", "validate")],
          "note": "pretrained on the hand-written synthetic parents only"},
    "B": {"train": [("s3", "gen_fit"), ("ubiqlog", "gen_fit"), ("synth_learned", "train")],
          "validate": [("synth_learned", "validate")],
          "note": "pretrained on real smartphone logs (gen_fit) and the learned non-use generator"},
}


def load_sets(root: Path, sets, purpose: str, episodes: bool, limit=None):
    splits = D.read_splits(root)
    people = []
    for dataset, split in sets:
        ps = D.load(root, dataset, split, splits, purpose=purpose, limit=limit)
        if episodes:
            for name in (f"{split}/episodes.jsonl", f"{split}/episodes.jsonl.gz"):
                D.apply_episodes(ps, Path(root) / D.VERSION / "episodes" / name)
        people += ps
    return people


def stack_windows(arrays, rng, batch):
    """A batch of WINDOW-bin windows, people drawn in proportion to their length."""
    lens = np.array([len(a[0]) for a in arrays])
    ok = np.nonzero(lens > WINDOW)[0]
    w = lens[ok] / lens[ok].sum()
    xs, ys, ms = [], [], []
    for i in rng.choice(ok, size=batch, p=w):
        x, y, m, _ = arrays[i]
        s = rng.integers(0, len(x) - WINDOW)
        xs.append(x[s:s + WINDOW]); ys.append(y[s:s + WINDOW]); ms.append(m[s:s + WINDOW])
    return torch.from_numpy(np.stack(xs)), torch.from_numpy(np.stack(ys)), torch.from_numpy(np.stack(ms)).float()


def logloss(net, arrays, burn_in=BINS_PER_DAY):
    """Mean masked hazard log-loss over every person (all horizons, next bin, 4 h ahead)."""
    eps = 1e-6
    tot = {"all": [0.0, 0], "next": [0.0, 0], "h16": [0.0, 0]}
    net.eval()
    with torch.no_grad():
        for x, y, m, _ in arrays:
            p = torch.sigmoid(net(torch.from_numpy(x)[None])[0])[0].numpy()
            m = m.copy(); m[:burn_in] = False
            p = np.clip(p, eps, 1 - eps)
            ll = -(y * np.log(p) + (1 - y) * np.log(1 - p))
            for k, sel in (("all", m), ("next", m[:, :1]), ("h16", m[:, -1:])):
                v = ll if k == "all" else ll[:, :1] if k == "next" else ll[:, -1:]
                tot[k][0] += float(v[sel].sum()); tot[k][1] += int(sel.sum())
    return {f"logloss_{k}": (s / n if n else float("nan")) for k, (s, n) in tot.items()}


def silence_prior(net, people, arrays):
    """Histogram of silence surprises on clean validation days: the phone's floor calibration."""
    surprises, days = [], 0
    with torch.no_grad():
        for p, (x, _, _, _) in zip(people, arrays):
            h1 = torch.sigmoid(net(torch.from_numpy(x)[None])[0])[0, :, 0].numpy()
            ok = clean_days(p)
            ok[:2] = False
            days += int(ok.sum())
            code = np.nan_to_num(day_matrix(p).reshape(-1), nan=-1).astype(int)
            surprises += stretch_surprises(code, h1, ok)
    hist, _ = np.histogram(np.clip(surprises, 0, SILENCE_BIN * SILENCE_BINS - 1e-6), bins=SILENCE_BINS,
                           range=(0, SILENCE_BIN * SILENCE_BINS))
    return hist, days, len(surprises)


def write_golden(net, p: D.Person, prior, path: Path, hist_days=20, ticks=3 * BINS_PER_DAY):
    """Golden vectors in the format the Kotlin CnnParityTest reads, from one validation person."""
    hist = [(p.dow(d), day_active(p.interactions, d, p.moved)) for d in range(hist_days)]
    prof = Profile.build(hist, prior)
    t0 = hist_days * BINS_PER_DAY
    ch = np.where(p.charging > 0, 1, 0); bat = np.where(p.battery >= 0, p.battery, 0)
    x = featurize(p.interactions, ch, bat, p.start_dow, lambda d: prof, t0, t0 + ticks, moved=p.moved)
    step = CnnStep(net).eval()
    h = torch.zeros(1, step.state_size)
    zs = []
    with torch.no_grad():
        for i in range(ticks):
            z, h = step(torch.from_numpy(x[i:i + 1]), h)
            zs.append(z[0].numpy())
    with open(path, "w") as f:
        f.write(f"golden 2\ndow_offset {p.start_dow}\nhistory {hist_days}\n")
        for d in range(hist_days):
            sl = slice(d * BINS_PER_DAY, (d + 1) * BINS_PER_DAY)
            f.write(f"day {d} {p.dow(d)} " + " ".join(str(int(v)) for v in p.interactions[sl]) + "\n")
            f.write("moved " + " ".join(str(int(v)) for v in p.moved[sl]) + "\n")
        for dt in range(3):
            f.write("rate " + " ".join(repr(float(v)) for v in prof.rate[dt]) + "\n")
        f.write(f"ticks {ticks}\n")
        for i in range(ticks):
            t = t0 + i
            f.write(f"tick {t} {int(p.interactions[t])} {int(p.moved[t])} {int(ch[t])} {int(bat[t])} "
                    + " ".join(repr(float(v)) for v in x[i]) + " " + " ".join(repr(float(v)) for v in zs[i]) + "\n")
    return x, np.stack(zs)


def run(a) -> dict:
    exp = EXPERIMENTS[a.exp]
    out = Path(a.out) / f"{a.exp}-{a.size}"
    out.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    train_p = load_sets(a.data, exp["train"], "train", episodes=True, limit=a.limit)
    val_p = load_sets(a.data, exp["validate"], "validate", episodes=True, limit=a.limit)
    prior = population_prior(train_p)
    tr, va = build(train_p, prior), build(val_p, prior)
    print(f"[{a.exp}] {len(train_p)} train people, {len(val_p)} validation people, data in {time.time() - t0:.0f}s", flush=True)

    torch.manual_seed(a.seed)
    rng = np.random.default_rng(a.seed)
    if a.init:                                  # fine-tune from an existing weights file, e.g. the shipped model
        from .weights_io import load_cnn
        net, _ = load_cnn(Path(a.init))
        net.train()
    else:
        net = CausalCnn(SIZES[a.size])
    opt = torch.optim.Adam(net.parameters(), lr=a.lr)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs)
    wk = torch.ones(HORIZON); wk[0] = 2.0
    best, best_state, history, bad = float("inf"), None, [], 0
    for ep in range(a.epochs):
        net.train()
        tot = 0.0
        steps = max(1, a.windows_per_epoch // a.batch)
        for _ in range(steps):
            xb, yb, mb = stack_windows(tr, rng, a.batch)
            mb[:, :RECEPTIVE_FIELD + 1] = 0         # the first receptive field sees padding, not history
            logits, _ = net(xb)
            loss = masked_bce(logits, yb, mb, wk)
            opt.zero_grad(); loss.backward()
            nn.utils.clip_grad_norm_(net.parameters(), 1.0)
            opt.step()
            tot += loss.item()
        sched.step()
        vm = logloss(net, va)
        history.append({"epoch": ep, "train_loss": tot / steps, **vm})
        print(f"[{a.exp}] {history[-1]}", flush=True)
        if vm["logloss_all"] < best - 1e-5:
            best, bad = vm["logloss_all"], 0
            best_state = {k: v.clone() for k, v in net.state_dict().items()}
        else:
            bad += 1
            if bad >= a.patience:
                break
    net.load_state_dict(best_state)
    net.eval()

    hist, prior_days, stretches = silence_prior(net, val_p, va)
    torch.save({"state_dict": net.state_dict(), "channels": net.channels}, out / "rhythm_cnn.pt")
    export_weights(net, prior, hist, prior_days, out / "rhythm_cnn.weights.txt")
    gp = next(p for p in val_p if p.days >= 23)
    x, zs = write_golden(net, gp, prior, out / "golden_cnn.txt")
    metrics = {"experiment": a.exp, "note": exp["note"], "size": a.size, "params": net.n_params(),
               "train_people": len(train_p), "validate_people": len(val_p), "best_val_logloss_all": best,
               "streaming_max_abs_error": check_streaming(net, x, zs), "silence_stretches": stretches,
               "silence_prior_days": prior_days, "history": history, "train_seconds": time.time() - t0}
    (out / "metrics.json").write_text(json.dumps(metrics, indent=1))
    ids = sorted(p.pid for p in train_p + val_p)
    manifest = {"experiment": a.exp, "train_sets": exp["train"], "validate_sets": exp["validate"],
                "people_sha256": hashlib.sha256("\n".join(ids).encode()).hexdigest(),
                "train_pids": sorted(p.pid for p in train_p), "validate_pids": sorted(p.pid for p in val_p),
                "splits_sha256": hashlib.sha256((Path(a.data) / D.VERSION / "splits.csv").read_bytes()).hexdigest(),
                "args": {k: str(v) for k, v in vars(a).items()}}
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1))
    print(f"[{a.exp}] done: best validation log-loss {best:.4f}, streaming error {metrics['streaming_max_abs_error']:.1e}", flush=True)
    return metrics


def parser():
    ap = argparse.ArgumentParser()
    ap.add_argument("--exp", choices=sorted(EXPERIMENTS), required=True)
    ap.add_argument("--data", type=Path, default=Path(r"D:\GitHub\MaPa\data\MaPaData"))
    ap.add_argument("--out", type=Path, default=Path("runs"))
    ap.add_argument("--size", choices=sorted(SIZES), default="s")
    ap.add_argument("--init", type=Path, default=None, help="start from a weights file (fine-tune)")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--patience", type=int, default=6)
    ap.add_argument("--windows-per-epoch", type=int, default=4096)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--limit", type=int, default=None, help="at most this many people per set (smoke runs)")
    return ap


if __name__ == "__main__":
    torch.set_num_threads(4)
    run(parser().parse_args())
