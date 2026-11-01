"""Export a trained CNN backbone to ExecuTorch (.pte) and check it against PyTorch.

    python -m mapa_rhythm.export_executorch --model ../models/cnn/rhythm_cnn_m.pt --out ../models/cnn

The program takes (x[1,18], h[1,S]) and returns (logits[1,16], h'[1,S]), the same contract as
`RhythmBackbone.step`. XNNPACK is used for the float model. Writes `<name>.pte` and `<name>.et_report.json`.
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch

from .cnn import CausalCnn, CnnStep
from .features import N_FEATURES


def export(net: CausalCnn, out: Path, xnnpack: bool = True) -> Path:
    from executorch.exir import to_edge_transform_and_lower
    step = CnnStep(net).eval()
    example = (torch.zeros(1, N_FEATURES), torch.zeros(1, step.state_size))
    prog = torch.export.export(step, example, strict=False)
    if xnnpack:
        from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
        edge = to_edge_transform_and_lower(prog, partitioner=[XnnpackPartitioner()])
    else:
        edge = to_edge_transform_and_lower(prog)
    et = edge.to_executorch()
    out.write_bytes(et.buffer)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=Path("../models/cnn"))
    ap.add_argument("--golden", type=Path, default=None, help="golden_cnn_*.txt to replay (default: next to the model)")
    ap.add_argument("--no-xnnpack", action="store_true")
    a = ap.parse_args()
    ck = torch.load(a.model, map_location="cpu")
    net = CausalCnn(ck["channels"])
    net.load_state_dict(ck["state_dict"])
    net.eval()
    name = a.model.stem
    pte = export(net, a.out / f"{name}.pte", xnnpack=not a.no_xnnpack)

    from executorch.runtime import Runtime
    prog = Runtime.get().load_program(str(pte))
    method = prog.load_method("forward")
    step = CnnStep(net).eval()
    h_py = torch.zeros(1, step.state_size)
    h_et = torch.zeros(1, step.state_size)
    golden = a.golden or a.model.with_name(a.model.stem.replace("rhythm_cnn_", "golden_cnn_") + ".txt")
    xs = []
    for line in golden.read_text().splitlines():
        p = line.split(" ")
        if p[0] == "tick":
            xs.append([float(v) for v in p[6:6 + N_FEATURES]])
    xs = torch.tensor(xs, dtype=torch.float32)
    max_z = max_h = 0.0
    t0 = time.time()
    with torch.no_grad():
        for i in range(len(xs)):
            z_py, h_py = step(xs[i:i + 1], h_py)
            z_et, h_et = method.execute([xs[i:i + 1], h_et])
            max_z = max(max_z, float((z_py - z_et).abs().max()))
            max_h = max(max_h, float((h_py - h_et).abs().max()))
    report = {
        "model": name, "params": net.n_params(), "state_floats": step.state_size, "pte_bytes": pte.stat().st_size,
        "ticks_checked": len(xs), "max_abs_logit_error_vs_pytorch": max_z, "max_abs_state_error_vs_pytorch": max_h,
        "desktop_ms_per_step_python_runtime": (time.time() - t0) / len(xs) * 1000 / 2,
        "backend": "cpu-portable" if a.no_xnnpack else "xnnpack",
    }
    (a.out / f"{name}.et_report.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
