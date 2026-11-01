import unittest
import numpy as np
from mapa_train.masking import mask_use_inputs


class MaskingTest(unittest.TestCase):
    def test_use_derived_channels_cannot_reveal_a_masked_bin(self):
        x = np.ones((2, 96, 21), dtype=np.float32)
        hidden = np.zeros((2, 96), bool)
        hidden[0, 40] = True
        out = mask_use_inputs(x, hidden)
        np.testing.assert_array_equal(out[0, 40, :4], 0)
        np.testing.assert_array_equal(out[0, :, 14], 0)
        np.testing.assert_array_equal(out[0, 40:42, 20], 0)
        np.testing.assert_array_equal(out[1], x[1])
        np.testing.assert_array_equal(out[..., 16:18], x[..., 16:18])
        np.testing.assert_array_equal(x, 1)
