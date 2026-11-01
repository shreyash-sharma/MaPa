package app.mapa.routine

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotTest {
    private val weights = RhythmWeights.load(File(System.getProperty("mapa.models") ?: "models", "rhythm_v2.weights.txt"))

    private val priorDays = weights["silence_prior_days"].data[0].toDouble()

    private fun engine() = RoutineEngine(
        EngineConfig(), KotlinGruBackbone(weights), weights.prior(), weights["silence_prior"].data, dowOffset = 2, installT = 0,
        silencePriorDays = priorDays,
    )

    private fun restore(snap: String) = RoutineEngine.restore(
        EngineConfig(), KotlinGruBackbone(weights), weights.prior(), weights["silence_prior"].data, snap, priorDays,
    )

    /** A parent who wakes about 06:30, uses the phone in bursts, charges overnight, and has a later-mornings phase. */
    private fun observations(days: Int, seed: Int): List<Pair<BinObservation, Boolean>> {
        val rnd = Random(seed)
        val out = ArrayList<Pair<BinObservation, Boolean>>()
        var battery = 90
        for (d in 0 until days) {
            val wake = 26 + rnd.nextInt(-2, 3) + if (d >= 45) 5 else 0
            val sleep = 88 + rnd.nextInt(-2, 3)
            val outing = if (rnd.nextDouble() < 0.2) 40 + rnd.nextInt(20) else -1
            for (b in 0 until Bins.PER_DAY) {
                val t = d * Bins.PER_DAY + b
                val awake = b in wake until sleep
                val charging = !awake
                battery = if (charging) minOf(100, battery + 5) else maxOf(5, battery - 1)
                val inOuting = outing >= 0 && b in outing until outing + 12
                val c = when {
                    d == 20 && b in 40..60 -> -1 // usage access briefly lost
                    awake && !inOuting && rnd.nextDouble() < 0.4 -> 1 + rnd.nextInt(4)
                    else -> 0
                }
                // movement: the outing's legs are seen; no signal at all on days 10 to 14
                val moved = when {
                    d in 10..14 -> null
                    inOuting && (b == outing || b == outing + 11) -> true
                    else -> false
                }
                out += BinObservation(t, c, charging, battery, moved) to (d == 30 && b in 50..55)
            }
        }
        return out
    }

    @Test
    fun restoredEngineIsIndistinguishable() {
        val obs = observations(days = 70, seed = 4)
        val a = engine()
        var b: RoutineEngine? = null
        var compared = 0
        for ((i, pair) in obs.withIndex()) {
            val (o, paused) = pair
            if (i % (5 * Bins.PER_DAY + 37) == 0) {
                val snap = a.snapshot()
                b = restore(snap)
                assertEquals(snap, b.snapshot(), "snapshot round-trips byte for byte")
            }
            if (i == 12 * Bins.PER_DAY) { a.addLifeTag(LifeTag(o.t, o.t + 96, LifeTagKind.GUESTS)); b?.addLifeTag(LifeTag(o.t, o.t + 96, LifeTagKind.GUESTS)) }
            // skip a few bins to exercise catch-up
            if (Bins.binOfDay(o.t) == 70 && Bins.day(o.t) % 9 == 0) continue
            val ra = a.onBin(o, paused)
            val rb = b?.onBin(o, paused)
            if (rb != null) { assertEquals(ra, rb, "tick ${o.t}"); compared++ }
            if (a.pendingQuestion != null) { a.answerNewRoutine(true); b?.answerNewRoutine(true) }
        }
        assertTrue(compared > 6000)
        assertTrue(a.modelReady, "the run reached the model-deciding phase")
    }
}
