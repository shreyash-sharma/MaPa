package app.mapa.core.language

import app.mapa.routine.RoutineState

/**
 * The structured summary of a parent's day: the only thing a language model ever sees.
 * Times are minutes after local midnight. No app names, no content, no location.
 */
data class NoteFacts(
    val person: Person,
    val lang: Lang,
    /** 0 = Monday, as in the engine. */
    val weekday: Int,
    val nowMinute: Int,
    val state: RoutineState,
    val firstActivityTodayMinute: Int?,
    val usualFirstMinute: Int?,
    val lastInteractionMinute: Int?,
    val lastInteractionToday: Boolean,
    /** 15-minute periods with phone use so far today. */
    val activeBinsToday: Int,
    /** How many such periods this parent usually has by this time on this kind of day. */
    val usualActiveBinsSoFar: Double,
    val battery: Int,
    val charging: Boolean,
    /** True in days 1 to 14, while the rhythm is still being learned: no "usual" comparisons. */
    val learning: Boolean,
) {
    /** Every clock time the note is allowed to mention, as written by [Clock]. */
    fun allowedTimes(): Set<String> = listOfNotNull(
        nowMinute, firstActivityTodayMinute, usualFirstMinute, lastInteractionMinute,
    ).map { Clock.time(it, lang) }.toSet()
}

/** Builds the Gemini Nano prompt from [NoteFacts]. English instructions, output in the family's language. */
object NotePrompt {
    fun build(f: NoteFacts, nameToken: String? = null): String {
        val p = f.person
        val L = Lang.EN
        fun tm(m: Int) = Clock.time(m, f.lang)
        val facts = buildList {
            add("Name: ${p.name} (pronouns: ${p.subj}/${p.obj})")
            add("Today: ${Clock.weekday(f.weekday, L)}, now ${tm(f.nowMinute)}")
            add("First phone use today: ${f.firstActivityTodayMinute?.let { tm(it) } ?: "none yet"}")
            if (!f.learning) f.usualFirstMinute?.let { add("Usual first phone use on this kind of day: ${tm(it)}") }
            f.lastInteractionMinute?.takeIf { f.lastInteractionToday }?.let { add("Last phone use: ${tm(it)}") }
            add("Phone use so far today: ${f.activeBinsToday} active 15-minute periods")
            if (!f.learning) add("Usual by this time: about ${Math.round(f.usualActiveBinsSoFar)} active periods")
        }
        val example = Phrases(f.lang).dailyNote(f)
        val language = if (f.lang == Lang.HI) "Hindi (Devanagari script)" else "English"
        return """
            You write a short update for an adult child about their parent's phone activity today.
            Rules:
            - Use only the facts below. Do not guess reasons for anything.
            - Talk about phone activity only. Never mention health, safety, sleep, mood, falls or emergencies, and never say the parent is fine or okay.
            - Copy times exactly as they are written in the facts.${nameToken?.let { "\n            - Write the parent's name as $it exactly. Never invent a name." } ?: ""}
            - Write in $language. At most two sentences and 40 words. Warm and plain. No emojis, no greeting.
            Facts:
            ${facts.joinToString("\n            ") { "- $it" }}
            Style example: "$example"
            Update:
        """.trimIndent()
    }
}
