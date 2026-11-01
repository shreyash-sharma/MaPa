import unittest

import numpy as np
import torch

from mapa_train.cold_start import apply_priors
from mapa_train.gpu_training import ARRAY_FILE, _cold_start_inputs


class GpuPrecomputedInputTest(unittest.TestCase):
    def test_cached_gpu_inputs_match_original_history_path_on_sample(self):
        if not torch.cuda.is_available():
            self.skipTest("CUDA is required for the GPU input path")
        with np.load(ARRAY_FILE, allow_pickle=False) as a:
            x = a["x"]
            slow = a["slow_history"]
            count = a["slow_count"]
            row_map = a["row_person_day"]
            hourly = a["hourly_profile"]
            quiet = a["quiet_context"]
            weights = a["history_weight"]
            priors = a["archetype_priors"]
            centers = a["archetype_centers"]
            scales = a["archetype_scales"]
            centroids = a["archetype_centroids"]
            clusters = a["cluster_ids"]
            folds = a["fold_by_row"]

            fold, seed_index = 0, 0
            row = next(i for i, n in enumerate(count) if n >= 30 and folds[i] != fold)
            person_day = int(row_map[row])
            data = {
                "x": torch.as_tensor(x[row:row + 1], device="cuda"),
                "row_person_day": torch.as_tensor(row_map[row:row + 1], device="cuda"),
                "hourly_profile": torch.as_tensor(hourly, device="cuda"),
                "quiet_context": torch.as_tensor(quiet, device="cuda"),
                "history_weight": torch.as_tensor(weights, device="cuda"),
                "archetype_priors": torch.as_tensor(priors, device="cuda"),
                "cluster_ids": torch.as_tensor(clusters, device="cuda"),
            }
            for n in (0, 3, 7, 17, 30):
                prior_index = int(clusters[fold, seed_index, person_day, n])
                fitted = {"population_prior": priors[fold, seed_index, 0],
                          "archetype_priors": priors[fold, seed_index, 1:],
                          "center": centers[fold, seed_index],
                          "scale": scales[fold, seed_index],
                          "centroids": centroids[fold, seed_index],
                          "pseudo_days": 7.0}
                expected = apply_priors(x[row:row + 1], slow[row:row + 1],
                                        np.array([n]), fitted)
                actual = _cold_start_inputs(data, torch.tensor([0], device="cuda"),
                                             torch.tensor([n], device="cuda"), fold,
                                             seed_index, data["x"].float()).cpu().numpy()
                # GPU and NumPy use different float32 add/multiply evaluation
                # order after the cached float64 history statistics are read.
                np.testing.assert_allclose(actual, expected, rtol=0., atol=1e-7)


if __name__ == "__main__":
    unittest.main()
