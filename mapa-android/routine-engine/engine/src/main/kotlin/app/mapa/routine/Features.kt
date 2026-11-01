package app.mapa.routine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.min
import kotlin.math.sin

/**
 * The 20 derived inputs of the rhythm model. Mirrors the Python training contract;
 * the golden-vector test fails if the two drift apart. No app names, content or
 * location: only counts, movement yes/no, time, battery and the personal profile.
 */
class FeatureBuilder(private val dowOffset: Int) {
    var lastInteractionT: Int? = null
        private set
    /** Last bin with use or movement. */
    var lastActivityT: Int? = null
        private set
    private var historyDay: Int? = null
    private var lastWasActive = false
    private var previousBinT: Int? = null
    private var previousBinObserved = false
    private var previousBinActive = false
    private var currentQuiet = 0
    private val completedToday = ArrayList<Int>()
    private val completedHistory = ArrayList<Int>()
    private val stateCounts = DoubleArray(4) // use, short break, long quiet, night

    class Row(val x: FloatArray, val rates: FloatArray)

    /** Row for tick [obs].t, describing the world after that bin to predict t+1..t+HORIZON. */
    fun build(obs: BinObservation, profile: Profile): Row {
        val sessionStart = obs.active && previousBinT == obs.t - 1 &&
            previousBinObserved && !previousBinActive
        if (obs.active) lastInteractionT = obs.t
        if (obs.alive) lastActivityT = obs.t
        val t = obs.t
        val day = Bins.day(t)
        if (historyDay != day) {
            if (historyDay != null) completedHistory.addAll(completedToday)
            completedToday.clear(); currentQuiet = 0; lastWasActive = false
            stateCounts.fill(0.0); historyDay = day
        }
        val slot = Bins.binOfDay(t)
        val daytime = slot in 28 until 88
        if (!obs.observed || !daytime) {
            currentQuiet = 0; lastWasActive = false
        } else if (obs.active) {
            if (currentQuiet > 0) completedToday.add(currentQuiet)
            currentQuiet = 0; lastWasActive = true
        } else {
            if (lastWasActive) currentQuiet = 1 else if (currentQuiet > 0) currentQuiet++
            lastWasActive = false
        }
        val x = FloatArray(N)
        x[0] = if (obs.active) 1f else 0f
        val sil = lastActivityT?.let { min(t - it, SILENCE_CAP) } ?: SILENCE_CAP
        val dayState = when {
            slot !in 24 until Bins.PER_DAY -> 3 // night
            obs.active -> 0                    // in use
            obs.moved == true || sil < 16 -> 1 // short break
            else -> 2                          // long quiet
        }
        if (obs.observed) stateCounts[dayState] += 1.0
        val stateTotal = stateCounts.sum()
        var entropy = 0.0
        if (stateTotal > 0) for (count in stateCounts) if (count > 0.0) {
            val p = count / stateTotal
            entropy -= p * ln(p)
        }
        x[1] = if (obs.observed && slot in 24 until Bins.PER_DAY && !obs.active && (obs.moved == true || sil < 16)) 1f else 0f
        x[2] = if (obs.observed && slot in 24 until Bins.PER_DAY && !obs.active && obs.moved != true && sil >= 16) 1f else 0f
        x[3] = if (obs.observed) 1f else 0f
        val next = t + 1
        val bod = Bins.binOfDay(next)
        val dow = Bins.dow(Bins.day(next), dowOffset)
        x[4] = sin(2 * PI * bod / Bins.PER_DAY).toFloat()
        x[5] = cos(2 * PI * bod / Bins.PER_DAY).toFloat()
        x[6] = sin(2 * PI * dow / 7).toFloat()
        x[7] = cos(2 * PI * dow / 7).toFloat()
        x[8] = if (slot in 24 until Bins.PER_DAY) 0f else 1f
        x[9] = if (obs.charging) 1f else 0f
        x[10] = (obs.battery / 100.0).toFloat()
        val r = DoubleArray(Bins.HORIZON) { k -> profile.rateAt(t + k + 1, dowOffset) }
        x[11] = r[0].toFloat()
        x[12] = (r.sliceArray(0 until 4).sum() / 4).toFloat()
        x[13] = (r.sliceArray(4 until 8).sum() / 4).toFloat()
        x[14] = (entropy / ln(4.0)).toFloat()
        x[15] = profile.confidence.toFloat()
        x[16] = if (obs.moved == true) 1f else 0f
        x[17] = if (obs.moved != null) 1f else 0f
        val q = quietQuantiles()
        x[18] = (ln1p(q.first.toDouble()) / LN_CAP).toFloat()
        x[19] = (ln1p(q.second.toDouble()) / LN_CAP).toFloat()
        x[20] = if (sessionStart) 1f else 0f
        previousBinT = obs.t
        previousBinObserved = obs.observed
        previousBinActive = obs.active
        return Row(x, FloatArray(Bins.HORIZON) { r[it].toFloat() })
    }

    private fun quietQuantiles(): Pair<Double, Double> {
        if (completedHistory.size < 5) return 16.0 to 32.0
        val sorted = completedHistory.sorted()
        fun q(p: Double): Double {
            val at = p * (sorted.size - 1); val lo = at.toInt(); val hi = min(lo + 1, sorted.lastIndex)
            return (sorted[lo] + (sorted[hi] - sorted[lo]) * (at - lo))
                .coerceIn(0.0, SILENCE_CAP.toDouble())
        }
        return q(.90) to q(.995)
    }

    internal fun save(w: StateWriter) {
        w.tag("features").intOrNull(lastInteractionT).intOrNull(lastActivityT)
            .intOrNull(historyDay).bool(lastWasActive).intOrNull(previousBinT)
            .bool(previousBinObserved).bool(previousBinActive).int(currentQuiet).ints(completedToday.toIntArray())
            .ints(completedHistory.toIntArray()).doubles(stateCounts)
    }
    internal fun load(r: StateReader) {
        r.tag("features"); lastInteractionT = r.intOrNull(); lastActivityT = r.intOrNull()
        historyDay = r.intOrNull(); lastWasActive = r.bool(); previousBinT = r.intOrNull()
        previousBinObserved = r.bool(); previousBinActive = r.bool(); currentQuiet = r.int()
        completedToday.clear(); completedToday.addAll(r.ints().toList())
        completedHistory.clear(); completedHistory.addAll(r.ints().toList())
        r.doubles().copyInto(stateCounts)
    }

    companion object {
        const val N = 21
        const val SILENCE_CAP = 192
        private val LN21 = ln(21.0)
        private val LN_CAP = ln1p(SILENCE_CAP.toDouble())
    }
}
