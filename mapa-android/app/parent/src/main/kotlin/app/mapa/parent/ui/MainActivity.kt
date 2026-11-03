package app.mapa.parent.ui

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.mapa.core.language.Lang
import app.mapa.core.language.Role
import app.mapa.core.pairing.InviteCodes
import app.mapa.parent.Prefs
import app.mapa.parent.collect.UsageProbe
import app.mapa.parent.cloud.ParentCloud
import app.mapa.shared.ui.MapaTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var demoMode by mutableStateOf(false)
    private var judgeInviteAccepted by mutableStateOf(false)
    private var judgeInviteDeclined by mutableStateOf(false)
    private var judgeDemoWentToBackground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        demoMode = app.mapa.parent.BuildConfig.JUDGE_DEMO_ENABLED
        tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
        val prefs = Prefs(this)
        pendingCode = codeFrom(intent) ?: prefs.pendingCode
        prefs.pendingCode = pendingCode
        setContent {
            MapaTheme(dark = debugTheme() ?: androidx.compose.foundation.isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    var consented by remember { mutableStateOf(prefs.consented) }
                    var lang by remember { mutableStateOf(prefs.lang) }
                    var usage by remember { mutableStateOf(UsageProbe.hasAccess(this@MainActivity)) }
                    val code = pendingCode
                    var liveText by remember(lang) { mutableStateOf(ParentTranslations.cached(this@MainActivity, lang)) }
                    LaunchedEffect(lang) {
                        if (lang != Lang.EN && lang != Lang.HI) {
                            val translated = withContext(Dispatchers.IO) { runCatching { ParentTranslations.fetch(this@MainActivity, lang) }.getOrDefault(emptyMap()) }
                            if (translated.isNotEmpty()) liveText = translated
                        }
                    }
                    val text = remember(lang, liveText) { ParentText(lang, liveText) }
                    val setLang = { l: Lang -> lang = l; prefs.lang = l }
                    when {
                        demoMode && judgeInviteAccepted -> HomeScreen(
                            text = text,
                            onDeleted = { judgeInviteAccepted = false },
                            judgeDemo = true,
                        )
                        demoMode && judgeInviteDeclined -> NoInviteScreen(text, lang, setLang) { judgeInviteDeclined = false }
                        demoMode -> ConsentScreen(
                            text = text,
                            code = JUDGE_DEMO_PAIRING.code,
                            lang = lang,
                            onLang = setLang,
                            speak = ::speak,
                            onDecline = { judgeInviteDeclined = true },
                            onDone = { judgeInviteAccepted = true },
                            demoPairing = JUDGE_DEMO_PAIRING,
                            onDemoAccept = {
                                prefs.name = JUDGE_DEMO_PAIRING.parentName
                                prefs.role = Role.of(JUDGE_DEMO_PAIRING.role)
                                prefs.consented = true
                                judgeInviteAccepted = true
                            },
                        )
                        !consented && code.isBlank() -> NoInviteScreen(text, lang, setLang) { pendingCode = it; prefs.pendingCode = it }
                        !consented -> ConsentScreen(
                            text, code, lang, setLang, ::speak,
                            onDecline = { pendingCode = ""; prefs.pendingCode = ""; tts?.stop() },
                            onDone = { tts?.stop(); consented = true },
                        )
                        !usage -> UsageAccessScreen(text, lang, setLang) { usage = true }
                        else -> HomeScreen(text = text, onDeleted = { consented = false })
                    }
                }
            }
        }
    }

    /** The family's invite code, waiting for papa's yes. Updated when a new link arrives. */
    private var pendingCode by mutableStateOf("")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        demoMode = app.mapa.parent.BuildConfig.JUDGE_DEMO_ENABLED
        judgeInviteAccepted = false
        judgeInviteDeclined = false
        codeFrom(intent)?.let { pendingCode = it; Prefs(this).pendingCode = it }
    }

    override fun onStart() {
        super.onStart()
        if (app.mapa.parent.BuildConfig.JUDGE_DEMO_ENABLED && judgeDemoWentToBackground) {
            judgeInviteAccepted = false
            judgeInviteDeclined = false
            judgeDemoWentToBackground = false
        }
    }

    override fun onStop() {
        if (app.mapa.parent.BuildConfig.JUDGE_DEMO_ENABLED) judgeDemoWentToBackground = true
        super.onStop()
    }

    /** mapa://join/ABC234 or mapa://join/ABC-234 */
    private fun codeFrom(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "mapa" && it.host == "join" }?.lastPathSegment?.let { InviteCodes.normalize(it) }

    /** Reads a screen aloud in the parent's language: the spoken walkthrough from the brief. */
    private fun speak(text: String, lang: Lang) {
        val engine = tts ?: return
        if (!ttsReady) return
        engine.language = Locale.forLanguageTag(when (lang) {
            Lang.HI -> "hi-IN"
            Lang.TA -> "ta-IN"
            Lang.FR -> "fr-FR"
            Lang.MR -> "mr-IN"
            Lang.EN -> "en-IN"
            else -> lang.code
        })
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mapa-onboarding")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

private val JUDGE_DEMO_PAIRING = ParentCloud.Pairing(
    code = "JUDGE01",
    familyUid = "judge-demo-family",
    familyName = "Riya",
    parentName = "Papa",
    role = "father",
    lang = "en",
)

/** Debug builds only: `--es mapa.theme light|dark` previews a theme without changing the phone's setting. */
private fun ComponentActivity.debugTheme(): Boolean? =
    if (!app.mapa.parent.BuildConfig.DEBUG) null
    else when (intent?.getStringExtra("mapa.theme")) { "light" -> false; "dark" -> true; else -> null }
