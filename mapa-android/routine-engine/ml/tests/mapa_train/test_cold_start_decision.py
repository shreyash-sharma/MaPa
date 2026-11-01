import unittest
import numpy as np
from mapa_train.decision import cold_start_levels


class ColdStartDecisionTest(unittest.TestCase):
    def test_stale_guard_and_eight_hour_floor_survive_all_blends(self):
        v = {'confidence': np.zeros(96), 'score_behavior': np.zeros(96),
             'score_surprise': np.zeros(96), 'silence': np.arange(96),
             'alert_eligible': np.ones(96, bool), 'avail': np.ones(96, bool)}
        for w in [0., .25, .5, .75, 1.]:
            result = cold_start_levels(v, {'model_weight': w}, .99)
            self.assertTrue(result['please_call'][32:].all())
            self.assertFalse((result['please_call'] & result['worth_a_look']).any())
            v['avail'][:] = False
            stale = cold_start_levels(v, {'model_weight': w}, .99)
            self.assertFalse(stale['please_call'].any())
            self.assertFalse(stale['worth_a_look'].any())
            v['avail'][:] = True

    def test_low_confidence_requires_a_higher_score(self):
        v = {'confidence': np.ones(96), 'score_behavior': np.full(96, .6),
             'score_surprise': np.full(96, .6), 'silence': np.zeros(96),
             'alert_eligible': np.ones(96, bool), 'avail': np.ones(96, bool)}
        cfg = {'model_weight': 1., 'uncertainty_margin': .2}
        self.assertTrue(cold_start_levels(v, cfg, .5)['please_call'].all())
        v['confidence'][:] = 0
        self.assertFalse(cold_start_levels(v, cfg, .5)['please_call'].any())
