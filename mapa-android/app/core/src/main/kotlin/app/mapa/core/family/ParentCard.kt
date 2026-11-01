package app.mapa.core.family

import app.mapa.core.LocalBins
import app.mapa.core.language.Lang
import app.mapa.core.language.Note
import app.mapa.core.language.Person
import app.mapa.core.language.Phrases
import app.mapa.core.wire.DayDoc
import app.mapa.core.wire.StatusDoc
import app.mapa.routine.Bins
import app.mapa.routine.FamilyStatusResolver
import app.mapa.routine.Phase
import app.mapa.routine.Reason
import app.mapa.routine.RoutineState
import app.mapa.routine.StateDecision
import java.time.ZoneId

/** What the family sees for one parent. Built only from synced, derived data. */
data class ParentCard(
    val state: RoutineState,
    val stateLabel: String,
    val detail: String,
    val updatedAgo: String,
    /** True when the data is too old to describe now; the card then never shows an all-clear. */
    val stale: Boolean,
    val forecast: String?,
    val note: String?,
    val noteByAi: Boolean,
    val learning: String?,
    val modelLine: String,
    /** Model confidence that the current silence is unusual, 0..99, only when the model decided. */
    val confidencePct: Int?,
    val battery: Int?,
    val charging: Boolean?,
    val chart: Chart?,
    /** Minute of the day of the last phone use, today only, from fresh data. */
    val lastUseMinute: Int? = null,
    /** The learned usual first-use minute, only once the rhythm is past learning and the data is fresh. */
    val usualFirstMinute: Int? = null,
    /** Day N of the learning period, while learning. */
    val learningDay: Int? = null,
    val learningDays: Int = 14,
) {
    data class Chart(
        /** -1 not observed, 0 quiet, 1 used; 96 entries for today. */
        val active: List<Int>,
        /** Usual chance of phone use per bin, 0..1. */
        val usual: List<Double>,
        val nowBin: Int,
        val expectedByBin: Int?,
    )
}

object ParentCards {
    private const val LEARNING_DAYS = 14

    /**
     * [showPaused] false: the parent chose not to show that sharing is paused, so the family
     * sees only that updates are unavailable (still never an all-clear).
     */
    fun build(person: Person, status: StatusDoc?, day: DayDoc?, nowMs: Long, lang: Lang, showPaused: Boolean = true): ParentCard {
        val ph = Phrases(lang)
        val zone = runCatching { ZoneId.of(status?.zone ?: "Asia/Kolkata") }.getOrDefault(ZoneId.of("Asia/Kolkata"))
        val nowT = LocalBins.binAt(nowMs, zone)
        val resolved = FamilyStatusResolver().resolve(nowT, status?.snapshot)
        val decision = if (resolved.state == RoutineState.PAUSED_BY_PARENT && !showPaused) {
            StateDecision(RoutineState.MONITORING_UNAVAILABLE, Reason.NoFreshData(null, null, null))
        } else resolved
        val stale = decision.reason is Reason.NoFreshData
        val ageMin = status?.let { ((nowMs - it.computedAtMs) / 60_000).coerceAtLeast(0) }
        val todayKey = LocalBins.date(nowT).toString()
        val todayDay = day?.takeIf { it.date == todayKey }
        val usedToday = todayDay?.active?.any { it == 1 } ?: false
        val fresh = status != null && !stale
        // The card reuses the last synced decision for up to an hour, and its "usually by" time is
        // often only 15-45 minutes ahead of it. Once that time has passed, never show it.
        val reason = decision.reason.let { r ->
            if (r is Reason.WithinRoutine && (r.expectedByT ?: Int.MAX_VALUE) <= nowT) Reason.WithinRoutine(null) else r
        }
        val confidence = (reason as? Reason.LongerThanUsual)?.confidence
            ?.takeIf { fresh && status!!.modelDecides && !reason.floorRule }
            ?.let { (it * 100).toInt().coerceAtMost(99) }

        // The trained Hybrid decides from day one. "Learning" is then only a confidence word, never a wait:
        // the card shows the day, the forecast and alerts at once; learned times appear from day 3.
        val worksNow = status != null && status.modelReady && status.modelDecides
        val knowsTimes = status != null && (status.phase != Phase.LEARNING || (worksNow && status.dayNumber >= 3))
        return ParentCard(
            state = decision.state,
            stateLabel = ph.stateLabel(decision.state),
            detail = ph.detail(reason, person, nowT),
            updatedAgo = ageMin?.let { ph.updatedAgo(it) } ?: ph.noUpdatesYet(),
            stale = stale,
            forecast = if (fresh && decision.state in setOf(RoutineState.QUIET, RoutineState.UNUSUAL_INACTIVITY)) {
                ph.forecast(person, nowT, status!!.usualFirstMinute.takeIf { knowsTimes }, usedToday, status.expectedByT)
            } else null,
            note = status?.note?.takeIf { fresh && decision.state != RoutineState.PAUSED_BY_PARENT },
            noteByAi = status?.noteSource.let { it != null && it != Note.TEMPLATE },
            learning = status?.let {
                when {
                    it.phase == Phase.LEARNING && worksNow -> ph.gettingToKnow(person, it.dayNumber)
                    it.phase == Phase.LEARNING -> ph.learning(person, it.dayNumber, LEARNING_DAYS)
                    it.lastTrainedT != null -> ph.rhythmUpdated(it.lastTrainedT)
                    else -> null
                }
            },
            modelLine = ph.modelLine(status?.model ?: "", status?.modelReady ?: false, status?.modelDecides ?: false),
            confidencePct = confidence,
            battery = status?.snapshot?.battery?.takeIf { it >= 0 && decision.state != RoutineState.PAUSED_BY_PARENT },
            charging = status?.snapshot?.charging,
            lastUseMinute = status?.lastInteractionAtMs?.takeIf { fresh && decision.state != RoutineState.PAUSED_BY_PARENT }
                ?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }
                ?.takeIf { it.toLocalDate() == java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate() }
                ?.let { it.hour * 60 + it.minute },
            usualFirstMinute = status?.usualFirstMinute?.takeIf { fresh && knowsTimes && decision.state != RoutineState.PAUSED_BY_PARENT },
            // The "Day X of 14" box only for a build without the trained model (rules decide while it learns).
            learningDay = status?.takeIf { it.phase == Phase.LEARNING && !worksNow }?.dayNumber?.coerceIn(1, LEARNING_DAYS),
            learningDays = LEARNING_DAYS,
            chart = todayDay?.let {
                ParentCard.Chart(it.active, it.usual, Bins.binOfDay(nowT), status?.expectedByT?.takeIf { e -> e > nowT && Bins.day(e) == Bins.day(nowT) }?.let(Bins::binOfDay))
            },
        )
    }
}
