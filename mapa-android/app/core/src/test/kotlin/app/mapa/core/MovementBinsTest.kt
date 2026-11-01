package app.mapa.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class MovementBinsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val t0 = LocalBins.binAt(1_760_000_000_000L, zone)
    private fun at(t: Int, minute: Int = 0) = LocalBins.startMs(t, zone) + minute * 60_000L

    @Test
    fun spanMarksEveryBinItOverlaps() {
        val spans = listOf(MoveSpan(at(t0 + 1, 10), at(t0 + 3, 5)))
        val moved = MovementBins.moved(spans, t0, t0 + 5, zone, availableSinceMs = at(t0 - 10), nowMs = at(t0 + 6))
        assertEquals(listOf(false, true, true, true, false), moved)
    }

    @Test
    fun binsBeforeTheSignalStartedAreUnknown() {
        val moved = MovementBins.moved(emptyList(), t0, t0 + 3, zone, availableSinceMs = at(t0 + 1, 5), nowMs = at(t0 + 4))
        assertEquals(listOf(null, null, false), moved)
    }

    @Test
    fun noPermissionMeansUnknownNotStill() {
        val moved = MovementBins.moved(emptyList(), t0, t0 + 2, zone, availableSinceMs = null, nowMs = at(t0 + 3))
        assertEquals(listOf(null, null), moved)
    }

    @Test
    fun openSpanIsCappedAtThreeHours() {
        val spans = listOf(MoveSpan(at(t0), null))
        val moved = MovementBins.moved(spans, t0, t0 + 16, zone, availableSinceMs = at(t0 - 1), nowMs = at(t0 + 40))
        assertEquals(12, moved.count { it == true }) // 3 hours = 12 bins
        assertEquals(false, moved.last())
    }

    @Test
    fun journalKeepsMovementAndReadsOldLines() {
        val codec = app.mapa.core.engine.JournalCodec
        for (m in listOf(true, false, null)) {
            val e = app.mapa.core.engine.JournalEntry.Bin(app.mapa.routine.BinObservation(100, 2, true, 80, m), false)
            assertEquals(e, codec.decode(codec.encode(e)))
        }
        val old = codec.decode("b 100 2 1 80 0") as app.mapa.core.engine.JournalEntry.Bin
        assertEquals(null, old.obs.moved)
    }

    @Test
    fun backfilledBinsGetThePowerTheyHad() {
        // Evening: 4% and plugged in at bin 0; morning reading at bin 6: 100%, unplugged.
        val evening = PowerSample(at(t0, 1), 4, true)
        val morning = PowerSample(at(t0 + 6, 10), 100, false)
        val p = PowerTimeline.forBins(listOf(evening), t0, t0 + 6, zone, current = morning)
        assertEquals(List(6) { true }, p.map { it.first }) // charging all night, not "unplugged"
        assertEquals(true, p.first().second < 30 && p.last().second > 80) // battery climbs, not 100% throughout
    }

    @Test
    fun noteFactsTravelWithoutNameOrLanguage() {
        val person = app.mapa.core.language.Person("Papa", app.mapa.core.language.Role.FATHER)
        val f = app.mapa.core.language.NoteFacts(
            person, app.mapa.core.language.Lang.EN, 0, 600, app.mapa.routine.RoutineState.ACTIVE,
            420, 450, 590, true, 9, 7.5, 64, true, false,
        )
        val wire = app.mapa.core.wire.NoteFactsWire.encode(f)
        assertEquals(false, wire.containsKey("name") || wire.values.any { it == "Papa" })
        val back = app.mapa.core.wire.NoteFactsWire.decode(wire, person, app.mapa.core.language.Lang.HI)
        assertEquals(f.copy(lang = app.mapa.core.language.Lang.HI), back)
    }
}
