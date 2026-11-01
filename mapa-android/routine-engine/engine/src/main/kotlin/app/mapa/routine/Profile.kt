package app.mapa.routine

import kotlin.math.abs
import kotlin.math.min

/**
 * One closed day as the engine remembers it. Holds only derived numbers: per-bin
 * interaction counts (-1 unobserved) and, per tick, the backbone logits and the
 * profile rates it was fed, so the personal head can retrain without replaying the network.
 */
class DayRecord(val day: Int, val dow: Int) {
    val interactions = IntArray(Bins.PER_DAY) { -1 }
    /** Movement per bin: 1 moved, 0 still, -1 no signal. */
    val moved = IntArray(Bins.PER_DAY) { -1 }
    val logits = Array(Bins.PER_DAY) { FloatArray(0) }
    val rates = Array(Bins.PER_DAY) { FloatArray(0) }
    /** The day showed UNUSUAL_INACTIVITY (live or shadow) at least once. */
    var flaggedUnusual = false

    fun active(b: Int) = interactions[b] > 0
    fun observed(b: Int) = interactions[b] >= 0

    /** Sign of life per bin (used or moved): 1, 0, or -1 unknown. Profile, head and calibrator learn on this. */
    fun activity(b: Int): Int = if (interactions[b] > 0 || moved[b] == 1) 1 else if (interactions[b] >= 0) 0 else -1
    val coverage: Double get() = interactions.count { it >= 0 }.toDouble() / Bins.PER_DAY

    /** First interaction at or after 04:00, in minutes after midnight. */
    fun firstActivityMinute(): Int? {
        for (b in 16 until Bins.PER_DAY) if (active(b)) return b * Bins.MINUTES
        return null
    }

    fun lastActivityMinute(): Int? {
        for (b in Bins.PER_DAY - 1 downTo 16) if (active(b)) return b * Bins.MINUTES
        return null
    }

    fun activeBins(): Int = interactions.count { it > 0 }
}

/**
 * Personal probability that a 15-minute bin holds phone interaction, per day type,
 * blended toward the population prior while history is short. Mirrors
 * `ml/mapa_rhythm/features.py:Profile`.
 */
class Profile(
    val rate: Array<DoubleArray>,
    val nDays: Int,
    val usualFirstActivityMinute: IntArray?,
) {
    val confidence: Double get() = min(nDays, FULL_DAYS).toDouble() / FULL_DAYS

    fun rateAt(t: Int, dowOffset: Int): Double {
        val dt = Bins.daytype(Bins.dow(Bins.day(t), dowOffset))
        return rate[dt][Bins.binOfDay(t)]
    }

    fun usualFirstActivity(day: Int, dowOffset: Int): Int? =
        usualFirstActivityMinute?.get(Bins.daytype(Bins.dow(day, dowOffset)))?.takeIf { it >= 0 }

    /** Mean absolute difference of rates, the distance the anchor guard limits. */
    fun distanceTo(other: Profile): Double {
        var s = 0.0
        for (dt in 0 until 3) for (b in 0 until Bins.PER_DAY) s += abs(rate[dt][b] - other.rate[dt][b])
        return s / (3 * Bins.PER_DAY)
    }

    /** this + lambda * (target - this), used to hold back a too-large weekly step. */
    fun stepToward(target: Profile, lambda: Double): Profile = Profile(
        Array(3) { dt -> DoubleArray(Bins.PER_DAY) { b -> rate[dt][b] + lambda * (target.rate[dt][b] - rate[dt][b]) } },
        target.nDays,
        target.usualFirstActivityMinute,
    )

    companion object {
        const val M = 4.0
        const val M2 = 4.0
        const val FULL_DAYS = 28

        fun priorOnly(prior: Array<DoubleArray>) = build(emptyList(), prior)

        fun build(days: List<DayRecord>, prior: Array<DoubleArray>): Profile {
            val sumAll = DoubleArray(Bins.PER_DAY)
            val nAll = DoubleArray(Bins.PER_DAY)
            val sumDt = Array(3) { DoubleArray(Bins.PER_DAY) }
            val nDt = Array(3) { DoubleArray(Bins.PER_DAY) }
            for (d in days) {
                val s = smoothDay(d)
                val dt = Bins.daytype(d.dow)
                for (b in 0 until Bins.PER_DAY) {
                    val v = s[b]
                    if (v.isNaN()) continue
                    sumAll[b] += v; nAll[b] += 1.0
                    sumDt[dt][b] += v; nDt[dt][b] += 1.0
                }
            }
            val rate = Array(3) { dt ->
                DoubleArray(Bins.PER_DAY) { b ->
                    val pall = (sumAll[b] + M * prior[dt][b]) / (nAll[b] + M)
                    (sumDt[dt][b] + M2 * pall) / (nDt[dt][b] + M2)
                }
            }
            return Profile(rate, days.size, usualFirst(days))
        }

        private fun smoothDay(d: DayRecord): DoubleArray {
            val w = doubleArrayOf(0.25, 0.5, 0.25)
            return DoubleArray(Bins.PER_DAY) { b ->
                if (d.activity(b) < 0) Double.NaN else {
                    var num = 0.0
                    var den = 0.0
                    for (j in -1..1) {
                        val bb = Math.floorMod(b + j, Bins.PER_DAY)
                        if (d.activity(bb) >= 0) {
                            num += w[j + 1] * d.activity(bb)
                            den += w[j + 1]
                        }
                    }
                    num / den
                }
            }
        }

        private fun usualFirst(days: List<DayRecord>): IntArray? {
            if (days.isEmpty()) return null
            val all = days.mapNotNull { it.firstActivityMinute() }
            val allMedian = median(all) ?: return null
            return IntArray(3) { dt ->
                val own = days.filter { Bins.daytype(it.dow) == dt }.mapNotNull { it.firstActivityMinute() }
                if (own.size >= 3) median(own)!! else allMedian
            }
        }

        internal fun median(xs: List<Int>): Int? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
