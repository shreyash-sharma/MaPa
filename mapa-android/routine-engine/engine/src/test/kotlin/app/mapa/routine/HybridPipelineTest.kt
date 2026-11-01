package app.mapa.routine

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone's decision path must give the tested model's numbers and alerts (mapa_train/device_golden.py). */
class HybridPipelineTest {
    private val root = File(System.getProperty("mapa.models") ?: "models", "hybrid")

    private class Golden(val dow: Int, val counts: List<IntArray>, val days: Map<Int, List<String>>, val bins: Map<Pair<Int, Int>, List<String>>)

    private fun golden(): Golden {
        var dow = 0
        val counts = ArrayList<IntArray>()
        val days = HashMap<Int, List<String>>()
        val bins = HashMap<Pair<Int, Int>, List<String>>()
        File(root, "golden_hybrid_pipeline.txt").forEachLine { line ->
            val p = line.trim().split(" ")
            when (p[0]) {
                "dow_offset" -> dow = p[1].toInt()
                "counts" -> counts.add(p.drop(2).map { it.toInt() }.toIntArray())
                "day" -> days[p[1].toInt()] = p
                "bin" -> bins[p[1].toInt() to p[2].toInt()] = p.drop(3)
            }
        }
        return Golden(dow, counts, days, bins)
    }

    private fun pipeline(dow: Int) = HybridPipeline(
        KotlinHybrid(RhythmWeights.load(File(root, "hybrid_reference.weights.txt"))),
        File(root, "hybrid_priors.txt").reader().use { ColdStartPriors.parse(it) },
        dow,
    )

    @Test
    fun matchesTheTestedPythonPipelineBinForBin() {
        val g = golden()
        val p = pipeline(g.dow)
        var maxLogit = 0.0
        var maxScore = 0.0
        var checkedBins = 0
        for ((d, day) in g.counts.withIndex()) {
            val results = (0 until Bins.PER_DAY).map { s ->
                p.step(BinObservation(d * Bins.PER_DAY + s, day[s], charging = false, battery = 50))
            }
            val summary = g.days.getValue(d)
            assertEquals(summary[3].toInt(), results[0].historyDays, "history days on day $d")
            assertEquals(summary[11].toInt(), results.count { it.pleaseCall }, "please-call bins on day $d")
            assertEquals(summary[13].toInt(), results.count { it.worthALook }, "worth-a-look bins on day $d")
            for (s in 0 until Bins.PER_DAY) {
                val want = g.bins[d to s] ?: continue
                val r = results[s]
                for (k in 0 until 19) maxLogit = maxOf(maxLogit, abs(r.logits[k] - want[21 + k].toDouble()))
                maxScore = maxOf(maxScore, abs(r.score - want[40].toDouble()), abs(r.threshold - want[41].toDouble()))
                assertEquals(want[42] == "1", r.pleaseCall, "please call at day $d bin $s")
                assertEquals(want[43] == "1", r.worthALook, "worth a look at day $d bin $s")
                checkedBins++
            }
        }
        println("Hybrid pipeline: $checkedBins bins, max logit error $maxLogit, max score error $maxScore")
        assertTrue(checkedBins >= 900)
        assertTrue(maxLogit <= 1e-3, "logits drift: $maxLogit")
        assertTrue(maxScore <= 1e-4, "scores drift: $maxScore")
    }

    @Test
    fun aJobThatStopsAndResumesGivesTheSameAlerts() {
        val g = golden()
        val straight = pipeline(g.dow)
        var resumed = pipeline(g.dow)
        for ((d, day) in g.counts.withIndex()) {
            for (s in 0 until Bins.PER_DAY) {
                val obs = BinObservation(d * Bins.PER_DAY + s, day[s], charging = false, battery = 50)
                val a = straight.step(obs)
                val b = resumed.step(obs)
                assertEquals(a.pleaseCall, b.pleaseCall); assertEquals(a.worthALook, b.worthALook)
                assertTrue(abs(a.score - b.score) < 1e-6, "day $d bin $s")
                if (s % 7 == 3) {   // a WorkManager run ends here: save, then a new process loads
                    val w = StateWriter(); resumed.save(w)
                    resumed = pipeline(g.dow).also { it.load(StateReader(w.toString())) }
                }
            }
        }
    }

    @Test
    fun alertsWorkFromDayOneWithAnyHistoryLength() {
        val g = golden()
        val report = StringBuilder()
        for (k in listOf(0, 1, 3, 7, 14)) {
            val p = pipeline(g.dow)
            for (d in 0 until k) for (s in 0 until Bins.PER_DAY) p.step(BinObservation(d * Bins.PER_DAY + s, g.counts[d][s], false, 80))
            // Day k: an ordinary morning until 07:45, then no use at all.
            val day = g.counts[k]
            var firstCall: Int? = null
            var firstLook: Int? = null
            var normalCalls = 0
            for (s in 0 until Bins.PER_DAY) {
                val r = p.step(BinObservation(k * Bins.PER_DAY + s, if (s >= 31 && day[s] >= 0) 0 else day[s], false, 80))
                if (r.pleaseCall && firstCall == null) firstCall = s
                if (r.worthALook && firstLook == null) firstLook = s
            }
            // The same day with its real use: how many "please call" bins on an ordinary day.
            val q = pipeline(g.dow)
            for (d in 0..k) for (s in 0 until Bins.PER_DAY) { val r = q.step(BinObservation(d * Bins.PER_DAY + s, g.counts[d][s], false, 80)); if (d == k && r.pleaseCall) normalCalls++ }
            fun hm(s: Int?) = s?.let { "%02d:%02d".format(it / 4, it % 4 * 15) } ?: "none"
            report.append("history=$k days: quiet day -> please call from ${hm(firstCall)}, worth a look from ${hm(firstLook)}; ordinary day -> $normalCalls please-call bins").append('\n')
            assertTrue(firstCall != null, "a quiet day must alert with $k days of history")
        }
        println(report)
        File(System.getProperty("java.io.tmpdir"), "mapa-dayone.txt").writeText(report.toString())
    }

    @Test
    fun aTaggedDayIsNeverLearned() {
        val g = golden()
        val p = pipeline(g.dow)
        for (s in 0 until Bins.PER_DAY) p.step(BinObservation(s, g.counts[0][s], false, 80))
        p.tagToday()
        for (s in 0 until Bins.PER_DAY) p.step(BinObservation(Bins.PER_DAY + s, g.counts[1][s], false, 80))
        val next = p.step(BinObservation(2 * Bins.PER_DAY, g.counts[2][0], false, 80))
        assertEquals(1, next.historyDays, "day 0 is learned, the tagged day 1 is not")
    }
}
