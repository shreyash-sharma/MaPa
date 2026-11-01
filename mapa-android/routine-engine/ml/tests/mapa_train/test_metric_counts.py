import unittest
import numpy as np
from mapa_train.decision import score_config


class MetricCountsTest(unittest.TestCase):
    def test_observation_episodes_never_enter_behavior_counts(self):
        def view(eid, expected):
            return dict(pid='p', eid=eid, day=0, expect=expected, severity='severe',
                        score_behavior=np.ones(96), score_surprise=np.ones(96),
                        silence=np.full(96, 32), stats_limit=16,
                        avail=np.ones(96, bool) if expected != 'monitoring_unavailable' else np.zeros(96, bool),
                        alert_eligible=np.ones(96, bool) if expected != 'monitoring_unavailable' else np.zeros(96, bool),
                        tags=np.zeros(96, bool), win=np.ones(96, bool))
        result = score_config([view('', 'none'), view('b', 'unusual_inactivity'),
                               view('o', 'monitoring_unavailable')], {}, {'kind': 'global', 'floor': .5})
        self.assertEqual(result['model']['episode_counts']['severe'], 1)
        self.assertEqual(result['model']['caught_counts']['severe'], 1)
        self.assertEqual(result['observation_episodes_separate']['severe']['episodes'], 1)
        self.assertEqual(result['model']['catch_95ci']['severe'], [1., 1.])
        self.assertIsNone(result['model']['catch_95ci']['mild'])
        self.assertFalse(result['C4'])
