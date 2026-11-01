"""One 15-minute hybrid step with explicit, bounded phone state."""
import torch
from torch import nn


class DailyEncoder(nn.Module):
    """Run once when prior-day summaries change; cache its vector for fast steps."""
    def __init__(self, model):
        super().__init__()
        if model.slow is None:
            raise ValueError("This ablation has no daily encoder")
        self.lstm = model.slow

    def forward(self, daily_summaries, count):
        states, _ = self.lstm(daily_summaries)
        index = (count.clamp(min=1, max=30) - 1)[:, None, None]
        vector = states.gather(1, index.expand(-1, 1, states.shape[-1])).squeeze(1)
        return torch.where((count > 0)[:, None], vector, torch.zeros_like(vector))


class HybridStep(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
        self.conv_state_size = model.cnn.state_size()
        self.next_use_blend = float(getattr(model, "next_use_blend", 0.0))

    def forward(self, x, conv_state, gru_state, attention_memory, memory_count, slow_vector):
        net = self.model
        current = x
        buffers = []
        offset = 0
        for i, (conv, dilation) in enumerate(zip(net.cnn.convs, net.cnn.dilations)):
            width = conv.in_channels
            size = 2 * dilation * width
            buf = conv_state[:, offset:offset + size].reshape(1, 2 * dilation, width)
            offset += size
            w = conv.weight
            z = (buf[:, 0] @ w[:, :, 0].t() + buf[:, dilation] @ w[:, :, 1].t()
                 + current @ w[:, :, 2].t() + conv.bias).relu()
            buffers.append(torch.cat([buf[:, 1:], current[:, None]], dim=1).reshape(1, size))
            current = z if i == 0 else current + z
        local = torch.cat([current, x], dim=-1)
        fast, next_gru = net.gru(local[:, None], gru_state)
        memory = torch.cat([attention_memory[:, 1:], fast], dim=1)
        count = (memory_count + 1).clamp(max=96)
        if net.attention is not None:
            invalid = torch.arange(96, device=x.device)[None] < (96 - count[:, None])
            attended, _ = net.attention(fast, memory, memory, key_padding_mask=invalid, need_weights=False)
            fast = fast + attended
        parts = [fast[:, 0], x]
        if net.personal is not None:
            parts.append(net.personal(torch.cat([x[:, 11:16], x[:, 18:20]], dim=-1)))
        if net.slow is not None:
            parts.append(slow_vector)
        context = torch.cat(parts, dim=-1)
        logits = torch.cat([net.hazard_head(context), net.use_now_head(context),
                            net.behaviour_head(context), net.confidence_head(context)], dim=-1)
        if self.next_use_blend > 0:
            model_p = torch.sigmoid(logits[:, :1])
            profile_p = x[:, 11:12].clamp(1e-6, 1 - 1e-6)
            mixed = (self.next_use_blend * model_p +
                     (1 - self.next_use_blend) * profile_p).clamp(1e-6, 1 - 1e-6)
            logits = torch.cat([torch.logit(mixed), logits[:, 1:]], dim=-1)
        return logits, torch.cat(buffers, dim=1), next_gru, memory, count
