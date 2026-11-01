package app.mapa.core.wire

import app.mapa.routine.Decider
import app.mapa.routine.Phase
import app.mapa.routine.Reason
import app.mapa.routine.RoutineState
import app.mapa.routine.SyncedSnapshot
import app.mapa.routine.TickResult
import app.mapa.routine.TrendReport

/**
 * Everything that leaves the parent's phone is built in this file, so the privacy promise
 * can be audited in one place: derived state, reason codes with times, battery, model
 * outputs and the AI note. No app names, no raw event streams, no location, no content.
 */
object Fields {
    const val V = "v"
    const val STATE = "state"
    const val REASON = "reason"
    const val T = "t"
    const val COMPUTED_AT = "computedAt"
    const val SYNCED_AT = "syncedAt"
    const val ZONE = "zone"
    const val LAST_INTERACTION_T = "lastInteractionT"
    const val LAST_INTERACTION_AT = "lastInteractionAt"
    const val BATTERY = "battery"
    const val CHARGING = "charging"
    const val DECIDER = "decider"
    const val MODEL_READY = "modelReady"
    const val MODEL_DECIDES = "modelDecides"
    const val MODEL = "model"
    const val SCORE = "score"
    const val EXPECTED_BY_T = "expectedByT"
    const val USUAL_FIRST_MINUTE = "usualFirstMinute"
    const val PHASE = "phase"
    const val DAY_NUMBER = "dayNumber"
    const val LAST_TRAINED_T = "lastTrainedT"
    const val NOTE = "note"
    const val NOTE_SOURCE = "noteSource"
    const val NOTE_LANG = "noteLang"
    const val NOTE_AT = "noteAt"
    /** The note's structured facts, so the family phone can write the note itself (Option C). */
    const val NOTE_FACTS = "noteFacts"
    const val APP_VERSION = "appVersion"
    const val RUNTIME = "runtime"
    const val PARAMS = "params"
    const val MODEL_BYTES = "modelBytes"
    const val STEP_MICROS = "stepMicros"
    const val STALE_ALERTED_FOR = "staleAlertedFor"
}

/** Parent-side context that the engine's [TickResult] does not carry. */
data class StatusExtras(
    val computedAtMs: Long,
    val zone: String,
    val lastInteractionAtMs: Long?,
    val phase: Phase,
    val dayNumber: Int,
    val model: String,
    val modelDecides: Boolean,
    val usualFirstMinute: Int?,
    val lastTrainedT: Int?,
    val note: String?,
    val noteSource: String?,
    val noteLang: String?,
    val noteAtMs: Long?,
    val appVersion: String,
    val noteFacts: Map<String, Any?>? = null,
    /** Facts about the model on the phone (not about the parent): runtime, size and speed. Null when unknown. */
    val runtime: String? = null,
    val params: Int? = null,
    val modelBytes: Long? = null,
    val stepMicros: Long? = null,
)

/** Decoded status document, as the family app and server see it. */
data class StatusDoc(
    val snapshot: SyncedSnapshot,
    val computedAtMs: Long,
    val zone: String,
    val lastInteractionAtMs: Long?,
    val decider: Decider,
    val modelReady: Boolean,
    val modelDecides: Boolean,
    val model: String,
    /** Calibrated 0..1, only present when the trained model is ready and allowed to decide. */
    val score: Double?,
    val expectedByT: Int?,
    val usualFirstMinute: Int?,
    val phase: Phase,
    val dayNumber: Int,
    val lastTrainedT: Int?,
    val note: String?,
    val noteSource: String?,
    val noteLang: String?,
    val noteAtMs: Long?,
    val noteFacts: Map<String, Any?>? = null,
    val runtime: String? = null,
    val params: Int? = null,
    val modelBytes: Long? = null,
    val stepMicros: Long? = null,
)

object StatusWire {
    const val VERSION = 1

