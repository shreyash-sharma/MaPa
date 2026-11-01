import unittest
import numpy as np
from mapa_train.decision import personal_history, stats_limits


class CoverageHistoryTest(unittest.TestCase):
    def test_partial_days_cannot_update_limits_or_morning_history(self):
        def day(index, full):
            avail = np.ones(96, bool) if full else np.arange(96) < 40
            return dict(pid='p', day=index, eid='', avail=avail, counts=np.zeros(96),
                        silence=np.full(96, 80), score_behavior=np.ones(96), score_surprise=np.ones(96))
        views = [day(0, False), day(1, True), day(2, True)]
        hist = personal_history(views)
        self.assertEqual(len(hist[('p', 1)][0]), 0)
        self.assertGreater(len(hist[('p', 2)][0]), 0)
        stats_limits(views)
        self.assertEqual(views[1]['stats_limit'], 16.)
        self.assertGreater(views[2]['stats_limit'], 16.)
