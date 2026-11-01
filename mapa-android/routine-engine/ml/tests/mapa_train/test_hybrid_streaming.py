import unittest
import torch
from mapa_train.zoo import Hybrid
from mapa_train.hybrid_streaming import HybridStep, DailyEncoder


class HybridStreamingTest(unittest.TestCase):
    def test_stream_matches_causal_day_with_and_without_attention(self):
        torch.set_num_threads(1)
        for attention in [False, True]:
            torch.manual_seed(98)
            net = Hybrid(attention=attention).eval()
            step = HybridStep(net)
            x = torch.rand(1, 96, 21)
            history = torch.rand(1, 30, 105)
            with torch.no_grad():
                expected = net(x, history, torch.tensor([3]))
                slow = DailyEncoder(net)(history[..., :9], torch.tensor([3]))
                state = torch.zeros(1, step.conv_state_size)
                gru = torch.zeros(1, 1, 16)
                memory = torch.zeros(1, 96, 16)
                count = torch.zeros(1, dtype=torch.int64)
                outputs = []
                for t in range(96):
                    z, state, gru, memory, count = step(x[:, t], state, gru, memory, count, slow)
                    outputs.append(z)
            torch.testing.assert_close(torch.stack(outputs, dim=1), expected, atol=1e-6, rtol=0)
