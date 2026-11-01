import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from mapa_train import decision


class ValidationLockTest(unittest.TestCase):
    def test_missing_config_and_completed_confirmation_do_not_open_data(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            folder = root / 'models' / 'fixture'
            folder.mkdir(parents=True)
            with patch.object(decision, 'ROOT', root), patch('sys.argv', ['decision', '--model', 'fixture']), patch.object(decision, 'day_views') as opening:
                (folder / 'selection.json').write_text('{}')
                with self.assertRaisesRegex(RuntimeError, 'locked decision_config'):
                    decision.main()
                (folder / 'selection.json').write_text(json.dumps({'decision_config': {'kind': 'global', 'floor': .5}}))
                (folder / 'validation-results.json').write_text('{}')
                with self.assertRaisesRegex(RuntimeError, 'already exists'):
                    decision.main()
                opening.assert_not_called()

    def test_confirmation_evaluates_only_locked_config(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); folder = root / 'models' / 'fixture'; folder.mkdir(parents=True)
            cfg = {'kind': 'global', 'floor': .431, 'score_kind': 'behaviour'}
            (folder / 'selection.json').write_text(json.dumps({'decision_config': cfg}))
            with patch.object(decision, 'ROOT', root), patch('sys.argv', ['decision', '--model', 'fixture']), patch.object(decision, 'day_views', return_value=[]), patch.object(decision, 'personal_history', return_value={}), patch.object(decision, 'stats_limits'), patch.object(decision, 'cold_start_curve', return_value={}), patch.object(decision, 'grid', side_effect=AssertionError('threshold sweep prohibited')), patch.object(decision, 'score_config', return_value={'model': {}, 'C4': False}) as scorer:
                decision.main()
                scorer.assert_called_once_with([], {}, cfg)
            self.assertTrue(json.loads((folder / 'validation-results.json').read_text())['confirmation_only'])
