import unittest
from mapa_train.ablations import configuration, decision_grid


class AblationScopeTest(unittest.TestCase):
    def test_later_components_cannot_enter_earlier_rows(self):
        for row in range(1, 11):
            cfg = configuration(row)
            self.assertEqual(bool(cfg.get('slow')), row >= 7)
            self.assertEqual(bool(cfg.get('attention')), row >= 8)
            self.assertEqual(bool(cfg.get('cold_start')), row >= 9)
            for decision in decision_grid(row):
                self.assertEqual(decision['kind'] == 'personal', row >= 5)
                self.assertEqual(decision['score_kind'] == 'combined', row >= 4)
                self.assertEqual(bool(decision.get('cold_start')), row >= 9)
                self.assertEqual(bool(decision.get('morning')), row >= 10)
                if row >= 9:
                    self.assertEqual(decision['silence_floor'], 32)

    def test_unknown_and_v3_rows_are_rejected(self):
        for row in [-1, 0, 12]:
            with self.assertRaises(ValueError):
                configuration(row)
        with self.assertRaises(ValueError):
            list(decision_grid(11))
