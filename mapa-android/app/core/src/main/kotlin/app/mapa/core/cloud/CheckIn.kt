package app.mapa.core.cloud

import app.mapa.routine.LifeTagKind
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** What a language model proposes from a family member's words. A person confirms before it becomes a life tag. */
data class TagProposal(val kind: LifeTagKind, val startMs: Long, val endMs: Long)

/**
 * The family's own words become a structured life tag. The model never decides an alert: it only
 * proposes a kind and a time range, [parse] checks them against fixed limits, and a person taps to add.
 * Sent to the model: the family member's text, the current local time and the zone. Nothing else.
 */
object CheckIn {
    /** Reply kinds, in the model's words. "none" means the text does not describe a different day. */
    private val KINDS = mapOf(
        "travelling" to LifeTagKind.TRAVELLING,
        "guests" to LifeTagKind.GUESTS,
        "festival" to LifeTagKind.FESTIVAL,
        "unwell" to LifeTagKind.UNWELL,
        "away_from_phone" to LifeTagKind.OTHER,
    )

    const val MAX_INPUT_CHARS = 400
    private val MAX_LENGTH = Duration.ofDays(14)
    private val MAX_PAST = Duration.ofDays(3)
    private val MAX_FUTURE = Duration.ofDays(30)
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    val SCHEMA = """{"type":"object","additionalProperties":false,"required":["kind","start","end"],"properties":{""" +
        """"kind":{"type":"string","enum":["travelling","guests","festival","unwell","away_from_phone","none"]},""" +
        """"start":{"type":"string"},"end":{"type":"string"}}}"""

    fun prompt(text: String, now: ZonedDateTime): String = """
        A family member describes why their parent's phone looked different from the usual day.
        Reply with JSON only: kind, start, end.
        kind is one of: travelling (away from home for a trip), guests (visitors at home), festival (festival or function),
        unwell (the family says the parent is not well), away_from_phone (the parent left the phone at home or is out without it),
        none (the text does not describe a different day).
        start and end are local date-times like 2026-11-01T09:00 in the parent's time zone.
        If no end is given, choose a sensible end of the same day. Never choose an end more than 14 days after the start.
        Now: ${now.format(FORMAT)} (${now.zone.id}), ${now.dayOfWeek}.
        Text: ${MiniJson.quote(text.trim().take(MAX_INPUT_CHARS))}
    """.trimIndent()

    fun parse(reply: String, now: ZonedDateTime): TagProposal? {
        val json = runCatching { MiniJson.parse(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
        val kind = KINDS[MiniJson.path(json, "kind") as? String ?: return null] ?: return null
        val zone: ZoneId = now.zone
        val start = time(MiniJson.path(json, "start"), zone) ?: return null
        val end = time(MiniJson.path(json, "end"), zone) ?: return null
        val span = Duration.between(start, end)
        if (span.isNegative || span.isZero || span > MAX_LENGTH) return null
        if (start.isBefore(now.minus(MAX_PAST)) || start.isAfter(now.plus(MAX_FUTURE))) return null
        return TagProposal(kind, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
    }

    private fun time(v: Any?, zone: ZoneId): ZonedDateTime? = runCatching {
        LocalDateTime.parse((v as String).trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zone)
    }.getOrNull()
}
