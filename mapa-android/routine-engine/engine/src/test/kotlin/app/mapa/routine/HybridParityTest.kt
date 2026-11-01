package app.mapa.routine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridParityTest {
    @Test
    fun dailyEncoderMatchesPythonAtColdStartBoundaries() {
        val root = File(System.getProperty("mapa.models") ?: "models")
        val reference = HybridReference(RhythmWeights.load(File(root, "hybrid_reference.weights.txt")))
        var maxError = 0.0
        var checked = 0
        File(root, "golden_hybrid_daily.txt").forEachLine { line ->
            if (line.isNotBlank() && !line.startsWith("#")) {
                val p = line.split(" ")
                val count = p[0].toInt()
                val rows = Array(30) { t -> FloatArray(9) { j -> p[1+9*t+j].toFloat() } }
                val got = reference.encodeDaily(rows, count)
                for (j in got.indices) maxError = maxOf(maxError, abs(got[j]-p[271+j].toFloat()).toDouble())
                checked++
            }
        }
        assertEquals(5, checked)
        println("Hybrid daily Kotlin/Python maximum error=$maxError")
        assertTrue(maxError <= 1e-3)
    }

    @Test
    fun streamingHybridMatchesPythonIncludingAttentionRollover() {
        val root = File(System.getProperty("mapa.models") ?: "models")
        val reference = HybridReference(RhythmWeights.load(File(root, "hybrid_reference.weights.txt")))
        var rows = 0
        var maxError = 0.0
        File(root, "golden_hybrid_reference.txt").forEachLine { line ->
            if (line.isNotBlank() && !line.startsWith("#")) {
                val values = line.split(" ").map { it.toFloat() }
                assertEquals(56, values.size)
                val got = reference.step(values.subList(0,21).toFloatArray(), values.subList(21,37).toFloatArray())
                assertEquals(19, got.size)
                for (j in got.indices) {
                    assertTrue(got[j].isFinite())
                    maxError = maxOf(maxError, abs(got[j]-values[37+j]).toDouble())
                }
                rows++
            }
        }
        assertEquals(192, rows)
        println("Hybrid Kotlin/Python streaming maximum error=$maxError, rows=$rows")
        assertTrue(maxError <= 1e-3, "hybrid parity drift: $maxError")
    }
}
