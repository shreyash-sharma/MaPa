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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.mapa.core.language.Lang
import app.mapa.core.pairing.InviteCodes
import app.mapa.parent.Prefs
import app.mapa.parent.collect.UsageProbe
import app.mapa.shared.ui.MapaTheme
import app.mapa.shared.ui.DemoRole
import app.mapa.shared.ui.DemoAppScreen
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var demoMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        demoMode = isSampleDemoIntent(intent)
        if (!demoMode) tts = TextToSpeech(this) { ttsReady = it == TextToSpeech.SUCCESS }
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
                    val text = remember(lang) { ParentText(lang) }
                    val setLang = { l: Lang -> lang = l; prefs.lang = l }
                    when {
                        demoMode -> DemoAppScreen(DemoRole.PHONE_OWNER)
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
        demoMode = isSampleDemoIntent(intent)
        codeFrom(intent)?.let { pendingCode = it; Prefs(this).pendingCode = it }
    }

    /** mapa://join/ABC234 or mapa://join/ABC-234 */
    private fun codeFrom(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "mapa" && it.host == "join" }?.lastPathSegment?.let { InviteCodes.normalize(it) }

    private fun isSampleDemoIntent(intent: Intent?): Boolean =
        app.mapa.parent.BuildConfig.DEBUG && intent?.data?.let {
            it.scheme == "mapa" && it.host == "demo" && it.path == "/sample"
        } == true

    /** Reads a screen aloud in the parent's language: the spoken walkthrough from the brief. */
    private fun speak(text: String, lang: Lang) {
        val engine = tts ?: return
        if (!ttsReady) return
        engine.language = if (lang == Lang.HI) Locale.forLanguageTag("hi-IN") else Locale.forLanguageTag("en-IN")
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mapa-onboarding")
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

/** Debug builds only: `--es mapa.theme light|dark` previews a theme without changing the phone's setting. */
private fun ComponentActivity.debugTheme(): Boolean? =
    if (!app.mapa.parent.BuildConfig.DEBUG) null
    else when (intent?.getStringExtra("mapa.theme")) { "light" -> false; "dark" -> true; else -> null }
