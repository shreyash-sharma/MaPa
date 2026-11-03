package app.mapa.parent.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.mapa.core.language.Lang
import app.mapa.core.language.Role
import app.mapa.core.pairing.InviteCodes
import app.mapa.parent.ParentApp
import app.mapa.parent.Prefs
import app.mapa.parent.cloud.ParentCloud
import app.mapa.parent.collect.MotionSignal
import app.mapa.parent.collect.Scheduler
import app.mapa.parent.collect.UsageProbe
import app.mapa.shared.ui.Mapa
import app.mapa.shared.ui.Emblem
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Check
import app.mapa.shared.ui.AiSpark
import app.mapa.shared.ui.PrimaryButton
import app.mapa.shared.ui.SecondaryButton
import app.mapa.shared.ui.Space
import app.mapa.shared.ui.TopBar
import app.mapa.shared.ui.Wordmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/*
 * The parent's whole setup: the family member does the work, the parent says yes once and turns
 * on one switch. No typing, no choices, no explainer pages (one optional "What is shared?").
 */

/** Shared frame: insets, wordmark, scrolling body, and a fixed bottom area for the one big action. */
@Composable
private fun SetupFrame(top: @Composable () -> Unit = {}, bottom: @Composable ColumnScope.() -> Unit = {}, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TopBar(start = { Wordmark() }, end = { top() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter).padding(top = Space.xl),
            content = body,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.l), content = bottom)
    }
}

@Composable
private fun LangSwitch(lang: Lang, onLang: (Lang) -> Unit) {
    TextButton(onClick = { onLang(if (lang == Lang.EN) Lang.HI else Lang.EN) }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (lang == Lang.EN) "हिन्दी" else "English", style = MaterialTheme.typography.labelLarge)
    }
}

/** Opened without a link: nothing to decide yet, just tell papa who sets it up. A code is the fallback. */
@Composable
fun NoInviteScreen(text: ParentText, lang: Lang, onLang: (Lang) -> Unit, onCode: (String) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    SetupFrame(top = { LangSwitch(lang, onLang) }, bottom = {
        if (typing) PrimaryButton(text.continueLabel, { InviteCodes.normalize(code)?.let(onCode) }, enabled = InviteCodes.normalize(code) != null)
        else TextButton(onClick = { typing = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(text.haveCode, style = MaterialTheme.typography.labelLarge) }
    }) {
        Spacer(Modifier.height(Space.xxl))
        Text(text.noInviteTitle, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(Space.l))
        Text(text.noInviteBody, style = MaterialTheme.typography.bodyLarge)
        if (typing) {
            Spacer(Modifier.height(Space.xl))
            OutlinedTextField(
                code, { code = it.uppercase().take(7) }, label = { Text(text.codeLabel) }, singleLine = true,
                shape = MaterialTheme.shapes.small, textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * "Shreyash wants to see your daily phone rhythm." Read aloud as soon as it opens, in papa's
 * language. One big yes. Everything papa would otherwise type comes from the family's invite.
 */
@Composable
fun ConsentScreen(
    text: ParentText,
    code: String,
    lang: Lang,
    onLang: (Lang) -> Unit,
    speak: (String, Lang) -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
    demoPairing: ParentCloud.Pairing? = null,
    onDemoAccept: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf<ParentCloud.Pairing?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val askMotion = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) MotionSignal.start(ctx); onDone() }

    LaunchedEffect(code, demoPairing) {
        val p = demoPairing ?: withContext(Dispatchers.IO) { runCatching { ParentCloud.fetchPairing(code) }.getOrNull() }
        if (p == null) failed = true
        else {
            pairing = p
            // The family member chose papa's language; use it unless papa already switched.
            if (prefs.lang.code != p.lang && !prefs.consented) onLang(Lang.of(p.lang))
        }
    }
    val p = pairing

    SetupFrame(top = {
        if (p == null) LangSwitch(lang, onLang)
        p?.let { ListenPill(text.listen) { speak(text.wantsToSee(it.familyName) + ". " + text.wantsBody(it.familyName), lang) } }
    }, bottom = {
        if (p != null) {
            PrimaryButton(
                text.yesShare,
                enabled = !busy,
                leading = if (busy) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) }) else null,
                onClick = {
                    if (onDemoAccept != null) {
                        onDemoAccept()
                    } else {
                        busy = true
                        scope.launch {
                            val now = System.currentTimeMillis()
                            prefs.name = p.parentName
                            prefs.role = Role.of(p.role)
                            prefs.consentAt = now
                            val ok = withContext(Dispatchers.IO) {
                                runCatching {
                                    val pid = ParentCloud.acceptPairing(p)
                                    ParentCloud.saveProfile(pid, prefs.profile(ZoneId.systemDefault().id), now)
                                }.isSuccess
                            }
                            busy = false
                            if (ok) {
                                prefs.consented = true
                                prefs.pendingCode = ""
                                ParentApp.start(ctx)
                                Scheduler.tickNow(ctx)
                                if (MotionSignal.hasPermission(ctx)) onDone() else askMotion.launch(MotionSignal.permission)
                            } else failed = true
                        }
                    }
                },
            )
            Spacer(Modifier.height(Space.s))
            TextButton(onClick = onDecline, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(text.notNow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) {
        when {
            failed -> {
                Spacer(Modifier.height(Space.xxl))
                Text(text.linkExpired, style = MaterialTheme.typography.headlineSmall)
            }
            p == null -> Row(Modifier.padding(top = Space.xxl), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Space.m))
                Text(text.loading, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Spacer(Modifier.height(Space.s))
                // Stitch's welcome chip: a small pill with a warm dot.
                Row(
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(Mapa.colors.accent, CircleShape))
                    Spacer(Modifier.width(Space.s))
                    Text(text.welcomeChip, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(Space.l))
                Text(text.wantsToSee(p.familyName), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(Space.xxl))
                // The promise, set apart with a terracotta rule (Stitch's privacy block).
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(Mapa.colors.accent.copy(alpha = 0.7f)))
                    Spacer(Modifier.width(Space.l))
                    Text(text.wantsBody(p.familyName), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 31.sp))
                }
                Spacer(Modifier.height(Space.xxl))
                // Stitch's two promise cards: what never leaves the phone, and what the family sees.
                PromiseCard({ Icon(Icons.Filled.Lock, null, Modifier.size(20.dp)) }, text.neverTitle, text.never)
                Spacer(Modifier.height(Space.m))
                PromiseCard({ AiSpark(size = 18.dp) }, text.sharesTitle, if (details) text.shares else text.shares.take(2)) {
                    if (!details) TextButton(onClick = { details = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text.whatShared, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(Space.xxl))
                // Stitch's language cards, each name in its own script.
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    LangCard("हिन्दी", lang == Lang.HI, Modifier.weight(1f)) { onLang(Lang.HI) }
                    LangCard("English", lang == Lang.EN, Modifier.weight(1f)) { onLang(Lang.EN) }
                }
            }
        }
    }
}

@Composable
private fun PromiseCard(icon: @Composable () -> Unit, title: String, lines: List<String>, more: @Composable () -> Unit = {}) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Space.gutter), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.xs))
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs)) }
                more()
            }
        }
    }
}

