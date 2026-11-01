package app.mapa.family

import android.util.Log
import app.mapa.core.language.Note
import app.mapa.core.language.NoteFacts
import app.mapa.core.language.NoteWriter
import app.mapa.core.language.TextModel
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Option C (decided 2026-10-21): the family member's phone writes the daily note with Gemini Nano,
 * from the structured facts the parent's phone syncs. Nothing raw is involved, the parent's phone
 * carries no language model, and the note comes out in this family member's language. Every note
 * still passes [app.mapa.core.language.ClaimsGuard]; without Gemini Nano, the template note is used.
 * Hackathon addition: with the member's opt-in, Muse Spark (or the Meta open model on Baseten) writes it from
 * the same facts, with the parent's name replaced by a placeholder. See [CloudAi].
 */
object FamilyNotes {
    private const val TAG = "MaPaNotes"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<Pair<NoteFacts, Boolean>, Note>()
    @Volatile private var client: GenerativeModel? = null
    @Volatile private var downloadStarted = false

    /** Meta AI first when this member switched it on (the name never leaves the phone), then Gemini Nano, then the template. */
    suspend fun write(facts: NoteFacts): Note {
        val key = facts to CloudAi.enabled
        cache[key]?.let { return it }
        // A cloud note is written once per parent per day, then kept: opening the app never calls the cloud again.
        val extra = CloudAi.noteLang?.takeIf { CloudAi.enabled && facts.lang == app.mapa.core.language.Lang.EN }
        val day = "${facts.person.name}|${extra?.code ?: facts.lang.code}|${java.time.LocalDate.now()}"
        val kept = CloudAi.noteCache?.takeIf { CloudAi.enabled }?.let { p ->
            p.getString("$day|text", null)?.let { t -> Note(t, p.getString("$day|source", null) ?: Note.TEMPLATE) }
        }
        if (kept != null) return kept.also { cache[key] = it }
        return withContext(Dispatchers.IO) {
            val english = NoteWriter(CloudAi.textModels() + listOfNotNull(nano())).write(facts)
            // Marathi or Tamil: only a checked translation of the checked English note; else the English note.
            val note = extra?.let { l -> CloudAi.translateNote(english.text, facts, l)?.let { Note(it, english.source.takeIf { s -> s != Note.TEMPLATE } ?: "muse-spark-open-1026") } } ?: english
            note.also {
                cache[key] = it
                if (CloudAi.isCloud(it)) CloudAi.noteCache?.edit()?.putString("$day|text", it.text)?.putString("$day|source", it.source)?.apply()
            }
        }
    }

    /** Gemini Nano if this phone has it ready. Starts the one-time system download when offered. */
    private suspend fun nano(): TextModel? {
        val c = client ?: runCatching { Generation.getClient() }.getOrNull()?.also { client = it } ?: return null
        val status = runCatching { c.checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)
        Log.i(TAG, "Gemini Nano status $status")
        return when (status) {
            FeatureStatus.AVAILABLE -> NanoModel(c)
            FeatureStatus.DOWNLOADABLE -> {
                if (!downloadStarted) {
                    downloadStarted = true
                    scope.launch { runCatching { c.download().collect { } } }
                }
                null
            }
            else -> null
        }
    }

    private class NanoModel(private val c: GenerativeModel) : TextModel {
        override val id = "gemini-nano"
        override fun generate(prompt: String): String? =
            runBlocking { c.generateContent(prompt).candidates.firstOrNull()?.text }
    }
}
