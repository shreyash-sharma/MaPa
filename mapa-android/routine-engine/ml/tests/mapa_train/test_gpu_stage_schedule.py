import unittest

from mapa_train.gpu_training import enabled_training_stages


class GpuStageScheduleTests(unittest.TestCase):
    def test_plain_cnn_uses_its_two_configured_stages(self):
        stages = ("real_observed_pretrain", "derived_normal_rhythm")
        self.assertEqual(enabled_training_stages(stages), list(stages))

    def test_hybrid_uses_all_three_configured_stages(self):
        stages = ("real_observed_pretrain", "derived_normal_rhythm",
                  "behaviour_episode_finetune")
        self.assertEqual(enabled_training_stages(stages), list(stages))


if __name__ == "__main__":
    unittest.main()
