import unittest
from unittest.mock import patch
from mapa_train.decision import cold_start_curve


class ColdStartCurveTest(unittest.TestCase):
    def test_boundaries_use_person_origin_and_full_histories(self):
        days = [0, 1, 2, 3, 4, 7, 8, 14, 15]
        views = [{'pid': 'p', 'eid': '', 'day': 100 + d} for d in days]
        hist = object()
        calls = []
        def scorer(selected, received_hist, cfg):
            self.assertIs(hist, received_hist)
            calls.append(cfg)
            return [v['days_since_install'] for v in selected]
        with patch('mapa_train.decision.score_config', scorer):
            curve = cold_start_curve(views, hist, {'kind': 'global'})
        expected = [[0, 1], [2, 3], [4, 7], [8, 14], [15]]
        self.assertEqual([v['metrics'] for v in curve['strata'].values()], expected)
        self.assertEqual(sum(bool(c.get('hard_switch')) for c in calls), 5)
