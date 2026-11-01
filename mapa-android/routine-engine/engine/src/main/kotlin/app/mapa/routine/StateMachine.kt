package app.mapa.routine

/** Everything the phone-side state machine needs for one tick. Built by [RoutineEngine]. */
data class StateInputs(
    val t: Int,
    val paused: Boolean,
    /** False when usage access is missing or the bin could not be observed. */
    val observed: Boolean,
    val lastInteractionT: Int?,
    val ruleUnusual: Boolean,
    val floorTriggered: Boolean,
    /** Null while the model is not ready to decide. */
    val modelUnusual: Boolean?,
    val decider: Decider,
    val expectedByT: Int? = null,
    val usualFirstActivityMinute: Int? = null,
    val confidence: Double? = null,
    val recurrenceWeeks: Double? = null,
    /** Last use or movement; silences run from here. Same as [lastInteractionT] without a movement signal. */
    val lastActivityT: Int? = lastInteractionT,
)

data class StateDecision(val state: RoutineState, val reason: Reason)

/**
 * Phone-side five-state machine. Precedence: paused, then monitoring, then activity,
 * then unusual, then quiet. Unusual latches for the rest of a silence stretch, so the
 * family never sees it flicker back to quiet without a new interaction.
 */
class PhoneStateMachine(private val activeWindowBins: Int = 2) {
    private var latchedStretch: Int? = null
    private var latchedReason: Reason.LongerThanUsual? = null

    fun evaluate(i: StateInputs): StateDecision {
        if (i.paused) return StateDecision(RoutineState.PAUSED_BY_PARENT, Reason.SharingPaused)
        if (!i.observed) return StateDecision(RoutineState.MONITORING_UNAVAILABLE, Reason.UsageAccessMissing)

        val last = i.lastInteractionT
        if (last != null && i.t - last < activeWindowBins) {
            latchedStretch = null
            latchedReason = null
            return StateDecision(RoutineState.ACTIVE, Reason.RecentInteraction(last))
        }
        // Movement alone is never Active (nobody touched the phone) but it does end a silence.
        val sign = i.lastActivityT
        if (sign != null && i.t - sign < activeWindowBins && sign != last) {
            return StateDecision(RoutineState.QUIET, Reason.RecentMovement(sign))
        }
        val stretch = sign ?: Int.MIN_VALUE
        if (latchedStretch == stretch) {
            return StateDecision(RoutineState.UNUSUAL_INACTIVITY, latchedReason!!)
        }

        val primary = when (i.decider) {
            Decider.RULES -> i.ruleUnusual
            Decider.MODEL -> i.modelUnusual ?: i.ruleUnusual
        }
        if (primary || i.floorTriggered) {
            val reason = Reason.LongerThanUsual(
                silentSinceT = sign ?: i.t,
                expectedByT = i.expectedByT,
                usualFirstActivityMinute = i.usualFirstActivityMinute,
                confidence = i.confidence,
                recurrenceWeeks = i.recurrenceWeeks,
                decidedBy = if (i.decider == Decider.MODEL && i.modelUnusual != null) Decider.MODEL else Decider.RULES,
                floorRule = !primary,
            )
            latchedStretch = stretch
            latchedReason = reason
            return StateDecision(RoutineState.UNUSUAL_INACTIVITY, reason)
        }
        return StateDecision(RoutineState.QUIET, Reason.WithinRoutine(i.expectedByT))
    }

    internal fun save(w: StateWriter) {
        w.tag("latch").intOrNull(latchedStretch).bool(latchedReason != null)
        latchedReason?.let { w.reason(it) }
    }

    internal fun load(r: StateReader) {
        r.tag("latch")
        latchedStretch = r.intOrNull()
        latchedReason = if (r.bool()) r.reason() else null
    }
}

data class AlertConfig(
    /** Rest period: unusual alerts raised here are held until it ends, if still relevant. */
    val restStartMinute: Int = 22 * 60,
    val restEndMinute: Int = 7 * 60,
    val unusualCooldownMinutes: Int = 180,
    val lowBatteryPercent: Int = 15,
) {
    fun inRest(t: Int): Boolean {
        val m = Bins.minuteOfDay(t)
        return if (restStartMinute <= restEndMinute) m in restStartMinute until restEndMinute
        else m >= restStartMinute || m < restEndMinute
    }
}

