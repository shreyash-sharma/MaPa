import unittest
import numpy as np
import torch
from mapa_train.zoo import train_neural, predict


class TrainingPipelineTest(unittest.TestCase):
    def test_three_stages_produce_finite_predictions_without_reading_data(self):
        torch.set_num_threads(1)
        rng = np.random.default_rng(190)
        n = 14
        x = rng.random((n, 96, 21)).astype(np.float32)
        x[:, :, 0] = rng.integers(0, 2, size=(n, 96))
        x[:, :, 3] = 1
        y = rng.integers(0, 2, size=(n, 96, 17)).astype(np.float32)
        mask = np.ones_like(y, dtype=bool)
        episode = np.arange(n) >= 7
        groups = [{'pid': f'p{i % 7}', 'source': 'S3' if i % 7 == 0 else 'derived',
                   'episode_id': 'fixture' if episode[i] else ''} for i in range(n)]
        history = rng.random((n, 30, 105)).astype(np.float32)
        history[:, :, 9:] = rng.integers(0, 2, size=(n, 30, 96))
        counts = np.full(n, 3, dtype=np.int64)
        tr = (x, y, mask, np.zeros(n, bool), np.ones(n, bool), episode, history, counts, groups)
        report, model = train_neural('hybrid', tr, 15, 'cpu', seed=71)
        self.assertEqual(len(report['stages']), 3)
        self.assertTrue(all(np.isfinite(row['train_loss']) for row in report['log']))
        out = predict('hybrid', model, x, 'cpu', history, counts)
        self.assertEqual(out['hazard'].shape, (n, 96, 16))
        for key in ['behaviour', 'confidence']:
            self.assertEqual(out[key].shape, (n, 96))
            self.assertTrue(np.isfinite(out[key]).all())
        self.assertEqual(set(model.cold_start_priors['fit_pids']), {f'p{i}' for i in range(1, 7)})
