"""Pretrain the population rhythm backbone on synthetic parents.

    python -m mapa_rhythm.train --out ../models

Writes the weights the Kotlin engine loads, the population prior, the silence-surprise
prior, golden vectors for the Kotlin parity test, and a metrics JSON.
"""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

from .dataset import clean_days, parent_arrays, population_prior, daily_profiles
from .features import BINS_PER_DAY, HORIZON, N_FEATURES, featurize
from .synth import generate

SILENCE_BIN = 0.25
SILENCE_BINS = 240
WINDOW = 4 * BINS_PER_DAY
BURN_IN = BINS_PER_DAY


class RhythmNet(nn.Module):
    """GRU over 15-minute ticks; head sees [h, x] and emits 16 hazard logits."""

    def __init__(self, hidden: int = 48):
        super().__init__()
        self.hidden = hidden
        self.gru = nn.GRU(N_FEATURES, hidden, batch_first=True)
        self.head = nn.Linear(hidden + N_FEATURES, HORIZON)

    def forward(self, x, h0=None):
        h, hn = self.gru(x, h0)
        return self.head(torch.cat([h, x], dim=-1)), hn

    def n_params(self):
        return sum(p.numel() for p in self.parameters())


class RhythmStep(nn.Module):
    """One tick, for on-device export: (x[1,16], h[1,H]) -> (logits[1,16], h'[1,H])."""

    def __init__(self, net: RhythmNet):
        super().__init__()
        self.cell = nn.GRUCell(N_FEATURES, net.hidden)
        self.cell.weight_ih.data.copy_(net.gru.weight_ih_l0.data)
        self.cell.weight_hh.data.copy_(net.gru.weight_hh_l0.data)
        self.cell.bias_ih.data.copy_(net.gru.bias_ih_l0.data)
        self.cell.bias_hh.data.copy_(net.gru.bias_hh_l0.data)
        self.head = net.head

    def forward(self, x, h):
        h2 = self.cell(x, h)
        return self.head(torch.cat([h2, x], dim=-1)), h2


def build_arrays(sims, prior):
    xs, ys, ms, rs = [], [], [], []
    for s in sims:
        x, y, m, r = parent_arrays(s, prior)
        xs.append(x); ys.append(y); ms.append(m); rs.append(r)
    return np.stack(xs), np.stack(ys), np.stack(ms), np.stack(rs)


def masked_bce(logits, y, m, weights):
    l = nn.functional.binary_cross_entropy_with_logits(logits, y, reduction="none") * weights
    return (l * m).sum() / (m * weights).sum().clamp(min=1)


def evaluate(net, X, Y, M, R):
    net.eval()
    with torch.no_grad():
        logits, _ = net(torch.from_numpy(X))
    p = torch.sigmoid(logits).numpy()
    m = M.copy()
    m[:, :BURN_IN] = False
    eps = 1e-6

    def ll(prob, k=None):
        sel = m if k is None else m[..., k]
        yy = Y if k is None else Y[..., k]
        pp = np.clip(prob if k is None else prob[..., k], eps, 1 - eps)
        return float(-(yy * np.log(pp) + (1 - yy) * np.log(1 - pp))[sel].mean())

    rates = np.clip(R, 0.002, 0.998)
    out = {
        "logloss_all": ll(p), "logloss_profile_all": ll(rates),
        "logloss_next_bin": ll(p, 0), "logloss_profile_next_bin": ll(rates, 0),
        "logloss_4h_ahead": ll(p, HORIZON - 1), "logloss_profile_4h_ahead": ll(rates, HORIZON - 1),
    }
    # calibration of the next-bin hazard in 10 buckets
    sel = m[..., 0]
    pp, yy = p[..., 0][sel], Y[..., 0][sel]
    edges = np.quantile(pp, np.linspace(0, 1, 11))
    cal = []
    for i in range(10):
        b = (pp >= edges[i]) & (pp <= edges[i + 1])
        cal.append([float(pp[b].mean()), float(yy[b].mean()), int(b.sum())])
    out["next_bin_calibration"] = cal
    return out, logits.numpy()


