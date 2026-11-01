package app.mapa.core

import app.mapa.routine.Bins
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalBinsTest {
    private val ist = ZoneId.of("Asia/Kolkata")

    @Test
    fun `IST wall time maps to local bins`() {
        val ms = LocalDateTime.of(2026, 10, 19, 7, 40).atZone(ist).toInstant().toEpochMilli()
        val t = LocalBins.binAt(ms, ist)
        assertEquals(7 * 4 + 2, Bins.binOfDay(t)) // 07:30-07:45
        assertEquals("2026-10-19", LocalBins.date(t).toString())
        assertEquals(0, Bins.dow(Bins.day(t), Bins.EPOCH_DOW)) // a Monday
        assertEquals(LocalDateTime.of(2026, 10, 19, 7, 30).atZone(ist).toInstant().toEpochMilli(), LocalBins.startMs(t, ist))
    }

    @Test
    fun `bin counter buckets interactions and marks unobserved`() {
        val start = LocalDateTime.of(2026, 10, 19, 9, 0).atZone(ist).toInstant().toEpochMilli()
        val t0 = LocalBins.binAt(start, ist)
        val times = listOf(start + 60_000, start + 120_000, start + 20 * 60_000)
        val obs = BinCounter.observations(times, t0, t0 + 3, ist, observed = true, charging = false, battery = 50)
        assertEquals(listOf(2, 1, 0), obs.map { it.interactions })
        val blind = BinCounter.observations(times, t0, t0 + 3, ist, observed = false, charging = false, battery = 50)
        assertEquals(listOf(-1, -1, -1), blind.map { it.interactions })
    }
}
