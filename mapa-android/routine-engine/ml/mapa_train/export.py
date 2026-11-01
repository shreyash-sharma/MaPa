"""Write the text weights the phone loads (`mapa-rhythm 3`), same format as mapa_rhythm.train_cnn.

Kept here because mapa_rhythm.train_cnn imports helpers this copy of mapa_rhythm.dataset does not have.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import torch

from mapa_rhythm.cnn import RECEPTIVE_FIELD, CausalCnn
from mapa_rhythm.features import HORIZON
from mapa_rhythm.train import write_tensor

SIZES = {"xs": 8, "s": 16, "m": 32, "l": 54}  # channels; about 1.9K, 5.4K, 18K, 48K parameters


def export_weights(net: CausalCnn, prior, silence_hist, prior_days, path: Path):
    """Conv weights are written as [out, tap * in] with tap 0 the oldest input."""
    with open(path, "w") as f:
        f.write("mapa-rhythm 3\n")
        f.write(f"# cnn channels={net.channels} inputs={net.input_features} horizon={HORIZON} "
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


def check_streaming(net: CausalCnn, x: np.ndarray, zs: np.ndarray) -> float:
    """The streaming step must equal the padded full-sequence forward after the warm-up."""
    net.eval()
    with torch.no_grad():
        full, _ = net(torch.from_numpy(x)[None])
    return float(np.abs(full[0].numpy() - zs).max())
