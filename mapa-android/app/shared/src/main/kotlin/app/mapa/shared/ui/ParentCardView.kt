package app.mapa.shared.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.text.withStyle
import app.mapa.core.family.ParentCard

/** Strings the card shows, in the reader's language. */
data class CardLabels(
    val aiNote: String = "Written on the phone by on-device AI",
    val battery: (Int, Boolean) -> String = { pct, charging -> "Battery $pct%" + if (charging) ", charging" else "" },
    val dial: DialLabels = DialLabels(),
    val showLegend: String = "How to read this",
    val hideLegend: String = "Hide",
    val stripHours: List<String> = listOf("6 AM", "12 PM", "6 PM", "10 PM"),
    val today: String = "Today",
    val lastUse: String = "Last phone use",
    val lastUseSub: String = "Today",
    val usualFirst: String = "Usually first use",
    val usualFirstSub: String = "Learned on this phone",
    val learningTitle: String = "Learning the rhythm",
    val learningDay: (Int, Int) -> String = { d, n -> "Day $d of $n" },
    val daysLeft: (Int) -> String = { n -> if (n == 1) "1 day left" else "$n days left" },
    val viewDay: String = "View day",
    /** A minute of the day as the reader writes it ("7:40 AM" / "सुबह 7:40"). */
    val timeOf: (Int) -> String = { m -> app.mapa.core.language.Clock.time(m, app.mapa.core.language.Lang.EN) },
)

private val Hero = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)

/**
 * The rhythm card, at two levels (glance and focus):
 * - Glance (default): name and state, one sentence, how fresh it is, and today's strip. Nothing else.
 * - Focus ([expanded]): the sentence as an editorial headline, the forecast, today's strip in a warm
 *   card with its key, the AI note, and quiet details (model and battery) at the bottom.
 */
@Composable
fun ParentCardView(
    name: String,
    card: ParentCard,
    modifier: Modifier = Modifier,
    labels: CardLabels = CardLabels(),
    expanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    if (expanded) {
        Column(modifier.fillMaxWidth()) { FocusBody(name, card, labels); footer() }
        return
    }
    val body: @Composable () -> Unit = { GlanceBody(name, card, labels, onClick != null) }
    val color = MaterialTheme.colorScheme.surfaceContainerLow
    if (onClick != null) Surface(onClick, modifier.fillMaxWidth(), shape = Hero, color = color, content = body)
    else Surface(modifier.fillMaxWidth(), shape = Hero, color = color, content = body)
}

/**
 * A soft aura in the state colour behind the top-right corner (the landing page's halo). It says the state
 * before any word is read. Never on old or paused data: a stale card must never look like a good day.
 */
@Composable
private fun Modifier.aura(card: ParentCard): Modifier {
    if (card.stale || card.state == app.mapa.routine.RoutineState.MONITORING_UNAVAILABLE || card.state == app.mapa.routine.RoutineState.PAUSED_BY_PARENT) return this
    val tint = stateColor(card.state)
    return drawBehind {
        drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(tint.copy(alpha = 0.30f), tint.copy(alpha = 0.08f), androidx.compose.ui.graphics.Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(size.width * 0.92f, size.height * 0.02f), radius = size.width * 0.75f,
            ),
            radius = size.width * 0.75f, center = androidx.compose.ui.geometry.Offset(size.width * 0.92f, size.height * 0.02f),
        )
    }
}

/** A warm wash at the top of a card, fading into the card colour (Stitch's "sunlit" gradient). */
@Composable
private fun Modifier.wash(card: ParentCard): Modifier =
    // No warmth on old or paused data: a stale card must never look like a good day.
    if (card.stale || card.state == app.mapa.routine.RoutineState.MONITORING_UNAVAILABLE || card.state == app.mapa.routine.RoutineState.PAUSED_BY_PARENT) this
    else background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Mapa.colors.wash, Mapa.colors.card)))

