import unittest
import torch
from mapa_train.zoo import Hybrid


class HybridContractTest(unittest.TestCase):
    def test_fixed_daily_window_matches_packed_lstm(self):
        torch.set_num_threads(1)
        torch.manual_seed(92)
        model = Hybrid(attention=False).eval()
        history = torch.rand(4, 30, 9)
        counts = torch.tensor([1, 3, 17, 30])
        with torch.no_grad():
            full, _ = model.slow(history)
            selected = full[torch.arange(4), counts - 1]
            packed = torch.nn.utils.rnn.pack_padded_sequence(history, counts, batch_first=True, enforce_sorted=False)
            _, (expected, _) = model.slow(packed)
        torch.testing.assert_close(selected, expected[-1], atol=1e-6, rtol=0)

    def test_future_bins_and_masked_daily_rows_cannot_change_past_outputs(self):
        torch.set_num_threads(1)
        torch.manual_seed(83)
        model = Hybrid().eval()
        x = torch.rand(2, 96, 21)
        history = torch.rand(2, 30, 105)
        counts = torch.tensor([0, 3])
        with torch.no_grad():
            expected = model(x, history, counts)
            x[:, 48:] = torch.rand_like(x[:, 48:]) * 100
            history[0] = 999
            history[1, 3:] = -999
            changed = model(x, history, counts)
        self.assertEqual(tuple(expected.shape), (2, 96, 19))
        self.assertLessEqual(sum(p.numel() for p in model.parameters()), 50000)
        self.assertTrue(torch.isfinite(expected).all())
        torch.testing.assert_close(expected[:, :48], changed[:, :48], atol=1e-6, rtol=0)
