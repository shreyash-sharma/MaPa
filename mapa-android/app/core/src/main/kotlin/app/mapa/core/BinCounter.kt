package app.mapa.core

import app.mapa.routine.BinObservation
import java.time.ZoneId

/**
 * Turns the collector's interaction timestamps (unlocks and app opens from Usage Access,
 * times only) into the engine's closed 15-minute bins.
 */
object BinCounter {
    /**
     * Observations for every bin in [fromT, toT). [observed] false (Usage Access missing)
     * marks every bin unobserved (-1); the engine then reports monitoring unavailable
     * instead of guessing. Battery and charging are the current readings, applied to the
     * backfilled bins too, since the phone keeps no battery history.
     */
    fun observations(
        interactionTimes: List<Long>,
        fromT: Int,
        toT: Int,
        zone: ZoneId,
        observed: Boolean,
        charging: Boolean,
        battery: Int,
        /** One value per bin from [MovementBins.moved], or null when there is no movement signal. */
        moved: List<Boolean?>? = null,
        /** Per-bin (charging, battery) from [PowerTimeline.forBins]; null applies [charging]/[battery] to all. */
        power: List<Pair<Boolean, Int>>? = null,
    ): List<BinObservation> {
        if (toT <= fromT) return emptyList()
        val counts = IntArray(toT - fromT)
        if (observed) {
            for (ms in interactionTimes) {
                val t = LocalBins.binAt(ms, zone)
                if (t in fromT until toT) counts[t - fromT]++
            }
        }
        return List(toT - fromT) { i ->
            val (c, b) = power?.getOrNull(i) ?: (charging to battery)
            BinObservation(fromT + i, if (observed) counts[i] else -1, c, b, moved?.getOrNull(i))
        }
    }
}
