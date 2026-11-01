import unittest
import numpy as np

from mapa_train.cold_start import CONTEXT, fit_priors, apply_priors, sample_history_prefix
from mapa_train.quiet_cache import precompute_quiet_quantiles
from mapa_rhythm.features import _quiet_runs


class ColdStartTest(unittest.TestCase):
    def test_training_uses_prefix_and_masks_every_later_day(self):
        histories = np.broadcast_to(np.arange(30)[None, :, None], (64, 30, 105)).copy()
        class EveryLength:
            def integers(self, low, high, size):
                return np.arange(size) % 31
        sampled, counts = sample_history_prefix(histories, np.full(64, 30), EveryLength())
        self.assertIn(0, counts)
        self.assertIn(30, counts)
        for i, n in enumerate(counts):
            np.testing.assert_array_equal(sampled[i, :n], histories[i, :n])
            np.testing.assert_array_equal(sampled[i, n:], 0)

    def fixture(self):
        rng = np.random.default_rng(71)
        return rng.random((12, 96, 21)).astype('float32'), rng.random((12, 9)), [
            {'pid': f'p{i}', 'source': 'derived'} for i in range(12)]

    def test_rows_outside_fit_do_not_affect_priors(self):
        x, summaries, groups = self.fixture()
        rows = np.arange(10)
        first = fit_priors(x, summaries, groups, rows, 7)
        x[10:] = 1000; summaries[10:] = -1000
        second = fit_priors(x, summaries, groups, rows, 7)
        self.assertEqual(first['fit_pids'], [f'p{i}' for i in range(10)])
        for key in ['population_prior', 'archetype_priors', 'centroids']:
            np.testing.assert_array_equal(first[key], second[key])

    def test_unused_future_history_is_inert_and_phone_signals_are_preserved(self):
        x, summaries, groups = self.fixture()
        prior = fit_priors(x, summaries, groups, np.arange(10), 7)
        histories = np.zeros((2, 30, 105)); counts = np.array([0, 3])
        first = apply_priors(x[:2], histories, counts, prior)
        histories[0] = 999; histories[1, 3:] = -999
        changed_x = x[:2].copy()
        changed_x[:, :, CONTEXT] = 999
        second = apply_priors(changed_x, histories, counts, prior)
        np.testing.assert_array_equal(first, second)
        preserved = [j for j in range(21) if j not in CONTEXT + [15]]
        np.testing.assert_array_equal(first[..., preserved], x[:2][..., preserved])
        np.testing.assert_allclose(first[0][:, CONTEXT], np.broadcast_to(prior['population_prior'], (96, 5)))

    def test_cached_quiet_quantiles_equal_original_history_scan(self):
        rng = np.random.default_rng(2031)
        histories = np.zeros((4, 30, 105), dtype=np.float32)
        histories[:, :, 9:] = rng.choice(np.array([-1., 0., 1.]), (4, 30, 96),
                                         p=[.12, .72, .16])
        counts = np.array([0, 3, 17, 30])
        cached = precompute_quiet_quantiles(histories, counts)
        self.assertEqual(cached.shape, (4, 31, 2))
        for i, available in enumerate(counts):
            for n in (0, min(1, available), int(available)):
                spells = [r for day in histories[i, :n, 9:] for r in _quiet_runs(day)]
                expected = (np.quantile(spells, [.90, .995]) if len(spells) >= 5
                            else np.array([16., 32.]))
                np.testing.assert_array_equal(cached[i, n], expected)
        x, summaries, groups = self.fixture()
        prior = fit_priors(x, summaries, groups, np.arange(10), 7)
        original = apply_priors(x[:4], histories, counts, prior)
        reused = apply_priors(x[:4], histories, counts, prior, quiet_quantiles=cached)
        np.testing.assert_array_equal(reused, original)


if __name__ == '__main__':
    unittest.main()
