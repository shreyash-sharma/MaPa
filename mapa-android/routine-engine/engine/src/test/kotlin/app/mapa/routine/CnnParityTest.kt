package app.mapa.routine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the Kotlin CNN to PyTorch for every size that `ml/mapa_rhythm/train_cnn.py` wrote to
 * `models/cnn`. Skips quietly when no CNN has been trained yet.
 */
class CnnParityTest {
    private val dir = File(System.getProperty("mapa.models") ?: "models", "cnn")

    @Test
    fun logitsMatchPythonForEverySize() {
        val sizes = dir.listFiles { f -> f.name.matches(Regex("rhythm_cnn_.*\\.weights\\.txt")) }.orEmpty()
        for (wf in sizes) {
            val name = wf.name.removePrefix("rhythm_cnn_").removeSuffix(".weights.txt")
            val golden = File(dir, "golden_cnn_$name.txt")
            assertTrue(golden.exists(), "missing $golden")
            val weights = RhythmWeights.load(wf)
            val net = RhythmBackbones.create(weights)
            assertTrue(net is KotlinCnnBackbone, "$name should load as a CNN")
            val h = FloatArray(net.hiddenSize)
            var maxZ = 0.0
            var n = 0
            golden.forEachLine { line ->
                val p = line.split(" ")
                if (p[0] == "tick") {
                    val nIn = p.size - 6 - Bins.HORIZON
                    val x = FloatArray(nIn) { p[6 + it].toFloat() }
                    val z = net.step(x, h)
                    for (i in 0 until Bins.HORIZON) {
                        maxZ = maxOf(maxZ, abs(z[i] - p[6 + nIn + i].toFloat()).toDouble())
                    }
                    n++
                }
            }
            assertTrue(n >= 96, "$name: only $n ticks")
            assertTrue(maxZ < 1e-3, "$name logit drift $maxZ")
        }
    }
}