    fun encode(r: TickResult, x: StatusExtras): Map<String, Any?> {
        val s = r.snapshot
        // While paused, only the fact of the pause is shared: no times, battery, note or forecast.
        if (s.state == RoutineState.PAUSED_BY_PARENT) return paused(r, x)
        val showScore = r.modelReady && x.modelDecides
        return mapOf(
            Fields.V to VERSION,
            Fields.STATE to s.state.name,
            Fields.REASON to ReasonWire.encode(s.reason, showScore),
            Fields.T to s.t,
            Fields.COMPUTED_AT to x.computedAtMs,
            Fields.ZONE to x.zone,
            Fields.LAST_INTERACTION_T to s.lastInteractionT,
            Fields.LAST_INTERACTION_AT to x.lastInteractionAtMs,
            Fields.BATTERY to s.battery,
            Fields.CHARGING to s.charging,
            Fields.DECIDER to r.decider.name,
            Fields.MODEL_READY to r.modelReady,
            Fields.MODEL_DECIDES to x.modelDecides,
            Fields.MODEL to x.model,
            Fields.SCORE to if (showScore) round3(r.score) else null,
            Fields.EXPECTED_BY_T to r.expectedByT,
            Fields.USUAL_FIRST_MINUTE to x.usualFirstMinute,
            Fields.PHASE to x.phase.name,
            Fields.DAY_NUMBER to x.dayNumber,
            Fields.LAST_TRAINED_T to x.lastTrainedT,
            Fields.NOTE to x.note,
            Fields.NOTE_SOURCE to x.noteSource,
            Fields.NOTE_LANG to x.noteLang,
            Fields.NOTE_AT to x.noteAtMs,
            Fields.NOTE_FACTS to x.noteFacts,
            Fields.APP_VERSION to x.appVersion,
            Fields.RUNTIME to x.runtime,
            Fields.PARAMS to x.params,
            Fields.MODEL_BYTES to x.modelBytes,
            Fields.STEP_MICROS to x.stepMicros,
        )
    }

    private fun paused(r: TickResult, x: StatusExtras): Map<String, Any?> = mapOf(
        Fields.V to VERSION,
        Fields.STATE to RoutineState.PAUSED_BY_PARENT.name,
        Fields.REASON to ReasonWire.encode(Reason.SharingPaused),
        Fields.T to r.snapshot.t,
        Fields.COMPUTED_AT to x.computedAtMs,
        Fields.ZONE to x.zone,
        Fields.DECIDER to r.decider.name,
        Fields.MODEL_READY to r.modelReady,
        Fields.MODEL_DECIDES to x.modelDecides,
        Fields.MODEL to x.model,
        Fields.PHASE to x.phase.name,
        Fields.DAY_NUMBER to x.dayNumber,
        Fields.APP_VERSION to x.appVersion,
    )

    /** Published the moment the parent taps Pause, before the next bin closes. */
    fun pausedNow(t: Int, nowMs: Long, zone: String, appVersion: String): Map<String, Any?> = mapOf(
        Fields.V to VERSION,
        Fields.STATE to RoutineState.PAUSED_BY_PARENT.name,
        Fields.REASON to ReasonWire.encode(Reason.SharingPaused),
        Fields.T to t,
        Fields.COMPUTED_AT to nowMs,
        Fields.ZONE to zone,
        Fields.APP_VERSION to appVersion,
    )

