package app.mapa.core

import java.time.ZoneId

/** A battery reading taken at [atMs]. The phone keeps no battery history, so MaPa keeps its own. */
data class PowerSample(val atMs: Long, val battery: Int, val charging: Boolean)

/**
 * Battery and charging for backfilled bins. Before this, every backfilled bin got the reading
 * taken at tick time, so a night spent charging looked like a morning at 100% unplugged, and
 * training (which waits for charging) never saw the phone charge.
 */
object PowerTimeline {
    /**
     * One (charging, battery) per bin in [fromT, toT), read at each bin's end: charging from the
     * latest sample at or before that moment, battery interpolated between the samples around it.
     * Bins before the first sample use the first sample; with no samples at all, [current].
     */
    fun forBins(samples: List<PowerSample>, fromT: Int, toT: Int, zone: ZoneId, current: PowerSample): List<Pair<Boolean, Int>> {
        if (toT <= fromT) return emptyList()
        val s = (samples + current).sortedBy { it.atMs }
        return List(toT - fromT) { i ->
            val at = LocalBins.startMs(fromT + i + 1, zone) - 1
            val before = s.lastOrNull { it.atMs <= at }
            val after = s.firstOrNull { it.atMs > at }
            when {
                before == null -> after!!.charging to after.battery
                after == null || before.battery < 0 || after.battery < 0 -> before.charging to before.battery
                else -> {
                    val f = (at - before.atMs).toDouble() / (after.atMs - before.atMs)
                    before.charging to Math.round(before.battery + f * (after.battery - before.battery)).toInt()
                }
            }
        }
    }
}
