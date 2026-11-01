package app.mapa.parent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.unit.dp
import app.mapa.core.family.ParentCard
import app.mapa.core.language.Lang
import app.mapa.core.language.Role
import app.mapa.parent.ParentApp
import app.mapa.parent.Prefs
import app.mapa.parent.cloud.ParentCloud
import app.mapa.parent.collect.Scheduler
import app.mapa.shared.ui.DayArc
import app.mapa.shared.ui.StripLegend
import app.mapa.shared.ui.Group
import app.mapa.shared.ui.GroupDivider
import app.mapa.shared.ui.GroupRow
import app.mapa.shared.ui.Mapa
import app.mapa.shared.ui.PrimaryButton
import app.mapa.shared.ui.Space
import app.mapa.shared.ui.TopBar
import app.mapa.shared.ui.Wordmark
import app.mapa.shared.ui.cardLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.math.exp

private const val PAGES = 4

/**
 * Consent-first walkthrough: what is shared, what never is, how the AI learns, then an
 * explicit choice. Every page can be read aloud. Nothing is collected before "Start sharing".
 */
@Composable
fun Onboarding(
    text: ParentText,
    lang: Lang,
    onLang: (Lang) -> Unit,
    speak: (String, Lang) -> Unit,
    onConsented: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        TopBar(start = { Wordmark() })
        StepBar(page, text)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                .padding(top = Space.xl, bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            when (page) {
                0 -> Page(text.welcomeTitle, text.welcomeBody, text, speak, lang) {
                    Text(text.chooseLanguage, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        LangChip("English", lang == Lang.EN) { onLang(Lang.EN) }
                        LangChip("हिन्दी", lang == Lang.HI) { onLang(Lang.HI) }
                    }
                }
                1 -> Page(text.sharesTitle, null, text, speak, lang, spoken = text.shares + text.neverTitle + text.never) {
                    Group {
                        text.shares.forEachIndexed { i, s ->
                            if (i > 0) GroupDivider()
                            GroupRow(leading = { Icon(Icons.Filled.CheckCircle, null, tint = Mapa.colors.active) }) {
                                Text(s, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    Text(text.neverTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = Space.l))
                    Group {
                        text.never.forEachIndexed { i, s ->
                            if (i > 0) GroupDivider()
                            GroupRow(leading = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                                Text(s, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                2 -> Page(text.aiTitle, text.aiBody, text, speak, lang) {
                    val card = cardLabels(lang, text.aiNoteLabel)
                    val labels = card.dial
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(Space.gutter)) {
                            DayArc(ExampleDay, labels.usual, card.timeOf)
                            Spacer(Modifier.height(Space.m))
                            StripLegend(labels.usual, labels.today)
                        }
                    }
                }
                else -> ConsentPage(text, onConsented)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            if (page > 0) TextButton(onClick = { page-- }, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(text.back, style = MaterialTheme.typography.labelLarge)
            }
            if (page < PAGES - 1) PrimaryButton(text.next, { page++ }, Modifier.weight(1f))
        }
    }
}

/** Progress through the four steps: a real sequence, so it is shown as one. */
@Composable
private fun StepBar(page: Int, text: ParentText) {
    Column(Modifier.padding(horizontal = Space.gutter)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(PAGES) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (i <= page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(Space.s))
        Text(text.step(page + 1, PAGES), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LangChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = Space.s, vertical = Space.m)) },
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun Page(
    title: String,
    body: String?,
    text: ParentText,
    speak: (String, Lang) -> Unit,
    lang: Lang,
    spoken: List<String> = emptyList(),
    extra: @Composable () -> Unit = {},
) {
    Text(title, style = MaterialTheme.typography.displaySmall)
    body?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    OutlinedButton(
        onClick = { speak((listOfNotNull(title, body) + spoken).joinToString(". "), lang) },
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
        Spacer(Modifier.width(Space.s))
        Text(text.listen, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
    extra()
}

@Composable
private fun ConsentPage(text: ParentText, onConsented: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(prefs.name) }
    var role by remember { mutableStateOf(prefs.role) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Text(text.consentTitle, style = MaterialTheme.typography.displaySmall)
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(24) },
        label = { Text(text.nameLabel) },
        placeholder = { Text(text.nameHint) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        listOf(Role.MOTHER to text.roleMother, Role.FATHER to text.roleFather, Role.OTHER to text.roleOther).forEach { (r, label) ->
            LangChip(label, role == r) { role = r }
        }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (agreed) 2.dp else 1.dp, if (agreed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(top = Space.s),
    ) {
        Row(
            Modifier
                .toggleable(value = agreed, role = SemanticsRole.Checkbox, onValueChange = { agreed = it })
                .padding(Space.l),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = agreed, onCheckedChange = null, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(Space.m))
            Text(text.agree, style = MaterialTheme.typography.bodyLarge)
        }
    }
    PrimaryButton(
        text = text.start,
        enabled = agreed && name.isNotBlank() && !busy,
        modifier = Modifier.padding(top = Space.s),
        leading = if (busy) ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) }) else null,
        onClick = {
            busy = true
            prefs.name = name
            prefs.role = role
            scope.launch {
                val now = System.currentTimeMillis()
                prefs.consentAt = now
                // Offline is fine: the next tick signs in and syncs once the phone is online.
                withContext(Dispatchers.IO) {
                    runCatching {
                        val pid = ParentCloud.signIn()
                        ParentCloud.saveProfile(pid, prefs.profile(ZoneId.systemDefault().id), now)
                    }
                }
                prefs.consented = true
                ParentApp.start(ctx)
                Scheduler.tickNow(ctx)
                busy = false
                onConsented()
            }
        },
    )
}

/** A made-up day for the explainer: up early, quiet after lunch, busy in the evening. */
private val ExampleDay: ParentCard.Chart = run {
    fun bump(b: Int, at: Double, width: Double, h: Double) = h * exp(-((b - at) * (b - at)) / (2 * width * width))
    val usual = List(96) { b -> (bump(b, 30.0, 5.0, 0.8) + bump(b, 52.0, 4.0, 0.5) + bump(b, 80.0, 7.0, 0.9) + 0.02).coerceAtMost(1.0) }
    val now = 46
    val active = List(96) { b -> if (b <= now && usual[b] > 0.35 && b % 3 != 1) 1 else 0 }
    ParentCard.Chart(active = active, usual = usual, nowBin = now, expectedByBin = 50)
}
