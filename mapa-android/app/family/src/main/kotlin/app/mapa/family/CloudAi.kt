package app.mapa.family

import android.content.Context
import app.mapa.core.cloud.AskAnswer
import app.mapa.core.cloud.AskFacts
import app.mapa.core.cloud.AiBudget
import app.mapa.core.cloud.AiKind
import app.mapa.core.cloud.AskMapa
import app.mapa.core.cloud.CounterStore
import app.mapa.core.cloud.ChatClient
import app.mapa.core.cloud.CheckIn
import app.mapa.core.cloud.CloudProvider
import app.mapa.core.cloud.CloudProviders
import app.mapa.core.cloud.CloudProxy
import app.mapa.core.cloud.CloudTextModel
import app.mapa.core.cloud.TagProposal
import app.mapa.core.language.Note
import app.mapa.shared.Backend
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Meta AI in the family app, off until this family member switches it on in Settings.
 * It sees only what [CheckIn] and the note prompt list: a name-free summary of the day, or the text
 * the member typed. The parent's phone never calls it. Order: Muse Spark, then the Meta open model on Baseten.
 * The keys live in the `chat` Cloud Function; only a debug build with keys in local.properties calls Meta directly.
 */
object CloudAi {
    private const val PREFS = "mapa_family"
    private const val KEY = "cloud_ai"

    /** Read by [FamilyNotes], which has no Context. Set at start-up and by the Settings switch. */
    @Volatile var enabled = false
        private set

    fun load(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY, false)
        val counters = ctx.getSharedPreferences("mapa_ai_budget", Context.MODE_PRIVATE)
        budget = AiBudget(object : CounterStore {
            override fun get(key: String) = counters.getLong(key, 0L)
            override fun put(key: String, value: Long) { counters.edit().putLong(key, value).apply() }
        }, { java.time.LocalDate.now().toString() })
        noteCache = ctx.getSharedPreferences("mapa_ai_notes", Context.MODE_PRIVATE)
        noteLang = app.mapa.core.cloud.NoteLang.of(prefs.getString("note_lang", null))
    }

    /** The daily note's extra language (Marathi or Tamil), or null for the app language. */
    @Volatile var noteLang: app.mapa.core.cloud.NoteLang? = null
        private set

    fun setNoteLang(ctx: Context, l: app.mapa.core.cloud.NoteLang?) {
        noteLang = l
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("note_lang", l?.code).apply()
    }

    /** Translates a checked English note; null keeps the English one. Two cloud calls at most, counted as a note. */
    fun translateNote(english: String, facts: app.mapa.core.language.NoteFacts, to: app.mapa.core.cloud.NoteLang): String? {
        val p = providers.firstOrNull() ?: return null
        val client = ChatClient(p, budget = budget, kind = AiKind.NOTE)
        return app.mapa.core.cloud.NoteTranslator.translate(english, facts, to) { client.complete(it) }
    }

    /** Spend guard for this build (Spark plan: the key is in the build). Null until [load]. */
    @Volatile var budget: AiBudget? = null
        private set

    /** One cloud note per parent per day, kept across app starts (see [FamilyNotes]). */
    @Volatile var noteCache: android.content.SharedPreferences? = null
        private set

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    private val providers: List<CloudProvider> by lazy {
        val direct = listOf(CloudProviders.museSpark(BuildConfig.MUSE_SPARK_KEY), CloudProviders.baseten(BuildConfig.BASETEN_KEY))
            .filter { it.usable }
        direct.ifEmpty { CloudProviders.viaProxy(proxy()) }
    }

    /** The `chat` function in asia-south1, on the real project or the local Emulator Suite. */
    private fun proxy(): CloudProxy {
        val url = if (Backend.usingEmulator) {
            "http://${Backend.emulatorHost}:5001/${Backend.DEMO_PROJECT}/$REGION/chat"
        } else {
            "https://$REGION-${FirebaseApp.getInstance().options.projectId}.cloudfunctions.net/chat"
        }
        return CloudProxy(url, emulator = Backend.usingEmulator) {
            FirebaseAuth.getInstance().currentUser?.let { Tasks.await(it.getIdToken(false), 15, TimeUnit.SECONDS).token }
        }
    }

    private const val REGION = "asia-south1"

    /** Always true now that the server holds the keys; a server without keys fails each call and the template note is used. */
    val available: Boolean get() = providers.isNotEmpty()

    fun textModels(kind: AiKind = AiKind.NOTE): List<CloudTextModel> =
        if (enabled) providers.map { CloudTextModel(it, ChatClient(it, budget = budget, kind = kind)) } else emptyList()

    fun isCloud(n: Note): Boolean = providers.any { it.id == n.source }

    /** The family member's words to a proposed life tag. Null when the model is unreachable or the answer fails the checks. */
    suspend fun propose(text: String, zone: ZoneId): TagProposal? = withContext(Dispatchers.IO) {
        if (!enabled || text.isBlank()) return@withContext null
        val now = ZonedDateTime.now(zone)
        val prompt = CheckIn.prompt(text, now)
        for (p in providers) {
            val reply = ChatClient(p, budget = budget, kind = AiKind.TAG).complete(prompt, CheckIn.SCHEMA) ?: continue
            CheckIn.parse(reply, now)?.let { return@withContext it }
        }
        null
    }

    /** Ask MaPa. Always returns an answer: a Meta model's when it passes the claims rules, else the facts-only template. */
    suspend fun ask(question: String, name: String, facts: AskFacts): AskAnswer = withContext(Dispatchers.IO) {
        AskMapa.answer(question, name, facts, textModels(AiKind.ASK))
    }
}