@Composable
private fun LangCard(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.heightIn(min = 64.dp), shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                    .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(Space.m))
            Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Read the screen aloud: a pill in the top bar, always within reach (sound only when tapped). */
@Composable
private fun ListenPill(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.heightIn(min = 48.dp)) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(Space.s))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Line(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, s: String) {
    Row(Modifier.padding(vertical = Space.s), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(Space.m))
        Text(s, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The one Android setting. MaPa opens its own switch directly (one tap on Pixel; falls back to the
 * list elsewhere), watches for the change and comes back to the front by itself.
 */
@Composable
fun UsageAccessScreen(text: ParentText, lang: Lang, onLang: (Lang) -> Unit, onGranted: () -> Unit) {
    val ctx = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (UsageProbe.hasAccess(ctx)) onGranted() }
    DisposableEffect(Unit) {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val listener = AppOpsManager.OnOpChangedListener { _, pkg ->
            if (pkg == ctx.packageName && UsageProbe.hasAccess(ctx)) bringBack(ctx)
        }
        ops.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, ctx.packageName, listener)
        onDispose { ops.stopWatchingMode(listener) }
    }
    SetupFrame(top = { LangSwitch(lang, onLang) }, bottom = {
        PrimaryButton(text.openSwitch, { openUsageAccess(ctx) })
    }) {
        Spacer(Modifier.height(Space.xl))
        Text(text.oneStepTitle, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(Space.l))
        Text(text.oneStepBody, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.xl))
        SwitchPicture()
        Spacer(Modifier.height(Space.xl))
        Text(text.androidWarn, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A picture of what papa will see: the MaPa row with its switch, ringed so it is easy to find. */
@Composable
private fun SwitchPicture() {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Space.gutter), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Mapa.colors.paper, CircleShape), contentAlignment = Alignment.Center) {
                Emblem(size = 26.dp)
            }
            Spacer(Modifier.width(Space.m))
            Text("MaPa", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = androidx.compose.ui.graphics.Color.Transparent, border = BorderStroke(3.dp, Mapa.colors.accent)) {
                Switch(checked = true, onCheckedChange = null, modifier = Modifier.padding(6.dp))
            }
        }
    }
}

private fun openUsageAccess(ctx: Context) {
    val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(direct) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
}

/** Return to MaPa once the switch is on, so papa doesn't have to find the back button. */
private fun bringBack(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}
