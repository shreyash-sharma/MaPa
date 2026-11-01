"""Train the causal 1D-CNN backbone at several sizes on the same synthetic parents as the GRU.

    python -m mapa_rhythm.train_cnn --out ../models/cnn

For each size it writes the PyTorch weights, the text weights the Kotlin engine loads
(`KotlinCnnBackbone`), golden vectors for the parity test, and one metrics file next to the
GRU's so the sizes can be compared on the same validation parents.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

from .cnn import KERNEL, RECEPTIVE_FIELD, CausalCnn, CnnStep
from .dataset import activity, clean_days, population_prior
from .features import BINS_PER_DAY, HORIZON, N_FEATURES, featurize
from .synth import generate
from .train import (SILENCE_BIN, SILENCE_BINS, WINDOW, build_arrays, evaluate, masked_bce,
                    stretch_surprises, write_tensor)

SIZES = {"xs": 8, "s": 16, "m": 32, "l": 54}  # channels; about 1.9K, 5.4K, 18K, 48K parameters


def export_weights(net: CausalCnn, prior, silence_hist, prior_days, path: Path):
    """Conv weights are written as [out, tap * in] with tap 0 the oldest input."""
    with open(path, "w") as f:
        f.write("mapa-rhythm 3\n")
        f.write(f"# cnn channels={net.channels} inputs={N_FEATURES} horizon={HORIZON} "
                f"params={net.n_params()} state={net.state_size()} receptive_field={RECEPTIVE_FIELD}\n")
        write_tensor(f, "cnn.dilations", [list(net.dilations)])
        for i, conv in enumerate(net.convs):
            w = conv.weight.detach().permute(0, 2, 1).reshape(conv.out_channels, -1).numpy()
            write_tensor(f, f"cnn.conv{i}.weight", w)
            write_tensor(f, f"cnn.conv{i}.bias", conv.bias.detach().numpy())
        write_tensor(f, "head.weight", net.head.weight.detach().numpy())
        write_tensor(f, "head.bias", net.head.bias.detach().numpy())
        write_tensor(f, "prior", prior)
        write_tensor(f, "silence_prior", silence_hist)
        write_tensor(f, "silence_prior_days", [prior_days])


def export_golden(net: CausalCnn, sim, prior, path: Path, hist_days=20, ticks=3 * BINS_PER_DAY):
    """Same format as the GRU's golden file: fixed profile from `hist_days`, then `ticks` steps."""
    from .features import Profile, day_active
    hist = [(sim.dow(d), day_active(sim.interactions, d, sim.moved)) for d in range(hist_days)]
    prof = Profile.build(hist, prior)
    t0 = hist_days * BINS_PER_DAY
    x = featurize(sim.interactions, sim.charging, sim.battery, sim.start_dow, lambda d: prof, t0, t0 + ticks,
                  moved=sim.moved)
    step = CnnStep(net).eval()
    h = torch.zeros(1, step.state_size)
    zs = []
    with torch.no_grad():
        for i in range(ticks):
            z, h = step(torch.from_numpy(x[i:i + 1]), h)
            zs.append(z[0].numpy())
    with open(path, "w") as f:
        f.write("golden 2\n")
        f.write(f"dow_offset {sim.start_dow}\n")
        f.write(f"history {hist_days}\n")
        for d in range(hist_days):
            c = sim.interactions[d * BINS_PER_DAY:(d + 1) * BINS_PER_DAY]
            mv = sim.moved[d * BINS_PER_DAY:(d + 1) * BINS_PER_DAY]
            f.write(f"day {d} {sim.dow(d)} " + " ".join(str(int(v)) for v in c) + "\n")
            f.write("moved " + " ".join(str(int(v)) for v in mv) + "\n")
        for dt in range(3):
            f.write("rate " + " ".join(repr(float(v)) for v in prof.rate[dt]) + "\n")
        f.write(f"ticks {ticks}\n")
        for i in range(ticks):
            t = t0 + i
            f.write(f"tick {t} {int(sim.interactions[t])} {int(sim.moved[t])} {int(sim.charging[t])} {int(sim.battery[t])} "
                    + " ".join(repr(float(v)) for v in x[i]) + " "
                    + " ".join(repr(float(v)) for v in zs[i]) + "\n")
    return x, np.stack(zs)


def check_streaming(net: CausalCnn, x: np.ndarray, zs: np.ndarray):
    """The streaming step must equal the padded full-sequence forward after the warm-up."""
    net.eval()
    with torch.no_grad():
        full, _ = net(torch.from_numpy(x)[None])
    err = float(np.abs(full[0].numpy() - zs).max())
    return err


