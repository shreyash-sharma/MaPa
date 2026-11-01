"""Read the text weights the phone loads (`mapa-rhythm 3`) back into a PyTorch CausalCnn."""
from __future__ import annotations

from pathlib import Path

import numpy as np
import torch

from mapa_rhythm.cnn import CausalCnn


def read_tensors(path: Path) -> dict[str, np.ndarray]:
    out, lines = {}, Path(path).read_text().splitlines()
    i = 0
    while i < len(lines):
        parts = lines[i].split()
        if parts and parts[0] == "tensor":
            name, rows, cols = parts[1], int(parts[2]), int(parts[3])
            out[name] = np.array([[float(v) for v in lines[i + 1 + r].split()] for r in range(rows)], dtype=np.float32).reshape(rows, cols)
            i += 1 + rows
        else:
            i += 1
    return out


def load_cnn(path: Path) -> tuple[CausalCnn, dict[str, np.ndarray]]:
    """The CNN and the raw tensors (prior, silence_prior, silence_prior_days) from a weights file."""
    t = read_tensors(path)
    input_features = int(t["cnn.conv0.weight"].shape[1] // 3)
    channels = int(t["cnn.conv0.weight"].shape[0])
    net = CausalCnn(channels, input_features=input_features)
    with torch.no_grad():
        for i, conv in enumerate(net.convs):
            w = t[f"cnn.conv{i}.weight"]                      # [out, tap * in], tap 0 the oldest
            k = conv.kernel_size[0]
            conv.weight.copy_(torch.from_numpy(w.reshape(conv.out_channels, k, conv.in_channels)).permute(0, 2, 1))
            conv.bias.copy_(torch.from_numpy(t[f"cnn.conv{i}.bias"].reshape(-1)))
        net.head.weight.copy_(torch.from_numpy(t["head.weight"]))
        net.head.bias.copy_(torch.from_numpy(t["head.bias"].reshape(-1)))
    want = [int(d) for d in t["cnn.dilations"].reshape(-1)]
    if list(net.dilations) != want:
        raise ValueError(f"dilations {want} differ from CausalCnn {list(net.dilations)}")
    return net.eval(), t

