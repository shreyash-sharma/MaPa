package app.mapa.core.cloud

import app.mapa.core.language.ClaimsGuard
import app.mapa.core.language.Clock
import app.mapa.core.language.Lang
import app.mapa.core.language.Note
import app.mapa.core.language.NoteWriter
import app.mapa.core.language.TextModel

/** One finished day of phone activity, as the parent's phone already shares it. No app names, no places. */
data class WeekDay(
    /** Local day name and date, e.g. "Mon 26 Oct". */
    val label: String,
    val firstUseMinute: Int?,
    /** 15-minute periods with phone use. */
    val activeBins: Int,
    /** Periods the phone could observe. Fewer than 96 means part of the day is unknown. */
    val observedBins: Int,
    /** Life tags on that day, in words: "travelling", "guests"... */
    val tags: List<String>,
)

/** Everything Ask MaPa may use to answer. Built from synced, derived data; it holds no name. */
data class AskFacts(
    val lang: Lang,
    val nowMinute: Int,
    /** True when the last update from the parent's phone is too old to describe now. */
    val stale: Boolean,
    /** Today's state in plain words, from the card: "Quiet", "Monitoring unavailable"... */
    val stateLabel: String,
    val usualFirstMinute: Int?,
    /** Oldest first. */
    val days: List<WeekDay>,
    /** The weekly rhythm trend, already worded as phone patterns. The parent is written as [NoteWriter.NAME_TOKEN]. */
    val trendLines: List<String>,
    /** The open "is this a new routine?" question, in words, or null. */
    val routineQuestion: String?,
    val battery: Int,
) {
    fun allowedTimes(): Set<String> =
        (listOfNotNull(nowMinute, usualFirstMinute) + days.mapNotNull { it.firstUseMinute }).map { Clock.time(it, lang) }.toSet()
}

data class AskAnswer(val text: String, val source: String) {
    val byAi: Boolean get() = source != Note.TEMPLATE
}

/**
 * "Ask MaPa": the family asks about the week and gets an answer grounded in the shared summaries.
 * Sent to a cloud model: the question with the parent's name swapped for a placeholder, and the
 * facts below. Never raw activity, never the name. Every answer passes [ClaimsGuard]; a question
 * that asks for a health or safety judgement is answered locally and never leaves the phone.
 */
object AskMapa {
    const val MAX_QUESTION = 300
    private const val MAX_ANSWER = 420