def train_size(name, channels, a, data, rng, gru_metrics):
    train, val, prior, Xtr, Ytr, Mtr, Xva, Yva, Mva, Rva = data
    torch.manual_seed(a.seed)
    net = CausalCnn(channels)
    print(f"[{name}] channels={channels} params={net.n_params()} state={net.state_size()}", flush=True)
    opt = torch.optim.Adam(net.parameters(), lr=a.lr)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, a.epochs)
    wk = torch.ones(HORIZON)
    wk[0] = 2.0
    n_ticks = Xtr.shape[1]
    history = []
    t0 = time.time()
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
            mb[:, :RECEPTIVE_FIELD + 1] = 0  # the first receptive field sees padding, not history
            logits, _ = net(xb)
            loss = masked_bce(logits, yb, mb, wk)
            opt.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(net.parameters(), 1.0)
            opt.step()
            tot += loss.item()
        sched.step()
        if ep % 5 == 4 or ep == a.epochs - 1:
            vm, _ = evaluate(net, Xva, Yva, Mva, Rva)
            history.append({"epoch": ep, "train_loss": tot / (a.windows_per_epoch // a.batch),
                            "val_logloss": vm["logloss_all"], "val_next_bin": vm["logloss_next_bin"]})
            print(f"[{name}] {history[-1]} ({time.time() - t0:.0f}s)", flush=True)

    metrics, logits = evaluate(net, Xva, Yva, Mva, Rva)
    metrics.update(params=net.n_params(), channels=channels, state=net.state_size(),
                   receptive_field=RECEPTIVE_FIELD, history=history, train_seconds=time.time() - t0)

    surprises, prior_days = [], 0
    for i, s in enumerate(val):
        h1 = 1 / (1 + np.exp(-logits[i, :, 0]))
        ok = clean_days(s)
        ok[:2] = False
        prior_days += int(ok.sum())
        surprises += stretch_surprises(activity(s), h1, ok)
    hist, _ = np.histogram(np.clip(surprises, 0, SILENCE_BIN * SILENCE_BINS - 1e-6),
                           bins=SILENCE_BINS, range=(0, SILENCE_BIN * SILENCE_BINS))
    metrics["silence_stretches"] = len(surprises)
    metrics["silence_prior_days"] = prior_days

    out = a.out
    torch.save({"state_dict": net.state_dict(), "channels": channels}, out / f"rhythm_cnn_{name}.pt")
    export_weights(net, prior, hist, prior_days, out / f"rhythm_cnn_{name}.weights.txt")
    x, zs = export_golden(net, next(s for s in val if s.persona.has_motion), prior, out / f"golden_cnn_{name}.txt")
    metrics["streaming_max_abs_error"] = check_streaming(net, x, zs)
    print(f"[{name}] streaming vs full-sequence max error {metrics['streaming_max_abs_error']:.2e}", flush=True)
    return metrics


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("../models/cnn"))
    ap.add_argument("--gru-metrics", type=Path, default=Path("../models/train_metrics.json"))
    ap.add_argument("--sizes", default="xs,s,m,l")
    ap.add_argument("--train-parents", type=int, default=500)
    ap.add_argument("--val-parents", type=int, default=100)
    ap.add_argument("--days", type=int, default=84)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--windows-per-epoch", type=int, default=4096)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(4)

    t = time.time()
    train = generate(a.train_parents, a.days, 11, "p")  # same seeds as train.py: same parents
    val = generate(a.val_parents, a.days, 12, "v")
    prior = population_prior(train)
    Xtr, Ytr, Mtr, _ = build_arrays(train, prior)
    Xva, Yva, Mva, Rva = build_arrays(val, prior)
    print(f"data ready in {time.time() - t:.0f}s: train {Xtr.shape} val {Xva.shape}", flush=True)
    data = (train, val, prior, Xtr, Ytr, Mtr, Xva, Yva, Mva, Rva)

    gru = json.loads(a.gru_metrics.read_text()) if a.gru_metrics.exists() else None
    summary = {"gru": None if gru is None else {k: gru[k] for k in (
        "params", "logloss_all", "logloss_profile_all", "logloss_next_bin", "logloss_profile_next_bin",
        "logloss_4h_ahead", "logloss_profile_4h_ahead")}, "cnn": {}}
    for name in a.sizes.split(","):
        rng = np.random.default_rng(a.seed)
        m = train_size(name, SIZES[name], a, data, rng, gru)
        with open(a.out / f"train_metrics_cnn_{name}.json", "w") as f:
            json.dump(m, f, indent=1)
        summary["cnn"][name] = {k: m[k] for k in (
            "params", "state", "logloss_all", "logloss_profile_all", "logloss_next_bin",
            "logloss_profile_next_bin", "logloss_4h_ahead", "logloss_profile_4h_ahead",
            "streaming_max_abs_error", "train_seconds")}
        with open(a.out / "cnn_summary.json", "w") as f:
            json.dump(summary, f, indent=1)
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