sealed interface Alert {
    val t: Int

    data class UnusualInactivity(override val t: Int, val reason: Reason.LongerThanUsual) : Alert
    data class LowBattery(override val t: Int, val battery: Int) : Alert
    data class MonitoringUnavailable(override val t: Int, val reason: Reason.NoFreshData) : Alert
}

/**
 * Phone-side alert gate: one unusual alert per silence stretch, a cooldown between
 * them, rest-period hold, and one low-battery warning per discharge.
 */
class AlertPolicy(private val config: AlertConfig = AlertConfig()) {
    private var alertedStretch: Int? = null
    private var lastUnusualAlertT: Int? = null
    private var lowBatteryWarned = false

    /** [lastActivityT] identifies the silence stretch: last use or movement. */
    fun onTick(t: Int, decision: StateDecision, lastActivityT: Int?, battery: Int, charging: Boolean): List<Alert> {
        val out = ArrayList<Alert>(1)
        if (decision.state == RoutineState.UNUSUAL_INACTIVITY) {
            val stretch = lastActivityT ?: Int.MIN_VALUE
            val cooled = lastUnusualAlertT?.let { (t - it) * Bins.MINUTES >= config.unusualCooldownMinutes } ?: true
            if (alertedStretch != stretch && cooled && !config.inRest(t)) {
                out += Alert.UnusualInactivity(t, decision.reason as Reason.LongerThanUsual)
                alertedStretch = stretch
                lastUnusualAlertT = t
            }
        }
        if (charging) lowBatteryWarned = false
        else if (battery in 0 until config.lowBatteryPercent && !lowBatteryWarned &&
            decision.state != RoutineState.PAUSED_BY_PARENT
        ) {
            out += Alert.LowBattery(t, battery)
            lowBatteryWarned = true
        }
        return out
    }

    internal fun save(w: StateWriter) {
        w.tag("alerts").intOrNull(alertedStretch).intOrNull(lastUnusualAlertT).bool(lowBatteryWarned)
    }

    internal fun load(r: StateReader) {
        r.tag("alerts"); alertedStretch = r.intOrNull(); lastUnusualAlertT = r.intOrNull(); lowBatteryWarned = r.bool()
    }
}

/** What the phone last synced to the server. */
data class SyncedSnapshot(
    val t: Int,
    val state: RoutineState,
    val reason: Reason,
    val lastInteractionT: Int?,
    val battery: Int,
    val charging: Boolean,
)

/**
 * Server/family side. Freshness is judged here, separately from inferred activity:
 * a stale snapshot is never shown as active or quiet.
 */
class FamilyStatusResolver(
    private val freshnessMinutes: Int = 60,
    private val staleAlertMinutes: Int = 120,
    /** Monitoring alerts due in this rest period wait until it ends, like unusual-inactivity alerts. Null: never hold. */
    private val rest: AlertConfig? = AlertConfig(),
) {
    private var staleAlertedForSync: Int? = null

    fun resolve(now: Int, last: SyncedSnapshot?): StateDecision {
        if (last != null && last.state == RoutineState.PAUSED_BY_PARENT) return StateDecision(last.state, last.reason)
        if (last == null || (now - last.t) * Bins.MINUTES > freshnessMinutes) {
            return StateDecision(
                RoutineState.MONITORING_UNAVAILABLE,
                Reason.NoFreshData(last?.t, last?.battery, last?.charging),
            )
        }
        return StateDecision(last.state, last.reason)
    }

    /**
     * One monitoring alert per outage, once data has been stale for [staleAlertMinutes]. Phones that
     * stop reporting overnight (Doze) would otherwise wake the family every night, so an alert that
     * comes due in the rest period is held and sent at its end if the data is still stale. The status
     * itself shows "no fresh data" throughout; only the notification waits.
     */
    fun staleAlert(now: Int, last: SyncedSnapshot?): Alert.MonitoringUnavailable? {
        if (last != null && last.state == RoutineState.PAUSED_BY_PARENT) return null
        val since = last?.t ?: return null
        if ((now - since) * Bins.MINUTES < staleAlertMinutes || staleAlertedForSync == since) return null
        if (rest?.inRest(now) == true) return null
        staleAlertedForSync = since
        return Alert.MonitoringUnavailable(now, Reason.NoFreshData(since, last.battery, last.charging))
    }
}
