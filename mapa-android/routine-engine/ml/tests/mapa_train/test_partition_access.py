import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from mapa_train import protocol


class PartitionAccessTest(unittest.TestCase):
    def test_training_verification_hashes_train_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.json").write_text(json.dumps({"sha256": {
                "train.parquet": "ok", "val.parquet": "ok", "test.parquet": "ok"}}))
            with patch.object(protocol, "DATA", root), \
                 patch.object(protocol, "sha256", return_value="ok") as digest:
                protocol.verify_data()
            self.assertEqual([Path(call.args[0]).name for call in digest.call_args_list],
                             ["train.parquet"])

    def test_explicit_evaluation_verifies_only_requested_partition(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "manifest.json").write_text(json.dumps({"sha256": {
                "train.parquet": "ok", "val.parquet": "ok", "test.parquet": "ok"}}))
            with patch.object(protocol, "DATA", root), \
                 patch.object(protocol, "sha256", return_value="ok") as digest:
                protocol.verify_data("test")
            self.assertEqual([Path(call.args[0]).name for call in digest.call_args_list],
                             ["test.parquet"])


if __name__ == "__main__":
    unittest.main()
