package app.mapa.routine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Kotlin features, profile and GRU to the Python training code, using
 * `models/golden_v2.txt` written by `ml/mapa_rhythm/train.py`.
 */
class GoldenParityTest {
    private val models = File(System.getProperty("mapa.models") ?: "models")

    private class Golden(
        val dowOffset: Int,
        val history: List<DayRecord>,
        val rate: Array<DoubleArray>,
        val ticks: List<Tick>,
    )

    private class Tick(val obs: BinObservation, val x: FloatArray, val z: FloatArray)

    private fun load(): Golden {
        val f = File(models, "golden_v2.txt")
        assertTrue(f.exists(), "run `python -m mapa_rhythm.train` first: missing $f")
        var dowOffset = 0
        val days = ArrayList<DayRecord>()
        val rates = ArrayList<DoubleArray>()
        val ticks = ArrayList<Tick>()
        f.forEachLine { line ->
            val p = line.split(" ")
            when (p[0]) {
                "dow_offset" -> dowOffset = p[1].toInt()
                "day" -> days += DayRecord(p[1].toInt(), p[2].toInt()).also { r ->
                    for (b in 0 until Bins.PER_DAY) r.interactions[b] = p[3 + b].toInt()
                }
                "moved" -> days.last().let { r -> for (b in 0 until Bins.PER_DAY) r.moved[b] = p[1 + b].toInt() }
                "rate" -> rates += DoubleArray(Bins.PER_DAY) { p[1 + it].toDouble() }
                "tick" -> {
                    val moved = when (p[3]) { "1" -> true; "0" -> false; else -> null }
                    val obs = BinObservation(p[1].toInt(), p[2].toInt(), p[4] == "1", p[5].toInt(), moved)
                    val n = p.size - 6 - Bins.HORIZON
                    val x = FloatArray(n) { p[6 + it].toFloat() }
                    val z = FloatArray(Bins.HORIZON) { p[6 + n + it].toFloat() }
                    ticks += Tick(obs, x, z)
                }
            }
        }
        return Golden(dowOffset, days, rates.toTypedArray(), ticks)
    }

    /**
     * The GRU fallback's profile and network match Python. The network is fed the golden file's own inputs:
     * that file predates the 21-input contract (input 14 became state entropy on 28 Oct), so today's feature
     * builder is checked by FeatureContractParityTest instead.
     */
    @Test
    fun profileAndNetworkMatchPython() {
        val g = load()
        val weights = RhythmWeights.load(File(models, "rhythm_v2.weights.txt"))
        val profile = Profile.build(g.history, weights.prior())
        for (dt in 0 until 3) for (b in 0 until Bins.PER_DAY) {
            assertEquals(g.rate[dt][b], profile.rate[dt][b], 1e-6, "profile rate (prior is stored as float32) $dt/$b")
        }
        val net = KotlinGruBackbone(weights)
        val h = FloatArray(net.hiddenSize)
        var maxZ = 0.0
        for (tick in g.ticks) {
            val z = net.step(tick.x, h)
            for (i in 0 until Bins.HORIZON) maxZ = maxOf(maxZ, abs(z[i] - tick.z[i]).toDouble())
        }
        assertTrue(g.ticks.size >= 96)
        assertTrue(maxZ < 1e-3, "logit drift $maxZ")
    }
}
