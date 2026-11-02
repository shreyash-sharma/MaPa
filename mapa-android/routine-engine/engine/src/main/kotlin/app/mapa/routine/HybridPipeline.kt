package app.mapa.routine

import java.io.Reader
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.min

/** The trained Hybrid behind one interface: the Kotlin reference, or ExecuTorch on the phone. */
interface HybridModel {
    val hiddenSize: Int
    fun reset()
    fun encodeDaily(rows: Array<FloatArray>, count: Int): FloatArray
    fun step(x: FloatArray, slowVector: FloatArray): FloatArray
    /** The streaming state (convolution buffers, GRU, memory), so a 15-minute job can stop and resume. */
    fun saveState(): FloatArray
    fun loadState(state: FloatArray)
}

class KotlinHybrid(weights: RhythmWeights) : HybridModel {
    private val reference = HybridReference(weights)
    override val hiddenSize get() = reference.hiddenSize
    override fun reset() = reference.reset()
    override fun encodeDaily(rows: Array<FloatArray>, count: Int) = reference.encodeDaily(rows, count)
    override fun step(x: FloatArray, slowVector: FloatArray) = reference.step(x, slowVector)
    override fun saveState() = reference.saveState()
    override fun loadState(state: FloatArray) = reference.loadState(state)
}

/** Versioned decision rules around the frozen neural weights. */
data class HybridDecisionPolicy(
    val id: String,
    val absoluteQuietBins: Int,
    val suppressTaggedAlerts: Boolean,
    val hybridOwnsAbsoluteFloor: Boolean,
) {
    init { require(absoluteQuietBins > 0) }

    companion object {
        /** Historical v1 behavior. Keep it available for exact result reproduction. */
        val V1 = HybridDecisionPolicy("v1", 32, suppressTaggedAlerts = false,
            hybridOwnsAbsoluteFloor = false)

        /** v2 policy selected on train CV and evaluated on the post-selection derived cohort. */
        val V2 = HybridDecisionPolicy("v2", 48, suppressTaggedAlerts = true,
            hybridOwnsAbsoluteFloor = true)
    }
}

/** Day-one start: archetype priors and shrinkage, from cold-start-priors.json (shipped as text). */
class ColdStartPriors(
    val center: FloatArray,
    val scale: FloatArray,
    val population: FloatArray,
    val centroids: List<FloatArray>,
    val archetypes: List<FloatArray>,
    val pseudoDays: Double,
) {
    companion object {
        fun parse(reader: Reader): ColdStartPriors {
            val rows = reader.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().split(Regex("\\s+")) }
            fun one(key: String) = rows.single { it[0] == key }.drop(1).map { it.toFloat() }.toFloatArray()
            fun many(key: String) = rows.filter { it[0] == key }.map { r -> r.drop(1).map { it.toFloat() }.toFloatArray() }
            return ColdStartPriors(one("center"), one("scale"), one("population"), many("centroid"), many("archetype"),
                rows.single { it[0] == "pseudo_days" }[1].toDouble())
        }
    }
}

/**
 * The tested MaPa Rhythm Hybrid decision path on raw 15-minute counts (mapa_train/device_golden.py is the
 * Python reference; HybridPipelineTest checks every number). Per bin it returns the model's "please call" /
 * "worth a look" decision. Only "please call" was scored in the held-out test.
 *
 * Inputs match the frozen training data on purpose: movement unknown, charging = the night mask, battery 80.
 * The real battery and charging still drive "Monitoring unavailable" and the low-battery note elsewhere.
 * Days with a life tag never enter the history (guard), and unobserved bins are never quiet.
 */
