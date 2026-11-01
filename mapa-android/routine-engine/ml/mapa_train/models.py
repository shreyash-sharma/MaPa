"""The approved MaPa Rhythm Hybrid and compact comparison architectures."""
import torch
from torch import nn
from mapa_rhythm.cnn import CausalCnn
from mapa_rhythm.features import TRAIN_FEATURES

class Hybrid(nn.Module):
    """Local causal CNN, GRU memory, causal attention, daily LSTM and personal context."""
    def __init__(self, channels=16, hidden=16, attention=True, personal=True, slow=True):
        super().__init__()
        self.cnn = CausalCnn(channels, dilations=(1, 2, 4, 8), input_features=TRAIN_FEATURES)
        self.gru = nn.GRU(channels + TRAIN_FEATURES, hidden, batch_first=True)
        self.attention = nn.MultiheadAttention(hidden, 4, batch_first=True) if attention else None
        self.register_buffer("causal", torch.triu(torch.full((96, 96), float("-inf")), 1))
        self.slow = nn.LSTM(9, hidden, batch_first=True) if slow else None
        self.personal = nn.Sequential(nn.Linear(7, 8), nn.Tanh()) if personal else None
        width = hidden + TRAIN_FEATURES + (8 if personal else 0) + (hidden if slow else 0)
        self.hazard_head = nn.Linear(width, 16)
        self.use_now_head = nn.Linear(width, 1)
        self.behaviour_head = nn.Linear(width, 1)
        self.confidence_head = nn.Linear(width, 1)
        self.reconstruction_head = self.use_now_head
        self.attention_enabled = attention

    def forward(self, x, slow_history, slow_count):
        fast, _ = self.gru(self.cnn.features(x))
        if self.attention is not None:
            attn, _ = self.attention(fast, fast, fast, attn_mask=self.causal[:x.shape[1], :x.shape[1]])
            fast = fast + attn
        parts = [fast, x]
        if self.personal is not None:
            parts.append(self.personal(torch.cat([x[..., 11:16], x[..., 18:20]], dim=-1)))
        if self.slow is not None:
            daily_states, _ = self.slow(slow_history[..., :9])
            index = (slow_count.clamp(min=1, max=30) - 1)[:, None, None]
            slow = daily_states.gather(1, index.expand(-1, 1, daily_states.shape[-1])).squeeze(1)
            slow = torch.where((slow_count > 0)[:, None], slow, torch.zeros_like(slow))
            parts.append(slow[:, None, :].expand(-1, x.shape[1], -1))
        z = torch.cat(parts, dim=-1)
        return torch.cat([self.hazard_head(z), self.use_now_head(z), self.behaviour_head(z),
                          self.confidence_head(z)], dim=-1)


class Rnn(nn.Module):
    """Plain GRU alternative, with hazard and use-now outputs."""
    def __init__(self, hidden=32):
        super().__init__()
        self.rnn = nn.GRU(TRAIN_FEATURES, hidden, batch_first=True)
        width = hidden + TRAIN_FEATURES
        self.hazard_head = nn.Linear(width, 16)
        self.use_now_head = nn.Linear(width, 1)
        self.reconstruction_head = self.use_now_head

    def forward(self, x):
        h, _ = self.rnn(x)
        h = torch.cat([h, x], -1)
        return torch.cat([self.hazard_head(h), self.use_now_head(h)], dim=-1)


class PlainCnn(nn.Module):
    """Causal CNN alternative without GRU, attention, slow lane or behaviour head."""
    def __init__(self, channels=16):
        super().__init__()
        self.net = CausalCnn(channels, dilations=(1, 2, 4, 8), input_features=TRAIN_FEATURES)
        self.use_now = nn.Linear(channels + TRAIN_FEATURES, 1)
        self.net.use_now = self.use_now

    def forward(self, x):
        h = self.net.features(x)
        return torch.cat([self.net.head(h), self.use_now(h)], dim=-1)


class RebuildLSTM(nn.Module):
    """Our labeled rebuild of a published-style 15-minute LSTM baseline."""
    def __init__(self, hidden=32):
        super().__init__()
        self.rnn = nn.LSTM(TRAIN_FEATURES, hidden, batch_first=True)
        width = hidden + TRAIN_FEATURES
        self.hazard = nn.Linear(width, 16)
        self.use_now = nn.Linear(width, 1)
        self.behaviour = nn.Linear(width, 1)
        self.confidence = nn.Linear(width, 1)

    def forward(self, x):
        h, _ = self.rnn(x)
        h = torch.cat([h, x], dim=-1)
        return torch.cat([self.hazard(h), self.use_now(h), self.behaviour(h), self.confidence(h)], dim=-1)


class HybridNoAttention(Hybrid):
    """Required backbone ablation: the same two-lane model without attention."""
    def __init__(self):
        super().__init__(attention=False)


