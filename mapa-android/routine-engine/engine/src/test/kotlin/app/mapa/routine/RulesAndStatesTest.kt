package app.mapa.routine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bin at hh:mm on day d. */
fun at(d: Int, hh: Int, mm: Int = 0) = d * Bins.PER_DAY + (hh * 60 + mm) / Bins.MINUTES

fun silent(t: Int, battery: Int = 80) = BinObservation(t, 0, false, battery)
fun used(t: Int, battery: Int = 80) = BinObservation(t, 3, false, battery)
fun unseen(t: Int) = BinObservation(t, -1, false, 80)
fun carried(t: Int) = BinObservation(t, 0, false, 80, moved = true)

class RuleBaselineTest {
    @Test
    fun countsOnlyObservedAwakeSilence() {
        val r = RuleBaseline(RuleConfig(inactivityMinutes = 60, floorMinutes = 120))
        r.observe(used(at(0, 21, 30)))
        for (t in at(0, 21, 45) until at(1, 7)) r.observe(silent(t))
        // 21:45 to 22:00 is the only awake silence overnight
        assertEquals(15, r.silentAwakeMinutes)
        assertFalse(r.unusual)
        for (t in at(1, 7) until at(1, 7, 45)) r.observe(silent(t))
        assertEquals(60, r.silentAwakeMinutes)
        assertTrue(r.unusual)
        assertFalse(r.floorTriggered)
        r.observe(unseen(at(1, 7, 45)))
        assertEquals(60, r.silentAwakeMinutes, "unobserved bins are not evidence of silence")
        for (t in at(1, 8) until at(1, 9)) r.observe(silent(t))
        assertTrue(r.floorTriggered)
        r.observe(used(at(1, 9)))
        assertEquals(0, r.silentAwakeMinutes)
    }

    @Test
    fun movementEndsASilenceAndMissingMovementChangesNothing() {
        val r = RuleBaseline(RuleConfig(inactivityMinutes = 60, floorMinutes = 120))
        r.observe(used(at(1, 9)))
        for (t in at(1, 9, 15) until at(1, 10)) r.observe(silent(t).copy(moved = false))
        assertEquals(45, r.silentAwakeMinutes)
        r.observe(carried(at(1, 10)))
        assertEquals(0, r.silentAwakeMinutes, "carried on a walk counts as a sign of life")
        // without a movement signal (null) it behaves exactly as before
        val a = RuleBaseline(RuleConfig()); val b = RuleBaseline(RuleConfig())
        for (t in at(2, 7) until at(2, 20)) {
            val o = if (t % 7 == 0) used(t) else silent(t)
            a.observe(o); b.observe(o.copy(moved = null))
            assertEquals(a.silentAwakeMinutes, b.silentAwakeMinutes)
        }
    }

    @Test
    fun quietHoursMayNotWrapMidnight() {
        val c = RuleConfig(quietStartMinute = 13 * 60, quietEndMinute = 15 * 60)
        assertTrue(c.isQuiet(at(3, 14)))
        assertFalse(c.isQuiet(at(3, 15)))
        assertFalse(c.isQuiet(at(3, 2)))
    }
}

class PhoneStateMachineTest {
    private fun inputs(
        t: Int = at(5, 12), paused: Boolean = false, observed: Boolean = true, last: Int? = at(5, 9),
        rule: Boolean = false, floor: Boolean = false, model: Boolean? = false, decider: Decider = Decider.MODEL,
    ) = StateInputs(t, paused, observed, last, rule, floor, model, decider)

    @Test
    fun precedence() {
        val sm = PhoneStateMachine()
        assertEquals(RoutineState.PAUSED_BY_PARENT, sm.evaluate(inputs(paused = true, observed = false, rule = true)).state)
        assertEquals(RoutineState.MONITORING_UNAVAILABLE, sm.evaluate(inputs(observed = false, rule = true, floor = true)).state)
        assertEquals(RoutineState.ACTIVE, sm.evaluate(inputs(last = at(5, 12), model = true)).state)
        assertEquals(RoutineState.QUIET, sm.evaluate(inputs()).state)
        assertEquals(RoutineState.UNUSUAL_INACTIVITY, sm.evaluate(inputs(model = true)).state)
    }

    @Test
    fun modelDecidesWhenReadyRulesOtherwise() {
        assertEquals(RoutineState.QUIET, PhoneStateMachine().evaluate(inputs(rule = true, model = false)).state)
        assertEquals(RoutineState.UNUSUAL_INACTIVITY, PhoneStateMachine().evaluate(inputs(rule = true, model = null)).state)
        assertEquals(
            RoutineState.UNUSUAL_INACTIVITY,
            PhoneStateMachine().evaluate(inputs(rule = true, model = false, decider = Decider.RULES)).state,
        )
    }

    @Test
    fun floorFiresWhateverTheModelSays() {
        val d = PhoneStateMachine().evaluate(inputs(floor = true, model = false))
        assertEquals(RoutineState.UNUSUAL_INACTIVITY, d.state)
        assertTrue((d.reason as Reason.LongerThanUsual).floorRule)
    }

    @Test
    fun unusualLatchesUntilNextInteraction() {
        val sm = PhoneStateMachine()
        assertEquals(RoutineState.UNUSUAL_INACTIVITY, sm.evaluate(inputs(t = at(5, 12), model = true)).state)
        assertEquals(RoutineState.UNUSUAL_INACTIVITY, sm.evaluate(inputs(t = at(5, 12, 15), model = false)).state)
        assertEquals(RoutineState.ACTIVE, sm.evaluate(inputs(t = at(5, 12, 30), last = at(5, 12, 30))).state)
        assertEquals(RoutineState.QUIET, sm.evaluate(inputs(t = at(5, 13), last = at(5, 12, 30), model = false)).state)
    }
}