@Composable
private fun GlanceBody(name: String, card: ParentCard, labels: CardLabels, onClick: Boolean) {
    val paused = card.state == app.mapa.routine.RoutineState.PAUSED_BY_PARENT
    Column(Modifier.wash(card).aura(card).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f).semantics { heading() })
            StatusPill(card.stateLabel + (card.confidencePct?.let { "  $it%" } ?: ""), stateColor(card.state))
        }
        Spacer(Modifier.height(14.dp))
        // The sentence in the editorial serif; its times in italic terracotta, as on the landing page.
        Text(italicTimes(card.detail, accent(card)), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(card.updatedAgo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val learningDay = card.learningDay?.takeIf { !paused && !card.stale }
        if (learningDay != null) {
            Spacer(Modifier.height(20.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)) {
                LearningBox(learningDay, card.learningDays, labels, Modifier.padding(16.dp))
            }
        } else card.chart?.takeIf { !paused }?.let {
            Spacer(Modifier.height(20.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.background.copy(alpha = 0.7f)) {
                DayStrip(it, card.detail + (shownForecast(card)?.let { f -> " $f" } ?: ""), labels.stripHours, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), stale = card.stale)
            }
        }
        if (onClick) {
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(labels.viewDay, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

/** Stitch's "rhythm calibration" box: how far the learning period has come, in days, not percent. */
@Composable
private fun LearningBox(day: Int, total: Int, labels: CardLabels, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(labels.learningTitle, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(labels.learningDay(day, total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.LinearProgressIndicator(
            progress = { day / total.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Mapa.colors.accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(10.dp))
        Text(labels.daysLeft(total - day), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Stitch's paired readouts: today's last phone use next to the usual first use, both real times. */
@Composable
private fun Readouts(card: ParentCard, labels: CardLabels) {
    val last = card.lastUseMinute
    val usual = card.usualFirstMinute
    if (last == null && usual == null) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val hair = MaterialTheme.colorScheme.outlineVariant
    val warm = if (card.state == app.mapa.routine.RoutineState.UNUSUAL_INACTIVITY) Mapa.colors.accent else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        last?.let { Readout(labels.lastUse, labels.timeOf(it), labels.lastUseSub, warm, warm, Modifier.weight(1f)) }
        if (last != null && usual != null) Spacer(Modifier.width(16.dp))
        usual?.let { Readout(labels.usualFirst, labels.timeOf(it), labels.usualFirstSub, hair, muted, Modifier.weight(1f)) }
    }
}

@Composable
private fun Readout(title: String, time: String, sub: String, rule: androidx.compose.ui.graphics.Color, titleColor: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Row(modifier.fillMaxHeight()) {
        androidx.compose.foundation.layout.Box(Modifier.width(2.dp).fillMaxHeight().background(rule))
        Spacer(Modifier.width(14.dp))
        Column {
            Eyebrow(title, color = titleColor)
            Spacer(Modifier.height(6.dp))
            Text(time, style = MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val ClockTime = Regex("""\d{1,2}:\d{2}""")

/** The accent for times in a sentence: terracotta, or plain ink when the data is old (nothing warm on stale data). */
@Composable
private fun accent(card: ParentCard): androidx.compose.ui.graphics.Color =
    if (card.stale || card.state == app.mapa.routine.RoutineState.MONITORING_UNAVAILABLE) androidx.compose.ui.graphics.Color.Unspecified else Mapa.colors.accent

/** Times in the headline set in italic, as in the Stitch design ("Phone not used since *9:10 AM*."). */
private fun italicTimes(s: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) = androidx.compose.ui.text.buildAnnotatedString {
    var i = 0
    Regex("""\d{1,2}:\d{2}(\s?[AP]M)?""").findAll(s).forEach { m ->
        append(s.substring(i, m.range.first))
        withStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = color)) { append(m.value) }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

/** The forecast, unless the sentence above already gives the same time (Quiet says it once, not twice). */
private fun shownForecast(card: ParentCard): String? = card.forecast?.takeUnless { f ->
    val t = ClockTime.find(f)?.value
    t != null && card.detail.contains(t)
}

/** Freshness with a small dot; the dot takes the state colour so "how old" and "what state" read together. */
@Composable
private fun Updated(card: ParentCard) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(stateColor(card.state), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(card.updatedAgo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FocusBody(name: String, card: ParentCard, labels: CardLabels) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        Updated(card)
    }
    androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Spacer(Modifier.height(20.dp))
    StatusPill(card.stateLabel + (card.confidencePct?.let { "  $it%" } ?: ""), stateColor(card.state))
    Spacer(Modifier.height(16.dp))
    // A short sentence gets the full editorial size; a long one steps down so it still reads in one glance.
    Text(italicTimes(card.detail, accent(card)), style = if (card.detail.length > 70) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge)
    shownForecast(card)?.let { Text(it, style = MaterialTheme.typography.headlineSmall, color = muted, modifier = Modifier.padding(top = 10.dp)) }

    val describe = card.detail + (shownForecast(card)?.let { " $it" } ?: "")
    card.chart?.takeIf { card.state != app.mapa.routine.RoutineState.PAUSED_BY_PARENT }?.let {
        Spacer(Modifier.height(28.dp))
        Surface(shape = Hero, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.wash(card).padding(20.dp)) {
                Eyebrow(labels.today)
                Spacer(Modifier.height(8.dp))
                DayArc(it, describe, labels.timeOf, stale = card.stale)
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                StripLegend(labels.dial.usual, labels.dial.today)
            }
        }
    }

    if (card.state != app.mapa.routine.RoutineState.PAUSED_BY_PARENT) {
        Spacer(Modifier.height(28.dp))
        Readouts(card, labels)
    }

    card.note?.let { note ->
        androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 28.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(24.dp))
        Text(note, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 31.sp))
        if (card.noteByAi) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiSpark(size = 14.dp)
                Spacer(Modifier.width(8.dp))
                Text(labels.aiNote, style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    val quiet = listOfNotNull(card.learning, card.modelLine, card.battery?.let { labels.battery(it, card.charging == true) })
    quiet.forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(bottom = 2.dp)) }
}

/** Card and dial words in English or Hindi; [aiNote] differs between the parent and family apps. */
fun cardLabels(lang: app.mapa.core.language.Lang, aiNote: String): CardLabels {
    val hi = lang == app.mapa.core.language.Lang.HI
    fun t(en: String, h: String) = if (hi) h else en
    return CardLabels(
        aiNote = aiNote,
        battery = { pct, charging -> t("Battery $pct%", "बैटरी $pct%") + if (charging) t(", charging", ", चार्ज हो रही है") else "" },
        showLegend = t("How to read this", "इसे कैसे पढ़ें"),
        hideLegend = t("Hide", "छिपाएँ"),
        stripHours = listOf(360, 720, 1080, 1320).map { app.mapa.core.language.Clock.time(it, lang).replace(":00", "") },
        today = t("Today", "आज"),
        lastUse = t("Last phone use", "आख़िरी बार फ़ोन"),
        lastUseSub = t("Today", "आज"),
        usualFirst = t("Usually first use", "आमतौर पर पहली बार"),
        usualFirstSub = t("Learned on this phone", "इसी फ़ोन पर सीखा"),
        learningTitle = t("Learning the rhythm", "दिनचर्या सीख रहा है"),
        learningDay = { d, n -> t("Day $d of $n", "दिन $d / $n") },
        daysLeft = { n -> if (n == 1) t("1 day left", "1 दिन बाकी") else t("$n days left", "$n दिन बाकी") },
        viewDay = t("View day", "दिन देखें"),
        timeOf = { m -> app.mapa.core.language.Clock.time(m, lang) },
        dial = DialLabels(
            hours = listOf(0, 360, 720, 1080).map { app.mapa.core.language.Clock.time(it, lang).replace(":00", "") },
            today = t("Today's phone use", "आज फ़ोन का इस्तेमाल"),
            usual = t("Usual rhythm", "सामान्य दिनचर्या"),
            now = t("Now", "अभी"),
            expected = t("Usually active by", "आमतौर पर इस समय तक सक्रिय"),
        ),
    )
}
