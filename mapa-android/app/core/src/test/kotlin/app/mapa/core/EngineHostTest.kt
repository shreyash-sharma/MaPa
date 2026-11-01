package app.mapa.core

import app.mapa.core.demo.SyntheticParent
import app.mapa.core.engine.EngineFactory
import app.mapa.core.engine.EngineHost
import app.mapa.core.engine.JournalCodec
import app.mapa.core.engine.JournalEntry
import app.mapa.core.engine.MemoryJournalStore
import app.mapa.core.engine.MemorySnapshotStore
import app.mapa.routine.BinObservation
import app.mapa.routine.Bins
import app.mapa.routine.LifeTag
import app.mapa.routine.LifeTagKind
import app.mapa.routine.RoutineState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineHostTest {
    private val startDay = 20_381 // 2025-10-20, a Monday
    private val install = startDay * Bins.PER_DAY

    private fun feedDays(host: EngineHost, from: Int, n: Int) =
        host.feed(SyntheticParent(seed = 3).days(from, n).map { it to false })

    @Test
    fun `trained rhythm model is available to the tests`() {
        assertNotNull(TestModels.weights, "routine-engine/models/rhythm_v2.weights.txt not found")
    }

    @Test
    fun `journal codec round trips every entry`() {
        val entries = listOf(
            JournalEntry.Start(install, "rhythm_v2"),
            JournalEntry.Bin(BinObservation(install + 5, 3, true, 87), false),
            JournalEntry.Bin(BinObservation(install + 6, -1, false, 12), true),
            JournalEntry.Tag("abc", LifeTag(install, install + 96, LifeTagKind.GUESTS)),
            JournalEntry.Answer(true),
        )
        assertEquals(entries, entries.map { JournalCodec.decode(JournalCodec.encode(it)) })
    }

    @Test
    fun `restored host continues exactly like an uninterrupted one`() {
        val journal = MemoryJournalStore()
        val snaps = MemorySnapshotStore()
        val a = EngineHost(journal, snaps, TestModels.factory())
        a.start(install)
        feedDays(a, startDay, 10)

        // Same stores, new "process": restores from the snapshot, no replay.
        val b = EngineHost(journal, snaps, TestModels.factory())
        assertFalse(b.replayed)
        assertEquals(a.lastT, b.lastT)
        assertEquals(a.today()!!.second.toList(), b.today()!!.second.toList())

        val next = SyntheticParent(seed = 9).days(startDay + 10, 2).map { it to false }
        val ra = a.feed(next)
        val rb = b.feed(next)
        assertEquals(ra.map { it.decision }, rb.map { it.decision })
        assertEquals(ra.map { it.expectedByT }, rb.map { it.expectedByT })
        assertEquals(ra.map { it.score }, rb.map { it.score })
    }

    @Test
    fun `journal is replayed when the snapshot belongs to another model`() {
        val journal = MemoryJournalStore()
        val snaps = MemorySnapshotStore()
        val a = EngineHost(journal, snaps, EngineFactory(null))
        a.start(install)
        feedDays(a, startDay, 3)
        val b = EngineHost(journal, snaps, EngineFactory(TestModels.weights, modelId = "rhythm_v2"))
        if (TestModels.weights != null) assertTrue(b.replayed)
        assertEquals(a.lastT, b.lastT)
        assertNotNull(b.engine)
    }

    @Test
    fun `tags are applied once and bins never go backwards`() {
        val host = EngineHost(MemoryJournalStore(), MemorySnapshotStore(), TestModels.factory())
        assertNull(host.nextT)
        host.start(install)
        assertEquals(install, host.nextT)
        feedDays(host, startDay, 1)
        assertTrue(host.addTag("t1", LifeTag(install, install + 96, LifeTagKind.TRAVELLING)))
        assertFalse(host.addTag("t1", LifeTag(install, install + 96, LifeTagKind.TRAVELLING)))
        val again = host.feed(listOf(BinObservation(install + 3, 1, false, 50) to false))
        assertTrue(again.isEmpty())
        assertEquals(install + 96, host.nextT)
    }

    @Test
    fun `learns a morning routine and flags a silent morning`() {
        val host = EngineHost(MemoryJournalStore(), MemorySnapshotStore(), TestModels.factory())
        host.start(install)
        feedDays(host, startDay, 21)
        val engine = host.engine!!
        val day = startDay + 21
        val usual = engine.profile.usualFirstActivity(day, Bins.EPOCH_DOW)
        assertNotNull(usual)
        assertTrue(usual in 6 * 60 + 45..8 * 60 + 30, "usual first use $usual")

        // A weekday with no phone use at all after waking: by noon it must be unusual.
        val silent = (0 until 48).map { b -> BinObservation(day * Bins.PER_DAY + b, 0, false, 80) to false }
        val results = host.feed(silent)
        assertEquals(RoutineState.UNUSUAL_INACTIVITY, results.last().decision.state)
        assertNotNull(results.last().expectedByT ?: usual)
        val today = host.today()!!
        assertEquals(day, today.first)
        assertEquals(0, today.second.count { it == 1 })
    }

    @Test
    fun `reset forgets everything`() {
        val journal = MemoryJournalStore()
        val snaps = MemorySnapshotStore()
        val host = EngineHost(journal, snaps, TestModels.factory())
        host.start(install)
        feedDays(host, startDay, 1)
        host.reset()
        assertFalse(host.started)
        assertTrue(journal.entries.isEmpty())
        assertNull(snaps.text)
    }
}