    fun decode(m: Map<String, Any?>): StatusDoc? = runCatching {
        val state = RoutineState.valueOf(m.str(Fields.STATE)!!)
        @Suppress("UNCHECKED_CAST")
        val reason = ReasonWire.decode(m[Fields.REASON] as Map<String, Any?>)!!
        val snap = SyncedSnapshot(
            m.int(Fields.T)!!, state, reason, m.int(Fields.LAST_INTERACTION_T),
            m.int(Fields.BATTERY) ?: -1, m.bool(Fields.CHARGING) ?: false,
        )
        StatusDoc(
            snapshot = snap,
            computedAtMs = m.long(Fields.COMPUTED_AT)!!,
            zone = m.str(Fields.ZONE) ?: "Asia/Kolkata",
            lastInteractionAtMs = m.long(Fields.LAST_INTERACTION_AT),
            decider = m.str(Fields.DECIDER)?.let { Decider.valueOf(it) } ?: Decider.RULES,
            modelReady = m.bool(Fields.MODEL_READY) ?: false,
            modelDecides = m.bool(Fields.MODEL_DECIDES) ?: false,
            model = m.str(Fields.MODEL) ?: "",
            score = m.double(Fields.SCORE),
            expectedByT = m.int(Fields.EXPECTED_BY_T),
            usualFirstMinute = m.int(Fields.USUAL_FIRST_MINUTE),
            phase = m.str(Fields.PHASE)?.let { Phase.valueOf(it) } ?: Phase.LEARNING,
            dayNumber = m.int(Fields.DAY_NUMBER) ?: 1,
            lastTrainedT = m.int(Fields.LAST_TRAINED_T),
            note = m.str(Fields.NOTE),
            noteSource = m.str(Fields.NOTE_SOURCE),
            noteLang = m.str(Fields.NOTE_LANG),
            noteAtMs = m.long(Fields.NOTE_AT),
            noteFacts = (m[Fields.NOTE_FACTS] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v },
            runtime = m.str(Fields.RUNTIME),
            params = m.int(Fields.PARAMS)?.takeIf { it > 0 },
            modelBytes = m.long(Fields.MODEL_BYTES)?.takeIf { it > 0 },
            stepMicros = m.long(Fields.STEP_MICROS)?.takeIf { it > 0 },
        )
    }.getOrNull()
}

object ReasonWire {
    /** [withConfidence] false strips the model confidence (fallback model, or model not ready). */
    fun encode(r: Reason, withConfidence: Boolean = true): Map<String, Any?> = when (r) {
        is Reason.RecentInteraction -> mapOf("code" to "recent", "lastInteractionT" to r.lastInteractionT)
        is Reason.WithinRoutine -> mapOf("code" to "routine", "expectedByT" to r.expectedByT)
        is Reason.RecentMovement -> mapOf("code" to "moved", "lastMovedT" to r.lastMovedT)
        is Reason.LongerThanUsual -> mapOf(
            "code" to "longer",
            "silentSinceT" to r.silentSinceT,
            "expectedByT" to r.expectedByT,
            "usualFirstMinute" to r.usualFirstActivityMinute,
            "confidence" to if (withConfidence) r.confidence?.let { round3(it) } else null,
            "recurrenceWeeks" to if (withConfidence) r.recurrenceWeeks?.let { round3(it) } else null,
            "decidedBy" to r.decidedBy.name,
            "floorRule" to r.floorRule,
        )
        is Reason.NoFreshData -> mapOf(
            "code" to "nofresh", "lastSyncT" to r.lastSyncT, "lastBattery" to r.lastBattery, "lastCharging" to r.lastCharging,
        )
        Reason.UsageAccessMissing -> mapOf("code" to "noaccess")
        Reason.SharingPaused -> mapOf("code" to "paused")
    }

    fun decode(m: Map<String, Any?>): Reason? = when (m.str("code")) {
        "recent" -> m.int("lastInteractionT")?.let { Reason.RecentInteraction(it) }
        "routine" -> Reason.WithinRoutine(m.int("expectedByT"))
        "moved" -> m.int("lastMovedT")?.let { Reason.RecentMovement(it) }
        "longer" -> Reason.LongerThanUsual(
            silentSinceT = m.int("silentSinceT") ?: 0,
            expectedByT = m.int("expectedByT"),
            usualFirstActivityMinute = m.int("usualFirstMinute"),
            confidence = m.double("confidence"),
            recurrenceWeeks = m.double("recurrenceWeeks"),
            decidedBy = m.str("decidedBy")?.let { Decider.valueOf(it) } ?: Decider.RULES,
            floorRule = m.bool("floorRule") ?: false,
        )
        "nofresh" -> Reason.NoFreshData(m.int("lastSyncT"), m.int("lastBattery"), m.bool("lastCharging"))
        "noaccess" -> Reason.UsageAccessMissing
        "paused" -> Reason.SharingPaused
        else -> null
    }
}

