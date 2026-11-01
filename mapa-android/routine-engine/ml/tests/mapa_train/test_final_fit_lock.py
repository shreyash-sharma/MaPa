import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from mapa_train.final_fit import fit_selected


class FinalFitLockTest(unittest.TestCase):
    def test_failed_cv_gate_cannot_trigger_training_or_data_access(self):
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)/'cv'; source.mkdir()
            (source/'cv-decision-results.json').write_text(json.dumps({
                'family': 'hybrid', 'selected_on_train_oof_only': {'minimum_mean': False}}))
            with patch('mapa_train.final_fit.freeze', return_value={}), patch('mapa_train.final_fit.load') as loading, patch('mapa_train.final_fit.train_neural') as training:
                with self.assertRaisesRegex(RuntimeError, 'minimum gate'):
                    fit_selected(source, Path(temp)/'final')
                loading.assert_not_called()
                training.assert_not_called()
