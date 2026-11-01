package app.mapa.core

import app.mapa.routine.Bins
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Converts between wall-clock instants and the engine's 15-minute bins of *local* time
 * (see routine-engine API.md). Bin t covers local minutes [t*15, t*15+15) after local
 * midnight of 1970-01-01, so `Bins.EPOCH_DOW` is the weekday offset.
 */
object LocalBins {
    private const val BIN_SECONDS = Bins.MINUTES * 60L

    fun binAt(epochMs: Long, zone: ZoneId): Int {
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds
        return Math.floorDiv(Math.floorDiv(epochMs, 1000L) + offset, BIN_SECONDS).toInt()
    }

    /** Wall-clock start of bin [t] in [zone]. */
    fun startMs(t: Int, zone: ZoneId): Long =
        LocalDateTime.ofEpochSecond(t * BIN_SECONDS, 0, ZoneOffset.UTC).atZone(zone).toInstant().toEpochMilli()

    fun date(t: Int): LocalDate = LocalDate.ofEpochDay(Bins.day(t).toLong())

    fun firstBinOf(date: LocalDate): Int = (date.toEpochDay() * Bins.PER_DAY).toInt()

    fun minuteOfDayAt(epochMs: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(epochMs).atZone(zone).let { it.hour * 60 + it.minute }
}
