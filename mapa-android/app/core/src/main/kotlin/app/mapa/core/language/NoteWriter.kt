package app.mapa.core.language

/**
 * An on-device language model: our fine-tuned open model (Gemma 3 270M class, via LiteRT-LM),
 * or Gemini Nano where the phone has it. [generate] returns null when unavailable or on failure.
 */
interface TextModel {
    /** Short id synced with the note, e.g. "gemma3-270m-mapa" or "gemini-nano". */
    val id: String
    /** True for models that run off the phone: the prompt then carries a placeholder, never the parent's name. */
    val remote: Boolean get() = false
    fun generate(prompt: String): String?
}

/** A daily note and which model wrote it; [source] is [TEMPLATE] when no model was used. */
data class Note(val text: String, val source: String) {
    val byAi: Boolean get() = source != TEMPLATE

    companion object {
        const val TEMPLATE = "template"
    }
}

/**
 * AI first, template as the floor: each on-device model is tried in order; the first note
 * that passes every rule in [ClaimsGuard] wins. If none does, the template note is used.
 */
class NoteWriter(private val models: List<TextModel>) {
    constructor(model: TextModel?) : this(listOfNotNull(model))

    /** Why the last model output was rejected, for the parent app's diagnostics screen. */
    var lastRejection: List<String> = emptyList()
        private set

    fun write(f: NoteFacts): Note {
        val prompt by lazy { NotePrompt.build(f) }
        val shielded by lazy { NotePrompt.build(f.copy(person = f.person.copy(name = NAME_TOKEN)), NAME_TOKEN) }
        for (m in models) {
            val raw = runCatching { m.generate(if (m.remote) shielded else prompt) }.getOrNull() ?: continue
            val text = clean(raw).let { if (m.remote) it.replace(NAME_TOKEN, f.person.name) else it }
            val verdict = ClaimsGuard.check(text, f)
            lastRejection = verdict.problems
            if (verdict.ok) return Note(text, m.id)
        }
        return Note(Phrases(f.lang).dailyNote(f), Note.TEMPLATE)
    }

    companion object {
        /** What a cloud model sees instead of the parent's name. The phone swaps the real name back in. */
        const val NAME_TOKEN = "[NAME]"
    }

    /** Strip quotes, labels and blank lines small models like to add. */
    internal fun clean(raw: String): String = raw.trim()
        .removePrefix("Update:").trim()
        .trim('"', '“', '”', '\'')
        .lines().filter { it.isNotBlank() }.joinToString(" ")
        .trim()
}
