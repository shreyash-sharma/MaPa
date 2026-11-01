"""Export the one-tick rhythm step to LiteRT (float and int8) and measure the quantisation cost.

    python -m mapa_rhythm.export --models ../models
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

from .dataset import parent_arrays, population_prior
from .features import BINS_PER_DAY, HORIZON, N_FEATURES
from .synth import generate
from .train import RhythmNet, RhythmStep


def load_net(models: Path) -> RhythmNet:
    ck = torch.load(models / "rhythm_v1.pt", map_location="cpu")
    net = RhythmNet(ck["hidden"])
    net.load_state_dict(ck["state_dict"])
    return net.eval()


class TfliteStep:
    def __init__(self, path: Path):
        from ai_edge_litert.interpreter import Interpreter
        # The full-int8 file needs preserved tensors in this runtime (its output aliases an
        # intermediate); the shipped dynamic-int8 file does not.
        self.interp = Interpreter(model_path=str(path), experimental_preserve_all_tensors="static" in path.name)
        sigs = self.interp.get_signature_list()
        self.key = next(iter(sigs))
        self.runner = self.interp.get_signature_runner(self.key)
        self.inputs = sigs[self.key]["inputs"]
        self.outputs = sigs[self.key]["outputs"]
        self.in_det = self.runner.get_input_details()
        self.out_det = self.runner.get_output_details()

    def _q(self, name, v):
        d = self.in_det[name]
        if d["dtype"] == np.float32:
            return v.astype(np.float32)
        scale, zp = d["quantization"]
        info = np.iinfo(d["dtype"])
        return np.clip(np.round(v / scale + zp), info.min, info.max).astype(d["dtype"])

    def _dq(self, name, v):
        d = self.out_det[name]
        if d["dtype"] == np.float32:
            return v
        scale, zp = d["quantization"]
        return ((v.astype(np.float32) - zp) * scale).astype(np.float32)

    def __call__(self, x: np.ndarray, h: np.ndarray):
        out = self.runner(**{self.inputs[0]: self._q(self.inputs[0], x), self.inputs[1]: self._q(self.inputs[1], h)})
        a, b = (self._dq(k, out[k]) for k in self.outputs)
        # the logits have HORIZON columns; the hidden state has H
        return (a, b) if a.shape[-1] == HORIZON and b.shape[-1] != HORIZON else (b, a)


def stream(step, X: np.ndarray, hidden: int):
    h = np.zeros((1, hidden), dtype=np.float32)
    out = np.zeros((len(X), HORIZON), dtype=np.float32)
    for i in range(len(X)):
        z, h = step(X[i:i + 1], h)
        out[i] = z[0]
        h = np.asarray(h, dtype=np.float32)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", type=Path, default=Path("../models"))
    ap.add_argument("--eval-parents", type=int, default=6)
    ap.add_argument("--eval-days", type=int, default=28)
    a = ap.parse_args()
    import litert_torch
    from ai_edge_quantizer import quantizer, recipe

    net = load_net(a.models)
    step = RhythmStep(net).eval()
    x0 = torch.zeros(1, N_FEATURES)
    h0 = torch.zeros(1, net.hidden)
    f32 = a.models / "rhythm_step_f32.tflite"
    litert_torch.convert(step, (x0, h0)).export(str(f32))

    calib_x = np.load(a.models / "calibration_inputs.npy").astype(np.float32)
    # hidden states seen while streaming the calibration inputs, for static activation ranges
    with torch.no_grad():
        hs, h = [], h0
        for i in range(len(calib_x)):
            hs.append(h.numpy().copy())
            _, h = step(torch.from_numpy(calib_x[i:i + 1]), h)

    flt = TfliteStep(f32)
    calib = {flt.key: [{flt.inputs[0]: calib_x[i:i + 1], flt.inputs[1]: hs[i]} for i in range(0, len(calib_x), 2)]}

    variants = {}
    q = quantizer.Quantizer(str(f32))
    q.load_quantization_recipe(recipe.dynamic_wi8_afp32())
    p = a.models / "rhythm_step_int8.tflite"
    q.quantize().export_model(str(p), overwrite=True)
    variants["int8_weights_dynamic"] = p
    try:
        q = quantizer.Quantizer(str(f32))
        q.load_quantization_recipe(recipe.static_wi8_ai8())
        res = q.calibrate(calib)
        p = a.models / "rhythm_step_int8_static.tflite"
        q.quantize(res).export_model(str(p), overwrite=True)
        variants["int8_full_static"] = p
    except Exception as e:  # report, do not hide
        print("static int8 failed:", repr(e)[:300])

    # Evaluate on fresh synthetic parents against the PyTorch float model.
    train_prior = population_prior(generate(60, 42, 11, "p"))
    sims = generate(a.eval_parents, a.eval_days, 99, "e")
    report = {"float32": {"bytes": f32.stat().st_size}}
    ref_all, got = [], {k: [] for k in ["float32", *variants]}
    masks, ys = [], []
    for s in sims:
        X, Y, M, _ = parent_arrays(s, train_prior)
        with torch.no_grad():
            ref, _ = net(torch.from_numpy(X[None]))
        ref_all.append(ref[0].numpy())
        got["float32"].append(stream(flt, X, net.hidden))
        for k, path in variants.items():
            got[k].append(stream(TfliteStep(path), X, net.hidden))
        masks.append(M); ys.append(Y)
    ref = np.concatenate(ref_all)
    M = np.concatenate(masks); M[:BINS_PER_DAY] = False
    Y = np.concatenate(ys)

    def ll(z):
        p = np.clip(1 / (1 + np.exp(-z)), 1e-6, 1 - 1e-6)
        return float(-(Y * np.log(p) + (1 - Y) * np.log(1 - p))[M].mean())

    report["torch_logloss"] = ll(ref)
    for k, zs in got.items():
        z = np.concatenate(zs)
        pr, pz = 1 / (1 + np.exp(-ref[:, 0])), 1 / (1 + np.exp(-z[:, 0]))
        report.setdefault(k, {})
        report[k].update({
            "bytes": (f32 if k == "float32" else variants[k]).stat().st_size,
            "max_abs_logit_err": float(np.abs(z - ref).max()),
            "mean_abs_next_bin_prob_err": float(np.abs(pz - pr).mean()),
            "max_abs_next_bin_prob_err": float(np.abs(pz - pr).max()),
            "logloss": ll(z),
        })
    report["params"] = int(sum(p.numel() for p in net.parameters()))
    report["signature"] = {"key": flt.key, "inputs": {k: [list(map(int, v["shape"])), str(np.dtype(v["dtype"]))] for k, v in flt.in_det.items()},
                           "outputs": {k: [list(map(int, v["shape"])), str(np.dtype(v["dtype"]))] for k, v in flt.out_det.items()}}
    with open(a.models / "export_metrics.json", "w") as f:
        json.dump(report, f, indent=1)
    print(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
