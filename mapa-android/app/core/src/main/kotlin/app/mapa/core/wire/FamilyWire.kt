package app.mapa.core.wire

import app.mapa.core.LocalBins
import app.mapa.routine.LifeTag
import app.mapa.routine.LifeTagKind
import java.time.ZoneId

/** A life tag as stored in parents/{pid}/tags. Times are wall-clock; the phone converts to its bins. */
data class TagDoc(
    val id: String,
    val kind: LifeTagKind,
    val startMs: Long,
    val endMs: Long,
    val byRole: String,
    val byName: String?,
) {
    fun toLifeTag(zone: ZoneId): LifeTag =
        LifeTag(LocalBins.binAt(startMs, zone), LocalBins.binAt(endMs - 1, zone) + 1, kind)
}

object TagWire {
    /** Kinds a person can pick. CHECK_IN and OTHER are reserved for the voice check-in in release 2. */
    val PICKABLE = listOf(LifeTagKind.UNWELL, LifeTagKind.TRAVELLING, LifeTagKind.GUESTS, LifeTagKind.FESTIVAL)

    fun encode(t: TagDoc): Map<String, Any?> = mapOf(
        "kind" to t.kind.name, "startAt" to t.startMs, "endAt" to t.endMs, "byRole" to t.byRole, "byName" to t.byName,
    )

    fun decode(id: String, m: Map<String, Any?>): TagDoc? = runCatching {
        TagDoc(id, LifeTagKind.valueOf(m.str("kind")!!), m.long("startAt")!!, m.long("endAt")!!, m.str("byRole") ?: "family", m.str("byName"))
    }.getOrNull()
}

/** parents/{pid}/routine/current: the new-routine question and the family's answer. */
data class RoutineDoc(
    val shiftMinutes: Int,
    val weeks: Int,
    val sinceDay: Int,
    val askedAtMs: Long,
    /** "yes", "no", or null while unanswered. */
    val answer: String?,
    val answeredBy: String?,
    val appliedAtMs: Long?,
)

object RoutineWire {
    fun encode(r: RoutineDoc): Map<String, Any?> = mapOf(
        "shiftMinutes" to r.shiftMinutes, "weeks" to r.weeks, "sinceDay" to r.sinceDay, "askedAt" to r.askedAtMs,
        "answer" to r.answer, "answeredBy" to r.answeredBy, "appliedAt" to r.appliedAtMs,
    )

    fun decode(m: Map<String, Any?>): RoutineDoc? = runCatching {
        RoutineDoc(
            m.int("shiftMinutes")!!, m.int("weeks")!!, m.int("sinceDay")!!, m.long("askedAt") ?: 0L,
            m.str("answer"), m.str("answeredBy"), m.long("appliedAt"),
        )
    }.getOrNull()
}

/** parents/{pid}: the parent's own settings. Only the parent's phone writes it, except noteLang. */
data class ParentProfile(
    val name: String,
    val role: String,
    val lang: String,
    val zone: String,
    val paused: Boolean,
    /** Whether the family may see that sharing is paused (brief: "if the parent allows it"). */
    val showPaused: Boolean,
)

object ParentWire {
    fun encode(p: ParentProfile): Map<String, Any?> = mapOf(
        "name" to p.name, "role" to p.role, "lang" to p.lang, "zone" to p.zone, "paused" to p.paused, "showPaused" to p.showPaused,
    )

    fun decode(m: Map<String, Any?>): ParentProfile? = runCatching {
        ParentProfile(
            m.str("name")!!, m.str("role") ?: "other", m.str("lang") ?: "en", m.str("zone") ?: "Asia/Kolkata",
            m.bool("paused") ?: false, m.bool("showPaused") ?: true,
        )
    }.getOrNull()
}