    fun prompt(question: String, name: String, f: AskFacts): String {
        val language = if (f.lang == Lang.HI) "Hindi (Devanagari script)" else "English"
        fun tm(m: Int) = Clock.time(m, f.lang)
        val facts = buildList {
            add("Status now: ${f.stateLabel}${if (f.stale) " (no fresh update from the phone: today's picture is unavailable)" else ""}")
            add("Time now: ${tm(f.nowMinute)}")
            f.usualFirstMinute?.let { add("Usual first phone use: ${tm(it)}") }
            f.days.forEach { d ->
                val first = d.firstUseMinute?.let { "first use ${tm(it)}" } ?: "no phone use seen"
                val unknown = if (d.observedBins < 96) ", part of the day unknown" else ""
                val tags = if (d.tags.isNotEmpty()) ", marked by family: ${d.tags.joinToString("/")}" else ""
                add("${d.label}: $first, ${d.activeBins} active 15-minute periods$unknown$tags")
            }
            f.trendLines.forEach { add("Trend: $it") }
            f.routineQuestion?.let { add("Open question to the family: $it") }
        }
        return """
            A family member asks about their parent's phone activity. The parent is called ${NoteWriter.NAME_TOKEN}.
            Rules:
            - Use only the facts below. If they do not answer the question, say so in one sentence.
            - Talk about phone activity only. Never mention health, safety, sleep, mood, falls or emergencies, and never say the parent is fine or okay.
            - Do not guess reasons. A quiet day may have a normal reason, and you may say that.
            - Copy times exactly as written in the facts. Do not add other times or percentages.
            - If the status says no fresh update, say that you cannot describe now.
            - Write in $language. At most three short sentences and 60 words. Warm and plain. No emojis, no greeting.
            Facts:
            ${facts.joinToString("\n            ") { "- $it" }}
            Question: ${MiniJson.quote(question.trim().take(MAX_QUESTION).let { if (name.isBlank()) it else maskName(it, name) })}
            Answer:
        """.trimIndent()
    }

    fun answer(question: String, name: String, f: AskFacts, models: List<TextModel>): AskAnswer {
        val q = question.trim()
        val tail = staleSuffix(f)
        if (ClaimsGuard.asksForJudgement(q)) return AskAnswer(refusal(f.lang) + " " + template(f).replace(NoteWriter.NAME_TOKEN, name) + tail, Note.TEMPLATE)
        val p by lazy { prompt(q, name, f) }
        for (m in models) {
            val raw = runCatching { m.generate(p) }.getOrNull() ?: continue
            val text = raw.trim().removePrefix("Answer:").trim().trim('"', '“', '”').lines().filter { it.isNotBlank() }.joinToString(" ")
                .replace(NoteWriter.NAME_TOKEN, name)
            val verdict = ClaimsGuard.check(text, f.lang, f.allowedTimes(), setOf(f.battery), MAX_ANSWER, 0)
            if (verdict.ok) return AskAnswer(text + tail, m.id)
        }
        return AskAnswer(template(f).replace(NoteWriter.NAME_TOKEN, name) + tail, Note.TEMPLATE)
    }

    /** Added by the app, never by the model, so an old picture is never presented as current. */
    private fun staleSuffix(f: AskFacts): String = if (!f.stale) "" else when (f.lang) {
        Lang.EN -> " The phone has not sent an update recently, so this may not describe now."
        Lang.HI -> " फ़ोन से हाल में कोई अपडेट नहीं आया, इसलिए यह अभी की स्थिति नहीं बता सकता।"
    }

    fun refusal(lang: Lang): String = when (lang) {
        Lang.EN -> "I can only describe phone activity patterns. I cannot say how someone is doing, or whether they are well or safe. If you are worried, please call."
        Lang.HI -> "मैं सिर्फ़ फ़ोन इस्तेमाल का पैटर्न बता सकता हूँ। किसी की सेहत या सुरक्षा के बारे में नहीं बता सकता। चिंता हो तो फ़ोन करें।"
    }

    /** The floor: facts only, no model. */
    fun template(f: AskFacts): String {
        val seen = f.days.filter { it.firstUseMinute != null }
        if (seen.isEmpty()) return when (f.lang) {
            Lang.EN -> "There is no phone activity in the shared days yet."
            Lang.HI -> "साझा किए गए दिनों में अभी फ़ोन इस्तेमाल नहीं दिखा।"
        }
        val median = seen.map { it.firstUseMinute!! }.sorted().let { it[it.size / 2] }
        val tagged = f.days.count { it.tags.isNotEmpty() }
        return when (f.lang) {
            Lang.EN -> buildString {
                append("Phone use showed on ${seen.size} of the last ${f.days.size} days, usually first around ${Clock.time(median, f.lang)}.")
                if (tagged > 0) append(" $tagged of those days ${if (tagged == 1) "was" else "were"} marked as different.")
                f.trendLines.firstOrNull()?.let { append(" ").append(it) }
            }
            Lang.HI -> buildString {
                append("पिछले ${f.days.size} दिनों में से ${seen.size} दिन फ़ोन इस्तेमाल दिखा, आमतौर पर पहला इस्तेमाल लगभग ${Clock.time(median, f.lang)}।")
                if (tagged > 0) append(" इनमें $tagged दिन अलग दिन के रूप में चिह्नित थे।")
                f.trendLines.firstOrNull()?.let { append(" ").append(it) }
            }
        }
    }

    /** Whole-word masking: short call names ("Ma", "Papa") must not eat parts of other words ("many", "MaPa"). */
    internal fun maskName(q: String, name: String): String =
        Regex("""(?<![\p{L}\p{M}])""" + Regex.escape(name.trim()) + """(?![\p{L}\p{M}])""",RegexOption.IGNORE_CASE).replace(q, NoteWriter.NAME_TOKEN)
}
