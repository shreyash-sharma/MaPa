package app.mapa.routine

import kotlin.math.exp
import kotlin.math.ln

/**
 * Time is counted in 15-minute bins of local time. Bin `t` covers minutes
 * [t*15, t*15+15) after local midnight of day 0, and `dowOffset` is the weekday
 * of day 0 (0 = Monday). On a phone, day 0 is the local epoch day 1970-01-01, a Thursday.
 */
object Bins {
    const val PER_DAY = 96
    const val MINUTES = 15
    const val HORIZON = 16
    const val EPOCH_DOW = 3

    fun day(t: Int): Int = Math.floorDiv(t, PER_DAY)
    fun binOfDay(t: Int): Int = Math.floorMod(t, PER_DAY)
    fun minuteOfDay(t: Int): Int = binOfDay(t) * MINUTES
    fun dow(day: Int, dowOffset: Int): Int = Math.floorMod(day + dowOffset, 7)

    /** 0 = Monday to Friday, 1 = Saturday, 2 = Sunday. */
    fun daytype(dow: Int): Int = when (dow) {
        5 -> 1
        6 -> 2
        else -> 0
    }
}

/** What the telemetry collector reports for one closed 15-minute bin. */
data class BinObservation(
    val t: Int,
    /** Interaction events in the bin, or -1 when the phone could not observe (usage access lost, app not running). */
    val interactions: Int,
    val charging: Boolean,
    val battery: Int,
    /**
     * Movement (Activity Recognition on Android, Core Motion on iPhone): true if the phone was
     * walking, running, cycling or in a vehicle for any part of the bin, false if the sensor was
     * listening for the whole bin and saw none, null when there is no signal. Optional: the
     * engine works the same as before when it is always null.
     */
    val moved: Boolean? = null,
) {
    val observed: Boolean get() = interactions >= 0
    val active: Boolean get() = interactions > 0
    val moving: Boolean get() = moved == true

    /** Sign of life: the phone was used or moved with someone. Silence is counted between these. */
    val alive: Boolean get() = active || moving

    /** 1 alive, 0 seen and neither, -1 nothing known. Mirrors `features.activity_code`. */
    val activityCode: Int get() = if (alive) 1 else if (observed) 0 else -1
}

enum class RoutineState { ACTIVE, QUIET, UNUSUAL_INACTIVITY, MONITORING_UNAVAILABLE, PAUSED_BY_PARENT }

enum class Decider { RULES, MODEL }

/** Plain-language building blocks for the language layer. No medical or safety wording lives here. */
sealed interface Reason {
    data class RecentInteraction(val lastInteractionT: Int) : Reason
    /** Not used, but the phone moved (carried on a walk or in a car) within the last half hour. */
    data class RecentMovement(val lastMovedT: Int) : Reason
    data class WithinRoutine(val expectedByT: Int?) : Reason
    data class LongerThanUsual(
        val silentSinceT: Int,
        val expectedByT: Int?,
        val usualFirstActivityMinute: Int?,
        /** Calibrated share of this parent's ordinary silences that were less unusual, 0..1. */
        val confidence: Double?,
        /** A silence this unusual happens about once in this many weeks for this parent. */
        val recurrenceWeeks: Double?,
        val decidedBy: Decider,
        val floorRule: Boolean,
    ) : Reason
    data class NoFreshData(val lastSyncT: Int?, val lastBattery: Int?, val lastCharging: Boolean?) : Reason
    data object UsageAccessMissing : Reason
    data object SharingPaused : Reason
}

internal fun sigmoid(x: Double): Double = if (x >= 0) 1.0 / (1.0 + exp(-x)) else exp(x).let { it / (1.0 + it) }
internal fun logit(p: Double): Double = ln(p / (1.0 - p))
internal fun Double.clampProb(lo: Double = 0.002, hi: Double = 0.998) = coerceIn(lo, hi)