class HybridPipeline(
    private val model: HybridModel,
    private val priors: ColdStartPriors,
    private val dowOffset: Int,
    val decisionPolicy: HybridDecisionPolicy = HybridDecisionPolicy.V1,
) {
    class Result(
        val t: Int,
        val logits: FloatArray,
        /** 0.5 x (unusual-quiet score + surprise). */
        val score: Double,
        val threshold: Double,
        val pleaseCall: Boolean,
        val worthALook: Boolean,
        /** Days of history behind today's personal context (0..30). */
        val historyDays: Int,
    ) {
        val nextUseChance: Double get() = sigmoid(logits[0].toDouble())
        val confidence: Double get() = sigmoid(logits[18].toDouble())
    }

    private class HistoryDay(val summary: FloatArray, val use: FloatArray)

    private val features = FeatureBuilder(dowOffset)
    private val history = ArrayDeque<HistoryDay>()          // last 30 quality days
    private val scoreHistory = ArrayDeque<DoubleArray>()    // last 14 full days, scores at eligible bins
    private var day: Int? = null
    private var dayTagged = false
    private var pendingTaggedDay: Int? = null
    private val counts = IntArray(Bins.PER_DAY) { -1 }
    private val scores = DoubleArray(Bins.PER_DAY)
    private val eligible = BooleanArray(Bins.PER_DAY)
    private var context = Array(Bins.PER_DAY) { FloatArray(5) }
    private var weight = 0.0
    private var slow = FloatArray(0)
    private var line = LINE_FLOOR
    private var streak = 0
    private var survival = 1.0
    private var lastHazard = 0.0
    private var lastSlot = -1

    /** Marks the given day as context-tagged, including when called before its first bin. */
    fun tagToday(dayIndex: Int) {
        if (day == dayIndex) dayTagged = true else pendingTaggedDay = dayIndex
    }

    fun step(obs: BinObservation): Result {
        val d = Bins.day(obs.t)
        if (day != d) startDay(d)
        val slot = Bins.binOfDay(obs.t)
        require(slot > lastSlot) { "bins must arrive in order within a day" }
        // A first install mid-day, or a gap: the missing bins are unobserved, never quiet.
        while (lastSlot + 1 < slot) step(BinObservation(obs.t - slot + lastSlot + 1, -1, false, 80))
        lastSlot = slot
        counts[slot] = obs.interactions
        val night = slot < DAY_START || slot >= DAY_END
        val fed = BinObservation(obs.t, obs.interactions, charging = night, battery = 80, moved = null)
        val x = features.build(fed, PROFILE).x
        for ((i, col) in CONTEXT.withIndex()) x[col] = context[slot][i]
        x[15] = weight.toFloat()
        val logits = model.step(x, slow)

        val quiet = obs.observed && !night && obs.interactions == 0
        streak = if (quiet) streak + 1 else 0
        if (slot == DAY_START) survival = 1.0
        var surprise = 0.0
        if (quiet) {
            survival *= 1.0 - lastHazard
            surprise = 1.0 - survival
        } else {
            survival = 1.0
        }
        lastHazard = sigmoid(logits[0].toDouble())
        val score = 0.5 * (sigmoid(logits[17].toDouble()) + surprise)
        val confidence = sigmoid(logits[18].toDouble()).coerceIn(0.0, 1.0)
        val threshold = min(1.0, line + (1.0 - confidence) * UNCERTAINTY_MARGIN)
        val tagAllowsAlert = !decisionPolicy.suppressTaggedAlerts || !dayTagged
        val call = quiet && tagAllowsAlert &&
            (score >= threshold || streak >= decisionPolicy.absoluteQuietBins)
        val look = quiet && tagAllowsAlert && !call && score >= LOOK_FRACTION * threshold
        scores[slot] = score
        eligible[slot] = quiet
        return Result(obs.t, logits, score, threshold, call, look, history.size)
    }

    private fun startDay(d: Int) {
        day?.let { closeDay(it) }
        day = d
        dayTagged = pendingTaggedDay == d
        if (pendingTaggedDay != null && pendingTaggedDay!! <= d) pendingTaggedDay = null
        counts.fill(-1); scores.fill(0.0); eligible.fill(false)
        streak = 0; survival = 1.0; lastHazard = 0.0; lastSlot = -1
        model.reset()
        val n = history.size
        val summaries = history.map { it.summary }
        var prior = priors.population
        if (n >= 3) {
            val z = FloatArray(9) { j -> (summaries.map { it[j] }.average().toFloat() - priors.center[j]) / priors.scale[j] }
            val cluster = priors.centroids.indices.minBy { k ->
                var s = 0.0; for (j in 0 until 9) { val v = (priors.centroids[k][j] - z[j]).toDouble(); s += v * v }; s
            }
            prior = priors.archetypes[cluster]
        }
        weight = if (n == 0) 0.0 else n / (n + priors.pseudoDays)
        context = if (n == 0) Array(Bins.PER_DAY) { prior.copyOf() } else personalContext(prior)
        val rows = Array(30) { i -> if (i < n) summaries[i].copyOf() else FloatArray(9) }
        slow = model.encodeDaily(rows, n)
        val past = scoreHistory.flatMap { it.asIterable() }
        line = if (past.size >= 96) maxOf(LINE_FLOOR, quantile(past.sorted(), 0.9)) else LINE_FLOOR
    }

    private fun personalContext(prior: FloatArray): Array<FloatArray> {
        val hourly = DoubleArray(Bins.PER_DAY) { s ->
            var sum = 0.0; var known = 0
            for (h in history) if (h.use[s] >= 0f) { sum += h.use[s]; known++ }
            if (known > 0) sum / known else prior[0].toDouble()
        }
        val pooled = history.flatMap { quietRuns(it.use) }
        val q = if (pooled.size >= 5) pooled.map { it.toDouble() }.sorted().let { quantile(it, .90) to quantile(it, .995) } else 16.0 to 32.0
        val q90 = (ln1p(q.first) / LN_CAP).toFloat()
        val q995 = (ln1p(q.second) / LN_CAP).toFloat()
        val w = weight.toFloat()
        return Array(Bins.PER_DAY) { s ->
            val personal = floatArrayOf(
                hourly[(s + 1) % 96].toFloat(),
                ((1..4).sumOf { hourly[(s + it) % 96] } / 4).toFloat(),
                ((5..8).sumOf { hourly[(s + it) % 96] } / 4).toFloat(),
                q90, q995,
            )
            FloatArray(5) { i -> w * personal[i] + (1 - w) * prior[i] }
        }
    }

    private fun closeDay(d: Int) {
        val observed = (24 until 96).count { counts[it] >= 0 }
        if (observed < 56 || dayTagged) return
        scoreHistory.addLast(DoubleArray(eligible.count { it }).also { out ->
            var k = 0; for (s in 0 until Bins.PER_DAY) if (eligible[s]) out[k++] = scores[s]
        })
        while (scoreHistory.size > 14) scoreHistory.removeFirst()
        history.addLast(HistoryDay(dailySummary(counts, Bins.dow(d, dowOffset)),
            FloatArray(Bins.PER_DAY) { s -> if (counts[s] >= 0) (if (counts[s] > 0) 1f else 0f) else -1f }))
        while (history.size > 30) history.removeFirst()
    }

    internal fun save(w: StateWriter) {
        w.tag("hybrid").intOrNull(day).bool(dayTagged).int(lastSlot).ints(counts).doubles(scores)
            .ints(IntArray(Bins.PER_DAY) { if (eligible[it]) 1 else 0 }).int(streak).double(survival).double(lastHazard)
            .int(history.size)
        history.forEach { w.floats(it.summary).floats(it.use) }
        w.int(scoreHistory.size); scoreHistory.forEach { w.doubles(it) }
        w.floats(model.saveState())
        features.save(w)
    }

    internal fun load(r: StateReader) {
        r.tag("hybrid")
        val savedDay = r.intOrNull(); val tagged = r.bool(); val savedSlot = r.int()
        val savedCounts = r.ints(); val savedScores = r.doubles(); val savedEligible = r.ints()
        val savedStreak = r.int(); val savedSurvival = r.double(); val savedHazard = r.double()
        history.clear(); repeat(r.int()) { history.addLast(HistoryDay(r.floats(), r.floats())) }
        scoreHistory.clear(); repeat(r.int()) { scoreHistory.addLast(r.doubles()) }
        val modelState = r.floats()
        features.load(r)
        // Today's context, slow vector and line are recomputed from the history (which excludes today).
        day = null
        if (savedDay != null) {
            startDay(savedDay)
            dayTagged = tagged
            savedCounts.copyInto(counts); savedScores.copyInto(scores)
            for (s in eligible.indices) eligible[s] = savedEligible[s] == 1
            streak = savedStreak; survival = savedSurvival; lastHazard = savedHazard; lastSlot = savedSlot
            model.loadState(modelState)
        }
    }

    companion object {
        const val DAY_START = 28
        const val DAY_END = 88
        const val LINE_FLOOR = 0.5
        const val UNCERTAINTY_MARGIN = 0.1
        const val LOOK_FRACTION = 0.7
        /** Historical v1 floor; v2 uses [HybridDecisionPolicy.absoluteQuietBins]. */
        const val SILENCE_FLOOR = 32
        private val CONTEXT = intArrayOf(11, 12, 13, 18, 19)
        private val LN_CAP = ln1p(192.0)
        private val PROFILE = Profile.priorOnly(Array(3) { DoubleArray(Bins.PER_DAY) { 0.1 } })

        fun sigmoid(v: Double) = 1.0 / (1.0 + exp(-v))

        /** NumPy's default (linear) quantile of a sorted list. */
        fun quantile(sorted: List<Double>, p: Double): Double {
            val at = p * (sorted.size - 1); val lo = at.toInt(); val hi = min(lo + 1, sorted.lastIndex)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (at - lo)
        }

        /** Completed zero-use spells strictly inside observed daytime segments (features._quiet_runs). */
        fun quietRuns(use: FloatArray): List<Int> {
            val found = ArrayList<Int>()
            var s = DAY_START
            while (s < DAY_END) {
                if (use[s] < 0f) { s++; continue }
                var end = s
                while (end < DAY_END && use[end] >= 0f) end++
                var a = s
                while (a < end) {
                    val used = use[a] > 0f
                    var b = a
                    while (b < end && (use[b] > 0f) == used) b++
                    if (!used && a > s && b < end) found.add(b - a)
                    a = b
                }
                s = end
            }
            return found
        }

        /** mapa_release.learning.daily_summary with the training placeholders (no movement, night charging, battery 80). */
        fun dailySummary(counts: IntArray, dow: Int): FloatArray {
            var first = -1; var last = -1; var bursts = 0; var longest = 0; var run = 0
            var prevUse = false
            for (s in 0 until Bins.PER_DAY) {
                val use = counts[s] > 0
                if (use) { if (first < 0) first = s; last = s }
                if (use && !prevUse) bursts++
                prevUse = use
            }
            for (b in 24 until 96) {
                if (counts[b] >= 0 && counts[b] <= 0) { run++; longest = maxOf(longest, run) } else run = 0
            }
            val obsDay = (24 until 96).filter { counts[it] >= 0 }
            val chargeShare = if (obsDay.isEmpty()) 0f else obsDay.count { it < DAY_START || it >= DAY_END }.toFloat() / obsDay.size
            return floatArrayOf(
                if (first >= 0) first / 96f else 1f,
                if (last >= 0) last / 96f else 1f,
                longest / 72f,
                bursts / 24f,
                obsDay.size / 72f,
                chargeShare,
                if (obsDay.isEmpty()) 0f else 0.8f,
                0f,
                dow / 6f,
            )
        }
    }
}
