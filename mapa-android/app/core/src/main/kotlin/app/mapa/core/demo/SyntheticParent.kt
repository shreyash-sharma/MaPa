package app.mapa.core.demo

import app.mapa.routine.BinObservation
import app.mapa.routine.Bins
import kotlin.random.Random

/**
 * A plausible parent's phone day, for demos, tests and the debug "fill with sample days"
 * button. Not training data: the real prior comes from routine-engine/ml.
 * Weekdays start around 7:30, weekends about 40 minutes later, a quiet early afternoon,
 * last use around 22:15, charging overnight.
 */
class SyntheticParent(seed: Int = 7, private val wakeMinute: Int = 7 * 60 + 30) {
    private val rnd = Random(seed)

    fun days(startDay: Int, count: Int): List<BinObservation> = (startDay until startDay + count).flatMap { day(it) }

    fun day(day: Int): List<BinObservation> {
        val dow = Bins.dow(day, Bins.EPOCH_DOW)
        val weekend = Bins.daytype(dow) != 0
        val wake = wakeMinute + (if (weekend) 40 else 0) + rnd.nextInt(-20, 21)
        val sleep = 22 * 60 + 15 + rnd.nextInt(-30, 31)
        var battery = 100
        return (0 until Bins.PER_DAY).map { b ->
            val m = b * Bins.MINUTES
            val p = when {
                m < wake || m >= sleep -> 0.01
                m < wake + 30 -> 0.85
                m in 13 * 60 + 30 until 15 * 60 + 30 -> 0.12
                else -> 0.38
            }
            val n = if (rnd.nextDouble() < p) 1 + rnd.nextInt(4) else 0
            val charging = m >= sleep || m < wake - 30
            battery = if (charging) minOf(100, battery + 6) else maxOf(5, battery - if (n > 0) 2 else 1)
            BinObservation(day * Bins.PER_DAY + b, n, charging, battery)
        }
    }
}
