package app.mapa.family.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mapa.core.cloud.AskAnswer
import app.mapa.core.cloud.AskMapa
import app.mapa.core.family.ParentCard
import app.mapa.core.language.Clock
import app.mapa.core.language.Phrases
import app.mapa.family.AskContext
import app.mapa.family.CloudAi
import app.mapa.family.FamilyRepo
import app.mapa.shared.ui.AiSpark
import app.mapa.shared.ui.DayStrip
import app.mapa.shared.ui.Eyebrow
import app.mapa.shared.ui.Mapa
import app.mapa.shared.ui.SecondaryButton
import app.mapa.shared.ui.SectionTitle
import app.mapa.shared.ui.Space
import app.mapa.shared.ui.TopBar
import app.mapa.shared.ui.Wordmark
import app.mapa.shared.ui.cardLabels
import app.mapa.shared.ui.stateColor
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The family app's three top-level places (Stitch's dock, in plain words): Today, History, Rhythm. */
enum class Tab { TODAY, HISTORY, RHYTHM }

/** Room the floating tab bar takes at the bottom of a top-level screen. */
val TabBarSpace = 112.dp

/**
 * Stitch's floating dock: a rounded pill with three tabs; the selected tab is a lighter pill with the
 * accent colour. Icon plus a 14sp+ label, each tab at least 48dp tall.
 */
@Composable
fun TabBar(tab: Tab, text: FamilyText, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier.navigationBarsPadding().padding(horizontal = Space.gutter, vertical = Space.m).widthIn(max = 420.dp).fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TabItem(text.tabToday, tab == Tab.TODAY, Modifier.weight(1f), { Icon(Icons.Filled.Home, null, Modifier.size(22.dp), tint = it) }) { onTab(Tab.TODAY) }
            TabItem(text.tabHistory, tab == Tab.HISTORY, Modifier.weight(1f), { Icon(Icons.Filled.DateRange, null, Modifier.size(22.dp), tint = it) }) { onTab(Tab.HISTORY) }
            TabItem(text.tabRhythm, tab == Tab.RHYTHM, Modifier.weight(1f), { AiSpark(size = 18.dp, color = it) }) { onTab(Tab.RHYTHM) }
        }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, modifier: Modifier, icon: @Composable (Color) -> Unit, onClick: () -> Unit) {
    val tint = if (selected) Mapa.colors.accent else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp).semantics { this.selected = selected; role = Role.Tab },
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.background else Color.Transparent,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            icon(tint)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) MaterialTheme.colorScheme.onSurface else tint, maxLines = 1)
        }
    }
}

/** Chips to choose a parent, shown only when the family member follows more than one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ParentPicker(pids: List<String>, selected: String, onPick: (String) -> Unit) {
    if (pids.size < 2) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(bottom = Space.l)) {
        pids.forEach { pid ->
            val p by remember(pid) { FamilyRepo.profile(pid) }.collectAsState(initial = null)
            FilterChip(
                selected = pid == selected, onClick = { onPick(pid) }, shape = CircleShape,
                label = { Text(p?.name ?: "…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = Space.m)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

/** The page frame for a tab: the wordmark, a serif title and room for the tab bar at the bottom. */
@Composable
internal fun TabPage(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Screen(topBar = { TopBar(start = { Wordmark() }) }) {
        Spacer(Modifier.height(Space.xl))
        Text(title, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(Space.xl))
        content()
        Spacer(Modifier.height(TabBarSpace))
    }
}

/**
 * History: the last 7 days as small day bars, the weekly trend, then every change of state grouped by day.
 * All of it is what the parent's phone already shared; nothing new is collected.
 */
