package app.mapa.core

import app.mapa.core.family.ParentCards
import app.mapa.core.language.Lang
import app.mapa.core.language.Person
import app.mapa.core.language.Role
import app.mapa.core.pairing.InviteCodes
import app.mapa.core.wire.DayDoc
import app.mapa.core.wire.DayWire
import app.mapa.core.wire.EventDoc
import app.mapa.core.wire.EventWire
import app.mapa.core.wire.ReasonWire
import app.mapa.core.wire.StatusExtras
import app.mapa.core.wire.StatusWire
import app.mapa.core.wire.TagDoc
import app.mapa.core.wire.TagWire
import app.mapa.core.wire.AlertKind
import app.mapa.routine.Bins
import app.mapa.routine.Decider
import app.mapa.routine.LifeTagKind
import app.mapa.routine.Phase
import app.mapa.routine.Reason
import app.mapa.routine.RoutineState
import app.mapa.routine.StateDecision
import app.mapa.routine.SyncedSnapshot
import app.mapa.routine.TickResult
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireAndCardTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val mom = Person("Mom", Role.MOTHER)

    private fun ms(h: Int, m: Int) = LocalDateTime.of(2026, 10, 19, h, m).atZone(ist).toInstant().toEpochMilli()

    /** Mimics Firestore: whole numbers come back as Long, fractions as Double. */
    @Suppress("UNCHECKED_CAST")
    private fun firestore(v: Any?): Any? = when (v) {
        is Int -> v.toLong()
        is Map<*, *> -> v.mapValues { firestore(it.value) }
        is List<*> -> v.map { firestore(it) }
        else -> v
    }

    private fun tick(state: RoutineState, reason: Reason, atMs: Long, ready: Boolean = true): TickResult {
        val t = LocalBins.binAt(atMs, ist) - 1
        val snap = SyncedSnapshot(t, state, reason, t - 1, 54, false)
        return TickResult(t, StateDecision(state, reason), emptyList(), snap, Decider.MODEL, 0.97, false, true, ready, t + 3, null, false, null)
    }

    private fun extras(atMs: Long, note: String? = "Mom started later than usual today.") = StatusExtras(
        atMs, "Asia/Kolkata", atMs - 600_000, Phase.STEADY, 40, "rhythm_v2", true, 7 * 60 + 40, null,
        note, "gemini-nano", "en", atMs, "1.0",
    )

    @Test
    fun `status round trips through firestore types`() {
        val at = ms(9, 10)
        val reason = Reason.LongerThanUsual(LocalBins.binAt(ms(7, 5), ist), null, 8 * 60 + 30, 0.97, null, Decider.MODEL, false)
        @Suppress("UNCHECKED_CAST")
        val m = firestore(StatusWire.encode(tick(RoutineState.UNUSUAL_INACTIVITY, reason, at), extras(at))) as Map<String, Any?>
        val doc = StatusWire.decode(m)!!
        assertEquals(RoutineState.UNUSUAL_INACTIVITY, doc.snapshot.state)
        assertEquals(reason, doc.snapshot.reason)
        assertEquals(0.97, doc.score)
        assertEquals(Phase.STEADY, doc.phase)
    }

    @Test
    fun `confidence is stripped when the model is not ready`() {
        val reason = Reason.LongerThanUsual(100, null, 450, 0.5, null, Decider.RULES, false)
        val m = StatusWire.encode(tick(RoutineState.UNUSUAL_INACTIVITY, reason, ms(9, 0), ready = false), extras(ms(9, 0)))
        assertNull(m["score"])
        @Suppress("UNCHECKED_CAST")
        assertNull((m["reason"] as Map<String, Any?>)["confidence"])
    }

    @Test
    fun `other wire formats round trip`() {
        listOf(
            Reason.RecentInteraction(5), Reason.WithinRoutine(null), Reason.NoFreshData(3, 6, false),
            Reason.UsageAccessMissing, Reason.SharingPaused,
        ).forEach { r ->
            @Suppress("UNCHECKED_CAST")
            assertEquals(r, ReasonWire.decode(firestore(ReasonWire.encode(r)) as Map<String, Any?>))
        }
        val day = DayDoc("2026-10-19", List(96) { it % 3 - 1 }, List(96) { it / 100.0 }, 465)
        @Suppress("UNCHECKED_CAST")
        assertEquals(day, DayWire.decode(firestore(DayWire.encode(day)) as Map<String, Any?>))
        val ev = EventDoc(1L, 2, RoutineState.QUIET, RoutineState.UNUSUAL_INACTIVITY, Reason.WithinRoutine(7), "x", AlertKind.UNUSUAL)
        @Suppress("UNCHECKED_CAST")
        assertEquals(ev, EventWire.decode(firestore(EventWire.encode(ev)) as Map<String, Any?>))
        val tag = TagDoc("id", LifeTagKind.GUESTS, ms(0, 0), ms(23, 59), "family", "Asha")
        @Suppress("UNCHECKED_CAST")
        val back = TagWire.decode("id", firestore(TagWire.encode(tag)) as Map<String, Any?>)!!
        assertEquals(tag, back)
        val lt = back.toLifeTag(ist)
        assertEquals(96, lt.endT - lt.startT)
    }

    @Test
    fun `family card never shows an all-clear on stale data`() {
        val at = ms(8, 0)
        @Suppress("UNCHECKED_CAST")
        val status = StatusWire.decode(firestore(StatusWire.encode(tick(RoutineState.ACTIVE, Reason.RecentInteraction(LocalBins.binAt(ms(7, 50), ist)), at), extras(at))) as Map<String, Any?>)
        val fresh = ParentCards.build(mom, status, null, ms(8, 10), Lang.EN)
        assertEquals(RoutineState.ACTIVE, fresh.state)
        assertNotNull(fresh.note)
        val stale = ParentCards.build(mom, status, null, ms(11, 0), Lang.EN)
        assertEquals(RoutineState.MONITORING_UNAVAILABLE, stale.state)
        assertTrue(stale.stale)
        assertNull(stale.note)
        assertNull(stale.forecast)
        assertTrue(stale.detail.startsWith("We haven't heard from Mom's phone for 3 hours"), stale.detail)
        val none = ParentCards.build(mom, null, null, ms(11, 0), Lang.EN)
        assertEquals(RoutineState.MONITORING_UNAVAILABLE, none.state)
    }

    @Test
    fun `paused status shares nothing but the pause`() {
        val at = ms(8, 0)
        val m = StatusWire.encode(tick(RoutineState.PAUSED_BY_PARENT, Reason.SharingPaused, at), extras(at))
        assertNull(m["lastInteractionAt"])
        assertNull(m["battery"])
        assertNull(m["note"])
        assertEquals(RoutineState.PAUSED_BY_PARENT, StatusWire.decode(m)!!.snapshot.state)
    }

    @Test
    fun `model facts travel with the status, and a paused status leaves them out`() {
        val at = ms(8, 0)
        val x = extras(at).copy(runtime = "Kotlin", params = 5360, modelBytes = 61_000, stepMicros = 15)
        val doc = StatusWire.decode(firestore(StatusWire.encode(tick(RoutineState.ACTIVE, Reason.RecentInteraction(LocalBins.binAt(at, ist)), at), x)) as Map<String, Any?>)!!
        assertEquals("Kotlin", doc.runtime)
        assertEquals(5360, doc.params)
        assertEquals(61_000L, doc.modelBytes)
        assertEquals(15L, doc.stepMicros)
        val paused = StatusWire.encode(tick(RoutineState.PAUSED_BY_PARENT, Reason.SharingPaused, at), x)
        assertNull(paused["params"])
        assertNull(StatusWire.decode(StatusWire.encode(tick(RoutineState.ACTIVE, Reason.RecentInteraction(LocalBins.binAt(at, ist)), at), extras(at)))!!.params)
    }

    @Test
    fun `hidden pause shows as unavailable, not as quiet`() {
        val at = ms(8, 0)
        val status = StatusWire.decode(StatusWire.encode(tick(RoutineState.PAUSED_BY_PARENT, Reason.SharingPaused, at), extras(at, null)))
        assertEquals(RoutineState.PAUSED_BY_PARENT, ParentCards.build(mom, status, null, ms(8, 5), Lang.EN).state)
        assertEquals(RoutineState.MONITORING_UNAVAILABLE, ParentCards.build(mom, status, null, ms(8, 5), Lang.EN, showPaused = false).state)
    }

    @Test
    fun `invite codes are readable and normalised`() {
        repeat(50) {
            val c = InviteCodes.generate()
            assertEquals(c, InviteCodes.normalize(InviteCodes.display(c).lowercase()))
        }
        assertNull(InviteCodes.normalize("ABC10O"))
        assertEquals("ABC-234", InviteCodes.display("ABC234"))
    }

    @Test
    fun `chart covers today only`() {
        val at = ms(10, 0)
        val status = StatusWire.decode(StatusWire.encode(tick(RoutineState.QUIET, Reason.WithinRoutine(null), at), extras(at)))
        val today = DayDoc("2026-10-19", List(96) { if (it in 30..38) 1 else 0 }, List(96) { 0.2 }, 450)
        val card = ParentCards.build(mom, status, today, ms(10, 5), Lang.EN)
        assertEquals(40, card.chart!!.nowBin)
        assertNull(ParentCards.build(mom, status, today.copy(date = "2026-10-18"), ms(10, 5), Lang.EN).chart)
        assertEquals(Bins.PER_DAY, card.chart!!.active.size)
    }

    @Test
    fun `a usual time that has already passed is never shown`() {
        val at = ms(15, 0)
        val t = LocalBins.binAt(at, ist) - 1
        val expected = LocalBins.binAt(ms(15, 45), ist)
        val status = StatusWire.decode(StatusWire.encode(tick(RoutineState.QUIET, Reason.WithinRoutine(expected), at).copy(expectedByT = expected), extras(at)))
        val today = DayDoc("2026-10-19", List(96) { if (it in 30..50) 1 else 0 }, List(96) { 0.2 }, 450)
        val early = ParentCards.build(mom, status, today, ms(15, 10), Lang.EN)
        assertTrue(early.detail.contains("usually by 3:45 PM"), early.detail)
        assertNotNull(early.chart!!.expectedByBin)
        val late = ParentCards.build(mom, status, today, ms(15, 55), Lang.EN)
        assertEquals(RoutineState.QUIET, late.state)
        assertTrue(!late.detail.contains("3:45"), late.detail)
        assertNull(late.forecast)
        assertNull(late.chart!!.expectedByBin)
        assertTrue(t < expected)
    }
}
