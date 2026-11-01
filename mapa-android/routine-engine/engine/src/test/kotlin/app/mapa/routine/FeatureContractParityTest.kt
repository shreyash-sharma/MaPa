package app.mapa.routine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Checks the complete 21-input Python/Kotlin contract from a deterministic fixture. */
class FeatureContractParityTest {
    @Test
    fun pythonAndKotlinProduceTheSameTwentyOneInputs() {
        val file = File(System.getProperty("mapa.models") ?: "models", "golden_features_21.txt")
        assertTrue(file.exists(), "missing $file; run mapa_train.generate_feature_golden")
        var dow = 0
        var history = 0
        val rates = Array(3) { DoubleArray(Bins.PER_DAY) }
        val expected = mutableListOf<FloatArray>()
        val observations = mutableListOf<BinObservation>()
        file.forEachLine { line ->
            val p = line.split(" ")
            when (p[0]) {
                "feature-contract-v1" -> Unit
                "dow" -> dow = p[1].toInt()
                "history" -> history = p[1].toInt()
                "rate" -> {
                    val dt = p[1].toInt()
                    for (i in 0 until Bins.PER_DAY) rates[dt][i] = p[i + 2].toDouble()
                }
                "bin" -> {
                    val t = p[1].toInt()
                    val count = p[2].toInt()
                    val moved = p[3].toInt().let { if (it < 0) null else it == 1 }
                    val charging = p[4].toInt() == 1
                    val battery = p[5].toInt()
                    observations += BinObservation(t, count, charging, battery, moved)
                    expected += FloatArray(FeatureBuilder.N) { p[it + 6].toFloat() }
                }
            }
        }
        assertEquals(10 * Bins.PER_DAY, observations.size)
        val builder = FeatureBuilder(dow)
        val profile = Profile(rates, history, null)
        var maxError = 0.0
        observations.forEachIndexed { i, obs ->
            val got = builder.build(obs, profile).x
            assertEquals(21, got.size)
            for (j in got.indices) maxError = maxOf(maxError, abs(got[j] - expected[i][j]).toDouble())
            assertEquals(if (obs.moved == true) 1f else 0f, got[16], "movement input at tick ${obs.t}")
            assertEquals(if (obs.moved != null) 1f else 0f, got[17], "movement-known input at tick ${obs.t}")
            assertEquals(expected[i][20], got[20], "session-start input at tick ${obs.t}")
        }
        assertTrue(maxError <= 1e-5, "21-input feature drift: $maxError")
    }
}
