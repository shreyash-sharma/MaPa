package app.mapa.core

import java.time.ZoneId

/**
 * The second signal: whether the phone was carried around (walking, running, cycling, in a
 * vehicle), from Android Activity Recognition or iPhone Core Motion. Only times are kept, never
 * activity types or places.
 */
data class MoveSpan(val startMs: Long, val endMs: Long?)

object MovementBins {
    /** A span with no end (the EXIT never arrived) counts for at most this long. */
    const val OPEN_SPAN_CAP_MS = 3L * 60 * 60 * 1000

    /**
     * `moved` for every bin in [fromT, toT): true if any span overlaps the bin, false if the signal
     * was listening for the whole bin and saw nothing, null if it wasn't (no permission, not yet
     * registered when the bin started, or [availableSinceMs] null).
     */
    fun moved(spans: List<MoveSpan>, fromT: Int, toT: Int, zone: ZoneId, availableSinceMs: Long?, nowMs: Long): List<Boolean?> {
        if (toT <= fromT) return emptyList()
        return List(toT - fromT) { i ->
            val t = fromT + i
            val start = LocalBins.startMs(t, zone)
            val end = LocalBins.startMs(t + 1, zone)
            when {
                availableSinceMs == null || availableSinceMs > start -> null
                spans.any { s -> s.startMs < end && (s.endMs ?: minOf(nowMs, s.startMs + OPEN_SPAN_CAP_MS)) > start } -> true
                else -> false
            }
        }
    }
}
