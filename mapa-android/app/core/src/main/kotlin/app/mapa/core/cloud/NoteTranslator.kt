package app.mapa.core.cloud

import app.mapa.core.language.ClaimsGuard
import app.mapa.core.language.Lang
import app.mapa.core.language.NoteFacts
import app.mapa.core.language.NoteWriter

/** Extra languages for the daily note only (the app's screens stay in English and Hindi). */
enum class NoteLang(val code: String, val english: String, val native: String) {
    MR("mr", "Marathi", "मराठी"),
    TA("ta", "Tamil", "தமிழ்");

    companion object {
        fun of(code: String?): NoteLang? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Turns an English daily note that already passed [ClaimsGuard] into Marathi or Tamil with a cloud model, and
 * keeps the translation only if all of these hold:
 * - every time in the note (7:40 and the like) is still there, in digits;
 * - the parent's name never leaves the phone: the model sees [NoteWriter.NAME_TOKEN] and must keep it;
 * - translated back to English by the model, the text passes [ClaimsGuard] (no health or safety words, no
 *   all-clear, only the facts' times).
 * Anything else returns null, and the English note is shown.
 */
object NoteTranslator {
    private val TIME = Regex("""\d{1,2}:\d{2}""")

    fun prompt(text: String, to: NoteLang) = """
        Translate this short note about phone use into ${to.english}, in plain everyday words.
        Keep every time exactly as written, in digits (for example 7:40 AM). Keep ${NoteWriter.NAME_TOKEN} exactly as it is.
        Do not add or remove anything. Reply with the translation only.

        $text
    """.trimIndent()

    fun backPrompt(text: String, from: NoteLang) = """
        Translate this ${from.english} text into English, word for word as far as possible. Keep ${NoteWriter.NAME_TOKEN} and all times as they are.
        Reply with the translation only.

        $text
    """.trimIndent()

    /** [ask] sends one prompt to the model and returns its text, or null. Two calls at most. */
    fun translate(englishNote: String, f: NoteFacts, to: NoteLang, ask: (String) -> String?): String? {
        val name = f.person.name
        val shielded = if (name.isBlank()) englishNote else englishNote.replace(name, NoteWriter.NAME_TOKEN)
        val translated = ask(prompt(shielded, to))?.trim()?.trim('"', '“', '”') ?: return null
        if (TIME.findAll(shielded).any { it.value !in translated }) return null
        if (NoteWriter.NAME_TOKEN in shielded && NoteWriter.NAME_TOKEN !in translated) return null
        val back = ask(backPrompt(translated, to))?.trim() ?: return null
        val verdict = ClaimsGuard.check(back.replace(NoteWriter.NAME_TOKEN, name), f.copy(lang = Lang.EN))
        if (!verdict.ok) return null
        return translated.replace(NoteWriter.NAME_TOKEN, name)
    }
}