/** Today's activity for the rhythm card: one value per 15-minute bin, plus the usual band. */
data class DayDoc(
    val date: String,
    /** -1 not observed, 0 no interaction, 1 interaction. Counts are reduced to yes/no before upload. */
    val active: List<Int>,
    /** Probability of phone use in each bin for this parent on this kind of day, 0..1. */
    val usual: List<Double>,
    val firstActivityMinute: Int?,
)

object DayWire {
    fun encode(d: DayDoc): Map<String, Any?> = mapOf(
        "date" to d.date,
        "active" to d.active,
        "usual" to d.usual.map { round3(it) },
        "firstActivityMinute" to d.firstActivityMinute,
    )

    fun decode(m: Map<String, Any?>): DayDoc? = runCatching {
        DayDoc(
            m.str("date")!!,
            (m["active"] as List<*>).map { (it as Number).toInt() },
            (m["usual"] as List<*>).map { (it as Number).toDouble() },
            m.int("firstActivityMinute"),
        )
    }.getOrNull()
}

enum class AlertKind(val wire: String) { UNUSUAL("unusual"), LOW_BATTERY("lowBattery"), STALE("stale") }

/** One entry in "Why did this change?". [text] is pre-rendered in the parent's note language for push. */
data class EventDoc(
    val atMs: Long,
    val t: Int,
    val from: RoutineState?,
    val to: RoutineState,
    val reason: Reason,
    val text: String,
    val alert: AlertKind?,
)

object EventWire {
    fun encode(e: EventDoc): Map<String, Any?> = mapOf(
        "at" to e.atMs,
        "t" to e.t,
        "from" to e.from?.name,
        "to" to e.to.name,
        "reason" to ReasonWire.encode(e.reason),
        "text" to e.text,
        "alert" to e.alert?.wire,
        "by" to "phone",
    )

    fun decode(m: Map<String, Any?>): EventDoc? = runCatching {
        @Suppress("UNCHECKED_CAST")
        EventDoc(
            m.long("at")!!, m.int("t") ?: 0,
            m.str("from")?.let { RoutineState.valueOf(it) },
            RoutineState.valueOf(m.str("to")!!),
            ReasonWire.decode(m["reason"] as Map<String, Any?>)!!,
            m.str("text") ?: "",
            m.str("alert")?.let { a -> AlertKind.entries.firstOrNull { it.wire == a } },
        )
    }.getOrNull()
}

object TrendWire {
    fun encode(r: TrendReport): Map<String, Any?> = mapOf(
        "day" to r.day,
        "firstUseShiftMinutes" to r.firstUseShiftMinutes,
        "lastUseShiftMinutes" to r.lastUseShiftMinutes,
        "activityChange" to r.activityChange?.let { round3(it) },
        "notable" to r.notable,
    )

    fun decode(m: Map<String, Any?>): TrendReport? = runCatching {
        TrendReport(m.int("day")!!, m.int("firstUseShiftMinutes"), m.int("lastUseShiftMinutes"), m.double("activityChange"), m.bool("notable") ?: false)
    }.getOrNull()
}

internal fun round3(v: Double) = Math.round(v * 1000) / 1000.0
internal fun Map<String, Any?>.str(k: String) = this[k] as? String
internal fun Map<String, Any?>.int(k: String) = (this[k] as? Number)?.toInt()
internal fun Map<String, Any?>.long(k: String) = (this[k] as? Number)?.toLong()
internal fun Map<String, Any?>.double(k: String) = (this[k] as? Number)?.toDouble()
internal fun Map<String, Any?>.bool(k: String) = this[k] as? Boolean