def stretch_surprises(interactions, hazard1, ok_day):
    """Python mirror of SilenceCalibrator.stretchSurprises over the clean days."""
    out = []
    nll, silent, seen = 0.0, 0, False
    prev_h, prev_t = None, None
    for t in range(len(interactions)):
        if not ok_day[t // BINS_PER_DAY]:
            prev_t = None
            continue
        if prev_t != t - 1:
            seen, prev_h = False, None
        c = interactions[t]
        if c > 0:
            if seen and silent > 0:
                out.append(nll)
            seen, nll, silent = True, 0.0, 0
        elif c == 0:
            if prev_h is None:
                seen = False
            else:
                nll += -math.log(1 - prev_h)
                silent += 1
        prev_h = float(hazard1[t])
        prev_t = t
    return out


def write_tensor(f, name, arr):
    arr = np.atleast_2d(np.asarray(arr, dtype=np.float32))
    f.write(f"tensor {name} {arr.shape[0]} {arr.shape[1]}\n")
    for row in arr:
        f.write(" ".join(repr(float(v)) for v in row) + "\n")


def export_weights(net: RhythmNet, prior, silence_hist, prior_days, path: Path):
    with open(path, "w") as f:
        f.write("mapa-rhythm 1\n")
        f.write(f"# hidden={net.hidden} inputs={N_FEATURES} horizon={HORIZON} params={net.n_params()}\n")
        write_tensor(f, "gru.weight_ih", net.gru.weight_ih_l0.detach().numpy())
        write_tensor(f, "gru.weight_hh", net.gru.weight_hh_l0.detach().numpy())
        write_tensor(f, "gru.bias_ih", net.gru.bias_ih_l0.detach().numpy())
        write_tensor(f, "gru.bias_hh", net.gru.bias_hh_l0.detach().numpy())
        write_tensor(f, "head.weight", net.head.weight.detach().numpy())
        write_tensor(f, "head.bias", net.head.bias.detach().numpy())
        write_tensor(f, "prior", prior)
        write_tensor(f, "silence_prior", silence_hist)
        write_tensor(f, "silence_prior_days", [prior_days])


def export_golden(net: RhythmNet, sim, prior, path: Path, hist_days=20, ticks=3 * BINS_PER_DAY):
    """Fixed profile from the first `hist_days` days, then features and logits for `ticks` ticks."""
    from .features import Profile, day_active
    hist = [(sim.dow(d), day_active(sim.interactions, d)) for d in range(hist_days)]
    prof = Profile.build(hist, prior)
    t0 = hist_days * BINS_PER_DAY
    x = featurize(sim.interactions, sim.charging, sim.battery, sim.start_dow, lambda d: prof, t0, t0 + ticks)
    step = RhythmStep(net).eval()
    h = torch.zeros(1, net.hidden)
    zs = []
    with torch.no_grad():
        for i in range(ticks):
            z, h = step(torch.from_numpy(x[i:i + 1]), h)
            zs.append(z[0].numpy())
    with open(path, "w") as f:
        f.write("golden 1\n")
        f.write(f"dow_offset {sim.start_dow}\n")
        f.write(f"history {hist_days}\n")
        for d in range(hist_days):
            c = sim.interactions[d * BINS_PER_DAY:(d + 1) * BINS_PER_DAY]
            f.write(f"day {d} {sim.dow(d)} " + " ".join(str(int(v)) for v in c) + "\n")
        for dt in range(3):
            f.write("rate " + " ".join(repr(float(v)) for v in prof.rate[dt]) + "\n")
        f.write(f"ticks {ticks}\n")
        for i in range(ticks):
            t = t0 + i
            f.write(f"tick {t} {int(sim.interactions[t])} {int(sim.charging[t])} {int(sim.battery[t])} "
                    + " ".join(repr(float(v)) for v in x[i]) + " "
                    + " ".join(repr(float(v)) for v in zs[i]) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("../models"))
    ap.add_argument("--train-parents", type=int, default=500)
    ap.add_argument("--val-parents", type=int, default=100)
    ap.add_argument("--days", type=int, default=84)
    ap.add_argument("--hidden", type=int, default=48)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--windows-per-epoch", type=int, default=4096)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(a.seed)
    torch.set_num_threads(4)
    rng = np.random.default_rng(a.seed)

    t = time.time()
    train = generate(a.train_parents, a.days, 11, "p")
    val = generate(a.val_parents, a.days, 12, "v")
    prior = population_prior(train)
    Xtr, Ytr, Mtr, _ = build_arrays(train, prior)
    Xva, Yva, Mva, Rva = build_arrays(val, prior)
    print(f"data ready in {time.time() - t:.0f}s: train {Xtr.shape} val {Xva.shape}", flush=True)

    net = RhythmNet(a.hidden)
    print(f"params: {net.n_params()}", flush=True)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs)
    wk = torch.ones(HORIZON)
    wk[0] = 2.0
    n_ticks = Xtr.shape[1]
    history = []
    for ep in range(a.epochs):
        net.train()
        tot = 0.0
        for _ in range(a.windows_per_epoch // a.batch):
            pi = rng.integers(0, len(train), a.batch)
            st = rng.integers(0, n_ticks - WINDOW, a.batch)
            idx = st[:, None] + np.arange(WINDOW)[None, :]
            xb = torch.from_numpy(Xtr[pi[:, None], idx])
            yb = torch.from_numpy(Ytr[pi[:, None], idx])
            mb = torch.from_numpy(Mtr[pi[:, None], idx]).float()
            mb[:, :BURN_IN] = 0
            logits, _ = net(xb)
            loss = masked_bce(logits, yb, mb, wk)
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(net.parameters(), 1.0)
            opt.step()
            tot += loss.item()
        sched.step()
        vm, _ = evaluate(net, Xva, Yva, Mva, Rva)
        history.append({"epoch": ep, "train_loss": tot / (a.windows_per_epoch // a.batch),
                        "val_logloss": vm["logloss_all"], "val_next_bin": vm["logloss_next_bin"]})
        print(history[-1], flush=True)

    metrics, logits = evaluate(net, Xva, Yva, Mva, Rva)
    metrics["params"] = net.n_params()
    metrics["hidden"] = a.hidden
    metrics["history"] = history

    # silence-surprise prior from the validation parents, with the untrained (identity) head
    surprises, prior_days = [], 0
    for i, s in enumerate(val):
        h1 = 1 / (1 + np.exp(-logits[i, :, 0]))
        ok = clean_days(s)
        ok[:2] = False  # hidden state still warming up
        prior_days += int(ok.sum())
        surprises += stretch_surprises(s.interactions, h1, ok)
    hist, _ = np.histogram(np.clip(surprises, 0, SILENCE_BIN * SILENCE_BINS - 1e-6),
                           bins=SILENCE_BINS, range=(0, SILENCE_BIN * SILENCE_BINS))
    metrics["silence_stretches"] = len(surprises)
    metrics["silence_prior_days"] = prior_days
    metrics["silence_quantiles"] = {q: float(np.quantile(surprises, q)) for q in (0.5, 0.9, 0.99, 0.995, 0.999)}

    torch.save({"state_dict": net.state_dict(), "hidden": a.hidden}, a.out / "rhythm_v1.pt")
    export_weights(net, prior, hist, prior_days, a.out / "rhythm_v1.weights.txt")
    export_golden(net, val[0], prior, a.out / "golden_v1.txt")
    np.save(a.out / "calibration_inputs.npy", Xva[:8, BURN_IN:BURN_IN + 2 * BINS_PER_DAY].reshape(-1, N_FEATURES))
    with open(a.out / "train_metrics.json", "w") as f:
        json.dump(metrics, f, indent=1)
    print(json.dumps({k: v for k, v in metrics.items() if k not in ("history", "next_bin_calibration")}, indent=1))


if __name__ == "__main__":
    main()
