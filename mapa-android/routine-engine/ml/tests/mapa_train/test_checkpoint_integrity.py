import json
import tempfile
import unittest
from pathlib import Path
import numpy as np
from mapa_train.checkpoints import digest, resume_fit


class CheckpointIntegrityTest(unittest.TestCase):
    def test_resume_requires_protocol_indices_and_intact_artifacts(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            self.assertIsNone(resume_fit(folder, 'protocol', [2]))
            pred = folder / 'held-predictions.npz'
            np.savez(pred, indices=[2], behaviour=np.zeros((1,96)), hazard=np.zeros((1,96,16)), confidence=np.zeros((1,96)))
            (folder / 'fit.json').write_text(json.dumps({'protocol_sha256': 'protocol', 'artifacts_sha256': {pred.name: digest(pred)}}))
            self.assertIsNotNone(resume_fit(folder, 'protocol', [2]))
            with self.assertRaisesRegex(RuntimeError, 'different protocol'):
                resume_fit(folder, 'changed', [2])
            with self.assertRaises(AssertionError):
                resume_fit(folder, 'protocol', [3])
            pred.write_bytes(b'corrupted')
            with self.assertRaisesRegex(RuntimeError, 'artifact mismatch'):
                resume_fit(folder, 'protocol', [2])
