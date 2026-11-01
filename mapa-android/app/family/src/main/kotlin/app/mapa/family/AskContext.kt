package app.mapa.family

import app.mapa.core.cloud.AskFacts
import app.mapa.core.cloud.WeekDay
import app.mapa.core.family.ParentCards
import app.mapa.core.language.Lang
import app.mapa.core.language.NoteWriter
import app.mapa.core.language.Person
import app.mapa.core.language.Phrases
import app.mapa.core.wire.DayDoc
import app.mapa.core.wire.RoutineDoc
import app.mapa.core.wire.StatusDoc
import app.mapa.core.wire.TagDoc
import app.mapa.routine.TrendReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns what the family app already holds into the name-free week summary Ask MaPa may use.
 * The parent's name never appears: wording that needs it carries [NoteWriter.NAME_TOKEN].
 */
object AskContext {
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun build(
        person: Person, status: StatusDoc?, week: List<DayDoc>, tags: List<TagDoc>, trend: TrendReport?,
        routine: RoutineDoc?, zone: ZoneId, lang: Lang, nowMs: Long,
    ): AskFacts {
        val shielded = person.copy(name = NoteWriter.NAME_TOKEN)
        val ph = Phrases(lang)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone)
        val card = ParentCards.build(shielded, status, week.firstOrNull { it.date == today.toLocalDate().toString() }, nowMs, lang)
        val days = week.map { d ->
            val date = LocalDate.parse(d.date)
            val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            WeekDay(
                label = if (date == today.toLocalDate()) "Today so far" else date.format(DAY),
                firstUseMinute = d.firstActivityMinute,
                activeBins = d.active.count { it == 1 },
                observedBins = d.active.count { it != -1 },
                tags = tags.filter { it.startMs < to && it.endMs > from }.map { it.kind.name.lowercase().replace('_', ' ') }.distinct(),
            )
        }
        return AskFacts(
            lang = lang,
            nowMinute = today.hour * 60 + today.minute,
            stale = card.stale,
            stateLabel = card.stateLabel,
            usualFirstMinute = status?.usualFirstMinute,
            days = days,
            trendLines = trend?.let { ph.trend(shielded, it) } ?: emptyList(),
            routineQuestion = routine?.takeIf { it.answer == null }?.let { ph.routineQuestion(shielded, FamilyRepo.question(it)) },
            battery = status?.snapshot?.battery ?: -1,
        )
    }
}