@Composable
fun HistoryScreen(pids: List<String>, pid: String?, text: FamilyText, onPick: (String) -> Unit) {
    TabPage(text.tabHistory) {
        if (pid == null) { Text(text.noParents, style = MaterialTheme.typography.bodyLarge); return@TabPage }
        ParentPicker(pids, pid, onPick)
        val profile by remember(pid) { FamilyRepo.profile(pid) }.collectAsState(initial = null)
        val events by remember(pid) { FamilyRepo.events(pid, limit = 60) }.collectAsState(initial = emptyList())
        val trend by remember(pid) { FamilyRepo.latestTrend(pid) }.collectAsState(initial = null)
        val week by produceState(emptyList<app.mapa.core.wire.DayDoc>(), pid) { value = FamilyRepo.week(pid) }
        val ph = Phrases(text.lang)
        val person = FamilyRepo.person(profile)
        val zone = FamilyRepo.zone(profile)
        val labels = cardLabels(text.lang, text.aiLabel)
        val today = LocalDate.now(zone)
        val nowBin = java.time.LocalTime.now(zone).let { (it.hour * 60 + it.minute) / 15 }

        Eyebrow(text.lastSevenDays)
        Spacer(Modifier.height(Space.m))
        // The week at a glance: one small sun per day. A day with no data stays a grey dotted outline.
        if (week.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (6 downTo 0).map { today.minusDays(it.toLong()) }.forEach { date ->
                    val d = week.firstOrNull { it.date == date.toString() }
                    DaySun(
                        d?.active, if (date == today) nowBin else 96,
                        if (date == today) labels.today else Clock.weekday(date.dayOfWeek.value - 1, text.lang).take(3),
                        bold = date == today,
                    )
                }
            }
            Spacer(Modifier.height(Space.l))
        }
        if (week.isEmpty()) Text(text.noHistory, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (week.isNotEmpty()) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = Space.gutter, vertical = Space.l)) {
                week.reversed().forEachIndexed { i, d ->
                    val date = runCatching { LocalDate.parse(d.date) }.getOrNull() ?: return@forEachIndexed
                    val isToday = date == today
                    val observed = d.active.any { it >= 0 }
                    val sub = when {
                        !observed -> text.noData
                        d.firstActivityMinute != null -> text.firstUse(Clock.time(d.firstActivityMinute!!, text.lang))
                        else -> text.noUse
                    }
                    if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = Space.m), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isToday) labels.today else Clock.weekday(date.dayOfWeek.value - 1, text.lang), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(Space.s))
                    DayStrip(
                        ParentCard.Chart(d.active, d.usual, if (isToday) nowBin else 96, null),
                        "$sub", labels.stripHours, stale = !observed,
                    )
                }
            }
        }

        trend?.let { r ->
            SectionTitle(text.thisWeek)
            ph.trend(person, r).forEach { line -> Text(line, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = Space.s)) }
        }

        if (events.isNotEmpty()) {
            SectionTitle(text.details)
            val fmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag(text.lang.code))
            events.groupBy { (_, e) -> Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate() }.forEach { (date, list) ->
                Eyebrow(if (date == today) labels.today else fmt.format(date), modifier = Modifier.padding(top = Space.m, bottom = Space.m))
                list.forEachIndexed { i, (_, e) ->
                    val local = Instant.ofEpochMilli(e.atMs).atZone(zone)
                    TimelineItem(
                        time = Clock.time(local.hour * 60 + local.minute, text.lang),
                        state = ph.stateLabel(e.to),
                        body = e.text.ifBlank { ph.detail(e.reason, person, e.t) },
                        color = stateColor(e.to),
                        last = i == list.lastIndex,
                    )
                }
            }
        }
        Text(text.notMedical, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xl))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskSection(pid: String, text: FamilyText) {
    val scope = rememberCoroutineScope()
    val profile by remember(pid) { FamilyRepo.profile(pid) }.collectAsState(initial = null)
    val status by remember(pid) { FamilyRepo.status(pid) }.collectAsState(initial = null)
    val trend by remember(pid) { FamilyRepo.latestTrend(pid) }.collectAsState(initial = null)
    val routine by remember(pid) { FamilyRepo.routine(pid) }.collectAsState(initial = null)
    val tags by remember(pid) { FamilyRepo.tags(pid) }.collectAsState(initial = emptyList())
    val person = FamilyRepo.person(profile)
    val zone = FamilyRepo.zone(profile)
    var question by remember(pid) { mutableStateOf("") }
    var asking by remember(pid) { mutableStateOf(false) }
    var answer by remember(pid) { mutableStateOf<AskAnswer?>(null) }
    val ask = { q: String ->
        scope.launch {
            asking = true; answer = null
            val facts = AskContext.build(person, status, FamilyRepo.week(pid), tags, trend, routine, zone, text.lang, System.currentTimeMillis())
            answer = CloudAi.ask(q, person.name, facts)
            asking = false
        }
        Unit
    }
    Text(text.askIntro(person.name), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(Space.l))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        text.askSuggestions(person.name).forEach { s ->
            FilterChip(
                selected = false,
                onClick = { question = s; if (!asking) ask(s) },
                label = { Text(s, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = Space.m)) },
                shape = CircleShape,
            )
        }
    }
    OutlinedTextField(
        value = question,
        onValueChange = { question = it.take(AskMapa.MAX_QUESTION) },
        placeholder = { Text(text.askHint) },
        shape = MaterialTheme.shapes.large,
        textStyle = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(top = Space.l),
    )
    Text(if (CloudAi.available && CloudAi.enabled) text.askSent else text.askLocal, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Space.s))
    SecondaryButton(if (asking) text.reading else text.askSend, { ask(question) }, enabled = !asking && question.isNotBlank())
    answer?.let { a ->
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = Space.l)) {
            Column(Modifier.padding(Space.gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AiSpark(size = 16.dp)
                    Spacer(Modifier.width(Space.s))
                    Text(if (a.byAi) text.aiLabelCloud else text.askFromFacts, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(Space.m))
                Text(a.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * One day as a small sun: the hours from 6 AM to 10 PM along a half circle, used quarter-hours in terracotta.
 * Gaps with no data are dotted grey, and a day with no data at all is only a grey outline (never a good day).
 */
@Composable
private fun DaySun(active: List<Int>?, nowBin: Int, label: String, bold: Boolean) {
    val c = app.mapa.shared.ui.Mapa.colors
    val track = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val progress by app.mapa.shared.ui.drawIn(active?.hashCode())
    val observed = active?.any { it >= 0 } == true
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 42.dp, height = 26.dp).semantics { contentDescription = label }) {
            val stroke = 4.dp.toPx()
            val r = size.width / 2 - stroke
            val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height - stroke / 2)
            val box = androidx.compose.ui.geometry.Rect(center.x - r, center.y - r, center.x + r, center.y + r)
            drawArc(track, 180f, 180f, false, box.topLeft, box.size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke / 2, pathEffect = if (observed) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f, 5f))))
            if (!observed || active == null) return@Canvas
            val first = 24; val last = 88
            val until = minOf(last, first + ((minOf(nowBin + 1, last) - first) * progress).toInt())
            for (b in first until until) {
                val v = active.getOrNull(b) ?: -1
                if (v == 1) {
                    val start = 180f + (b - first) * 180f / (last - first)
                    drawArc(c.accent, start, 180f / (last - first) + 0.6f, false, box.topLeft, box.size,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Butt))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null), color = if (bold) MaterialTheme.colorScheme.onSurface else muted)
    }
}