class MovementStateTest {
    @Test
    fun movementAloneIsQuietNotActiveAndEndsTheLatch() {
        val sm = PhoneStateMachine()
        fun inp(t: Int, lastUse: Int, lastSign: Int, model: Boolean) =
            StateInputs(t, false, true, lastUse, false, false, model, Decider.MODEL, lastActivityT = lastSign)
        assertEquals(RoutineState.UNUSUAL_INACTIVITY, sm.evaluate(inp(at(5, 12), at(5, 8), at(5, 8), true)).state)
        val d = sm.evaluate(inp(at(5, 12, 15), at(5, 8), at(5, 12, 15), false))
        assertEquals(RoutineState.QUIET, d.state)
        assertEquals(Reason.RecentMovement(at(5, 12, 15)), d.reason)
        assertEquals(RoutineState.QUIET, sm.evaluate(inp(at(5, 13), at(5, 8), at(5, 12, 15), false)).state)
        val u = sm.evaluate(inp(at(5, 17), at(5, 8), at(5, 12, 15), true))
        assertEquals(at(5, 12, 15), (u.reason as Reason.LongerThanUsual).silentSinceT, "silence runs from the last movement")
    }
}

class AlertPolicyTest {
    private val unusual = StateDecision(
        RoutineState.UNUSUAL_INACTIVITY,
        Reason.LongerThanUsual(0, null, null, 0.99, 12.0, Decider.MODEL, false),
    )
    private val quiet = StateDecision(RoutineState.QUIET, Reason.WithinRoutine(null))

    @Test
    fun oneAlertPerSilence() {
        val p = AlertPolicy()
        val last = at(2, 8)
        assertEquals(1, p.onTick(at(2, 13), unusual, last, 80, false).size)
        assertEquals(0, p.onTick(at(2, 13, 15), unusual, last, 80, false).size)
        assertEquals(0, p.onTick(at(2, 18), unusual, last, 80, false).size)
    }

    @Test
    fun restPeriodHoldsAlertUntilItEnds() {
        val p = AlertPolicy(AlertConfig(restStartMinute = 22 * 60, restEndMinute = 7 * 60))
        val last = at(2, 18)
        assertTrue(p.onTick(at(3, 5), unusual, last, 80, false).isEmpty())
        val a = p.onTick(at(3, 7), unusual, last, 80, false)
        assertIs<Alert.UnusualInactivity>(a.single())
    }

    @Test
    fun cooldownBetweenSilences() {
        val p = AlertPolicy(AlertConfig(unusualCooldownMinutes = 180))
        assertEquals(1, p.onTick(at(2, 10), unusual, at(2, 8), 80, false).size)
        assertEquals(0, p.onTick(at(2, 11), unusual, at(2, 10, 30), 80, false).size)
        assertEquals(1, p.onTick(at(2, 13), unusual, at(2, 10, 30), 80, false).size)
    }

    @Test
    fun lowBatteryOncePerDischarge() {
        val p = AlertPolicy()
        assertIs<Alert.LowBattery>(p.onTick(at(1, 10), quiet, at(1, 9), 12, false).single())
        assertTrue(p.onTick(at(1, 11), quiet, at(1, 9), 10, false).isEmpty())
        p.onTick(at(1, 12), quiet, at(1, 9), 40, true)
        assertEquals(1, p.onTick(at(2, 12), quiet, at(2, 9), 9, false).size)
    }
}

class FamilyStatusResolverTest {
    private fun snap(t: Int, state: RoutineState = RoutineState.QUIET) =
        SyncedSnapshot(t, state, Reason.WithinRoutine(null), t - 4, 6, false)

    @Test
    fun staleDataIsNeverShownAsActiveOrQuiet() {
        val r = FamilyStatusResolver(freshnessMinutes = 60)
        assertEquals(RoutineState.QUIET, r.resolve(at(1, 10, 45), snap(at(1, 10))).state)
        val stale = r.resolve(at(1, 11, 15), snap(at(1, 10), RoutineState.ACTIVE))
        assertEquals(RoutineState.MONITORING_UNAVAILABLE, stale.state)
        assertEquals(6, (stale.reason as Reason.NoFreshData).lastBattery)
        assertEquals(RoutineState.MONITORING_UNAVAILABLE, r.resolve(at(1, 10), null).state)
    }

    @Test
    fun pauseIsRespectedEvenWhenStale() {
        val r = FamilyStatusResolver()
        assertEquals(RoutineState.PAUSED_BY_PARENT, r.resolve(at(2, 10), snap(at(1, 10), RoutineState.PAUSED_BY_PARENT)).state)
        assertNull(r.staleAlert(at(2, 10), snap(at(1, 10), RoutineState.PAUSED_BY_PARENT)))
    }

    @Test
    fun oneStaleAlertPerOutage() {
        val r = FamilyStatusResolver(staleAlertMinutes = 120)
        val s = snap(at(1, 10))
        assertNull(r.staleAlert(at(1, 11), s))
        assertTrue(r.staleAlert(at(1, 12), s) != null)
        assertNull(r.staleAlert(at(1, 13), s))
        assertTrue(r.staleAlert(at(1, 16), snap(at(1, 13))) != null)
    }
}
