import hashlib
import json
import tempfile
import unittest
from pathlib import Path
import torch
from mapa_train.models import Hybrid
from mapa_train.locked_model import load_locked_hybrid


class LockedModelTest(unittest.TestCase):
    def test_exact_parameters_and_hash_rejection(self):
        torch.set_num_threads(1)
        model = Hybrid()
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            path = folder/'model.pt'
            torch.save({'family':'hybrid', 'state_dict':model.state_dict(),
                        'ablation_config':{'attention':True,'personal':True,'slow':True}},path)
            (folder/'selection.json').write_text(json.dumps({'checkpoint_sha256':hashlib.sha256(path.read_bytes()).hexdigest()}))
            (folder/'complete.json').write_text(json.dumps({'feature_contract':21}))
            restored, _, _ = load_locked_hybrid(folder)
            for name, value in model.state_dict().items():
                torch.testing.assert_close(value,restored.state_dict()[name],equal_nan=True)
            path.write_bytes(b'corrupt')
            with self.assertRaisesRegex(RuntimeError,'hash mismatch'):
                load_locked_hybrid(folder)
