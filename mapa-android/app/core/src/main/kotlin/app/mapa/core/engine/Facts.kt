package app.mapa.core.engine

import app.mapa.core.LocalBins
import app.mapa.core.language.Lang
import app.mapa.core.language.NoteFacts
import app.mapa.core.language.Person
import app.mapa.routine.Bins
import app.mapa.routine.Phase
import app.mapa.routine.RoutineState
import java.time.Instant
import java.time.ZoneId

/** Builds the language layer's [NoteFacts] from the engine's current view of the day. */
object FactsBuilder {
    /** Bins before 04:00 don't count as the start of the day, matching the engine's DayRecord. */
    private const val DAY_START_BIN = 16

    fun build(
        host: EngineHost,
        person: Person,
        lang: Lang,
        nowMs: Long,
        zone: ZoneId,
        state: RoutineState,
        lastInteractionAtMs: Long?,
        battery: Int,
        charging: Boolean,
    ): NoteFacts? {
        val engine = host.engine ?: return null
        val nowT = LocalBins.binAt(nowMs, zone)
        val day = Bins.day(nowT)
        val bins = host.today()?.takeIf { it.first == day }?.second ?: IntArray(Bins.PER_DAY) { -1 }
        val first = (DAY_START_BIN until Bins.PER_DAY).firstOrNull { bins[it] == 1 }?.let { it * Bins.MINUTES }
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val lastToday = lastInteractionAtMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } ?: false
        val usualSoFar = (day * Bins.PER_DAY until nowT).sumOf { engine.profile.rateAt(it, Bins.EPOCH_DOW) }
        return NoteFacts(
            person = person,
            lang = lang,
            weekday = Bins.dow(day, Bins.EPOCH_DOW),
            nowMinute = LocalBins.minuteOfDayAt(nowMs, zone),
            state = state,
            firstActivityTodayMinute = first,
            usualFirstMinute = engine.profile.usualFirstActivity(day, Bins.EPOCH_DOW),
            lastInteractionMinute = lastInteractionAtMs?.let { LocalBins.minuteOfDayAt(it, zone) },
            lastInteractionToday = lastToday,
            activeBinsToday = bins.count { it == 1 },
            usualActiveBinsSoFar = usualSoFar,
            battery = battery,
            charging = charging,
            learning = engine.scheduler.phase(day) == Phase.LEARNING,
        )
    }

    /** Today's usual band for the chart: the personal profile's chance of phone use per bin. */
    fun usualBand(host: EngineHost, day: Int): List<Double> {
        val engine = host.engine ?: return List(Bins.PER_DAY) { 0.0 }
        return List(Bins.PER_DAY) { b -> engine.profile.rateAt(day * Bins.PER_DAY + b, Bins.EPOCH_DOW) }
    }
}
