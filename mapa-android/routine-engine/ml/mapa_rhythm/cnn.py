"""Causal dilated 1D-CNN backbone: same inputs and outputs as the GRU in `train.RhythmNet`.

Training runs the whole sequence with left padding. On the phone the same network runs one
tick at a time (`CnnStep`) and keeps the last 2*d inputs of every layer in one flat state
vector, so `RhythmBackbone.step(x, h)` works unchanged. A zero state equals zero left padding,
so training and streaming give the same numbers (checked in `train_cnn.py`).
"""
from __future__ import annotations

import torch
from torch import nn
from torch.nn import functional as F

from .features import HORIZON, N_FEATURES

KERNEL = 3
DILATIONS = (1, 2, 4, 8, 16, 32)
RECEPTIVE_FIELD = 1 + (KERNEL - 1) * sum(DILATIONS)  # bins


class CausalCnn(nn.Module):
    """Six causal convolutions (kernel 3, dilations 1..32), ReLU, residual from layer 2 on,
    then a linear head over [last features, x] that emits the 16 hazard logits."""

    def __init__(self, channels: int, dilations=DILATIONS, input_features: int = N_FEATURES):
        super().__init__()
        self.channels = channels
        self.dilations = tuple(dilations)
        self.input_features = input_features
        self.convs = nn.ModuleList()
        cin = input_features
        for d in self.dilations:
            self.convs.append(nn.Conv1d(cin, channels, KERNEL, dilation=d))
            cin = channels
        self.head = nn.Linear(channels + input_features, HORIZON)

    def features(self, x):
        z = x.transpose(1, 2)
        for i, (conv, d) in enumerate(zip(self.convs, self.dilations)):
            y = torch.relu(conv(F.pad(z, ((KERNEL - 1) * d, 0))))
            z = y if i == 0 else z + y
        z = z.transpose(1, 2)
        return torch.cat([z, x], dim=-1)

    def forward(self, x, h0=None):
        return self.head(self.features(x)), None

    def n_params(self):
        return sum(p.numel() for p in self.parameters())

    def state_size(self):
        size, cin = 0, self.input_features
        for d in self.dilations:
            size += (KERNEL - 1) * d * cin
            cin = self.channels
        return size


class CnnStep(nn.Module):
    """One tick for on-device export: (x[1,input_features], state) -> logits and next state."""

    def __init__(self, net: CausalCnn):
        super().__init__()
        self.net = net
        self.state_size = net.state_size()

    def forward(self, x, h):
        cur = x
        news = []
        off = 0
        for i, (conv, d) in enumerate(zip(self.net.convs, self.net.dilations)):
            cin = conv.in_channels
            n = (KERNEL - 1) * d * cin
            buf = h[:, off:off + n].reshape(1, (KERNEL - 1) * d, cin)
            off += n
            w = conv.weight  # [out, in, 3]; tap 0 sees the oldest input
            y = buf[:, 0] @ w[:, :, 0].t() + buf[:, d] @ w[:, :, 1].t() + cur @ w[:, :, 2].t() + conv.bias
            y = torch.relu(y)
            news.append(torch.cat([buf[:, 1:], cur.unsqueeze(1)], dim=1).reshape(1, n))
            cur = y if i == 0 else cur + y
        logits = self.net.head(torch.cat([cur, x], dim=-1))
        return logits, torch.cat(news, dim=1)
