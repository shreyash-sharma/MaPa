import unittest

import numpy as np

from mapa_train.decision import CausalHistory, detect


class DecisionQuantileCacheTests(unittest.TestCase):
    def test_cached_and_uncached_decisions_are_bit_identical(self):
        rng = np.random.default_rng(418)
        cached = CausalHistory()
        for day in range(16):
            quiet = rng.integers(0, 64, size=120).astype(np.float64)
            firsts = [int(x) for x in rng.integers(25, 72, size=8)]
            cached[("p1", day)] = (quiet, firsts)

        views = []
        for day in range(1, 16):
            counts = np.zeros(96, dtype=np.int64)
            counts[(day * 7) % 96] = 1
            silence = np.arange(96, dtype=np.float64) % 34
            views.append({
                "pid": "p1", "day": day, "days_since_install": day + 20,
                "avail": np.ones(96, dtype=bool),
                "alert_eligible": np.ones(96, dtype=bool),
                "counts": counts, "silence": silence,
                "score_behavior": rng.random(96), "score_surprise": rng.random(96),
                "confidence": rng.random(96),
            })

        settings = [
            {"kind": "personal", "q": q, "floor": floor,
             "silence_floor": 16, "morning": morning}
            for q in (.90, .97, .995) for floor in (.2, .4, .7)
            for morning in (False, True)
        ]
        settings += [
            {"cold_start": True, "q": q, "floor": floor,
             "model_weight": weight, "uncertainty_margin": .2,
             "morning": morning}
            for q in (.90, .97, .995) for floor in (.2, .4)
            for weight in (0., .5, 1.) for morning in (False, True)
        ]
        plain = dict(cached)
        for view in views:
            for cfg in settings:
                expected = detect(view, plain, cfg)
                actual = detect(view, cached, cfg)
                np.testing.assert_array_equal(actual, expected)

        for key, (quiet, firsts) in cached.items():
            for q in (.90, .97, .995):
                self.assertEqual(cached.quantile(key, q), float(np.quantile(quiet, q)))
            self.assertEqual(cached.quantile(key, .9, first_use=True),
                             float(np.quantile(firsts, .9)))
        self.assertEqual(len(cached._quantiles), len(cached) * 4)


if __name__ == "__main__":
    unittest.main()
