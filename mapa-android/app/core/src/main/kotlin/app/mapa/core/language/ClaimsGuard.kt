package app.mapa.core.language

/**
 * The trust rules from the brief, enforced on every AI-written sentence before anyone sees it.
 * A note that fails is replaced by the template note; it is never edited and shown.
 */
object ClaimsGuard {
    data class Verdict(val ok: Boolean, val problems: List<String>)

    const val MAX_CHARS = 280

    /** Medical, safety, emergency and all-clear language, and guesses about why the phone is quiet. */
    private val BANNED_EN = listOf(
        "fall", "fell", "fallen", "safe", "safety", "unsafe", "emergency", "injur", "hurt", "hospital",
        "ambulance", "sick", "ill", "illness", "unwell", "health", "medical", "medicine", "doctor", "heart",
        "stroke", "died", "dead", "death", "unconscious", "dementia", "depress", "lonely", "asleep", "sleeping",
        "slept", "nap", "tired", "pain", "danger", "worry", "worried", "alarm", "accident", "police", "911", "112",
    )
    private val BANNED_EN_PHRASES = listOf(
        "is fine", "is okay", "is ok", "all good", "all is well", "doing well", "doing fine", "nothing to worry",
        "no need to worry", "don't worry", "is well",
    )
    private val BANNED_HI = listOf(
        "गिर", "सुरक्षित", "आपात", "अस्पताल", "एम्बुलेंस", "बीमार", "डॉक्टर", "स्वास्थ्य", "चोट", "दर्द",
        "सो रह", "सोई", "सोए", "नींद", "थक", "चिंता", "ख़तरा", "खतरा", "दुर्घटना", "पुलिस", "ठीक है", "ठीक हैं", "सब ठीक",
    )
    private val TIME = Regex("""(\d{1,2}):(\d{2})""")
    private val PERCENT = Regex("""(\d{1,3})\s*%""")
    private val WORD = Regex("""[a-z0-9']+""")

    fun check(text: String, f: NoteFacts): Verdict =
        check(text, f.lang, f.allowedTimes(), setOf(f.battery), MAX_CHARS, 1)

    /**
     * The same rules for any text that may only use the given times and percentages
     * (the daily note uses one line; Ask MaPa answers may use a few).
     */
    fun check(text: String, lang: Lang, allowedTimes: Set<String>, allowedPercents: Set<Int>, maxChars: Int, maxExtraLines: Int): Verdict {
        val problems = mutableListOf<String>()
        val t = text.trim()
        if (t.isEmpty()) problems += "empty"
        if (t.length > maxChars) problems += "too long (${t.length})"
        if (t.count { it == '\n' } > maxExtraLines) problems += "too many lines"
        val lower = t.lowercase()
        val words = WORD.findAll(lower).map { it.value }.toList()
        BANNED_EN.forEach { b -> if (words.any { w -> w == b || (b.length >= 5 && w.startsWith(b)) }) problems += "banned word: $b" }
        BANNED_EN_PHRASES.forEach { if (lower.contains(it)) problems += "all-clear phrase: $it" }
        BANNED_HI.forEach { if (t.contains(it)) problems += "banned word: $it" }

        // Every clock time must come from the facts: the model may not invent or shift times.
        val allowed = allowedTimes.mapNotNull { TIME.find(it)?.value }.toSet()
        TIME.findAll(t).forEach { m -> if (m.value !in allowed) problems += "time not in facts: ${m.value}" }
        // The only percentage the facts carry is the battery.
        PERCENT.findAll(t).forEach { m -> if (m.groupValues[1].toInt() !in allowedPercents) problems += "percent not in facts: ${m.value}" }

        if (lang == Lang.HI && t.none { it in 'ऀ'..'ॿ' }) problems += "not in Hindi"
        if (lang == Lang.EN && t.any { it in 'ऀ'..'ॿ' }) problems += "not in English"
        return Verdict(problems.isEmpty(), problems)
    }

    /** True when a question asks for a health, safety or "is she okay" judgement, which MaPa never gives. */
    fun asksForJudgement(question: String): Boolean {
        val lower = question.lowercase()
        val words = WORD.findAll(lower).map { it.value }.toSet()
        if (BANNED_EN.any { b -> words.any { w -> w == b || (b.length >= 5 && w.startsWith(b)) } }) return true
        if (JUDGEMENT_WORDS.any { it in words }) return true
        return BANNED_HI.any { question.contains(it) }
    }

    private val JUDGEMENT_WORDS = setOf("ok", "okay", "fine", "alright", "well", "good", "bad", "wrong", "okey")
}
