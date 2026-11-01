package app.mapa.family.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mapa.core.family.ParentCard
import app.mapa.core.family.ParentCards
import app.mapa.core.language.Clock
import app.mapa.core.language.Lang
import app.mapa.core.language.Phrases
import app.mapa.family.FamilyRepo
import app.mapa.routine.RoutineState
import app.mapa.shared.ui.AiSpark
import app.mapa.shared.ui.Eyebrow
import app.mapa.shared.ui.Mapa
import app.mapa.shared.ui.Numerals
import app.mapa.shared.ui.Space
import app.mapa.shared.ui.StatusPill
import app.mapa.shared.ui.cardLabels
import app.mapa.shared.ui.stateColor
import java.time.Instant
import java.time.LocalDate

/** Words for the Rhythm tab, English or Hindi (one language per screen). */
private class RhythmText(val lang: Lang) {
    private fun t(en: String, hi: String) = if (lang == Lang.HI) hi else en
    val title = t("Rhythm", "दिनचर्या")
    val intro = t("What MaPa's model has learned on the parent's own phone, and how it reads today.", "MaPa के मॉडल ने माता-पिता के अपने फ़ोन पर क्या सीखा, और वह आज को कैसे पढ़ता है।")
    val expectedTitle = t("Expected vs today", "उम्मीद बनाम आज")
    val expectedKey = t("Expected chance of phone use", "फ़ोन इस्तेमाल की उम्मीद")
    val todayKey = t("Today's phone use", "आज फ़ोन का इस्तेमाल")
    val noDataKey = t("No data", "डेटा नहीं")
    val oldData = t("This is old data. MaPa has not heard from the phone recently.", "यह पुराना डेटा है। MaPa को हाल में फ़ोन से कुछ नहीं मिला।")
    val speakTitle = t("MaPa will speak up if…", "MaPa तब बताएगा जब…")
    fun speakUsual(name: String, time: String) = t(
        "$name usually uses the phone again by $time. If it stays unused for much longer than $name's usual quiet, MaPa tells you.",
        "$name आमतौर पर $time तक फ़ोन फिर इस्तेमाल करते हैं। अगर फ़ोन उनके सामान्य से काफ़ी ज़्यादा देर तक इस्तेमाल न हो, तो MaPa आपको बताएगा।",
    )
    fun speakNow(name: String) = t(
        "$name's phone is quiet for longer than usual. MaPa has told you on the Today tab.",
        "$name का फ़ोन सामान्य से ज़्यादा देर से चुप है। MaPa ने आज वाले टैब पर बताया है।",
    )
    fun speakActive(name: String) = t(
        "$name's phone is in use now. MaPa keeps comparing the day with $name's usual rhythm.",
        "$name का फ़ोन अभी इस्तेमाल में है। MaPa दिन की तुलना उनकी सामान्य दिनचर्या से करता रहता है।",
    )
    val speakStale = t("MaPa cannot see the phone right now, so it cannot compare today. It never shows old data as fine.", "MaPa अभी फ़ोन नहीं देख पा रहा, इसलिए आज की तुलना नहीं कर सकता। वह पुराने डेटा को कभी ठीक नहीं दिखाता।")
    val speakPaused = t("Sharing is paused by the parent. MaPa shows nothing until it is on again.", "माता-पिता ने साझा करना रोका है। फिर से चालू होने तक MaPa कुछ नहीं दिखाता।")
    val compareTitle = t("A fixed timer vs MaPa", "तय टाइमर बनाम MaPa")
    val timerLabel = t("A 24-hour timer app", "24 घंटे वाला टाइमर ऐप")
    fun timerLine(time: String, tomorrow: Boolean) = t("Speaks up at $time" + if (tomorrow) " tomorrow" else "", "${if (tomorrow) "कल " else ""}$time पर बताता है")
    val mapaLabel = t("MaPa", "MaPa")
    fun mapaLine(time: String) = t("Starts to look closely after $time today", "आज $time के बाद ध्यान से देखता है")
    val compareNote = t("A timer counts hours from the last use. MaPa knows this parent's usual day.", "टाइमर आख़िरी इस्तेमाल से घंटे गिनता है। MaPa इन माता-पिता का सामान्य दिन जानता है।")
    val learnedTitle = t("What MaPa has learned", "MaPa ने क्या सीखा")
    fun daysLearned(n: Int) = t(if (n == 1) "1 day of this phone's rhythm" else "$n days of this phone's rhythm", "इस फ़ोन की दिनचर्या के $n दिन")
    val guardWait = t("MaPa works from day one. A new day waits before the model learns from it (1 day at first, 7 days later), so an unusual day is not learned as normal.", "पहले 2 हफ़्तों के बाद, मॉडल किसी दिन से 7 दिन बाद ही सीखता है।")
    fun guardTags(n: Int) = t(if (n == 1) "1 day was marked as different, so it is not learned as usual." else "$n days were marked as different, so they are not learned as usual.", "$n दिन अलग बताए गए, इसलिए उन्हें सामान्य नहीं सीखा गया।")
    val guardRoutine = t("A new routine is waiting for your Yes or No. It is not learned until a person says Yes.", "एक नई दिनचर्या आपके हाँ या ना का इंतज़ार कर रही है। जब तक कोई हाँ न कहे, उसे नहीं सीखा जाता।")
    fun onPhone(name: String) = t("On $name's phone", "$name के फ़ोन पर")
    val conclusionsOnly = t("Only conclusions leave the phone. Never app names, messages or location.", "फ़ोन से सिर्फ़ नतीजे बाहर जाते हैं। ऐप के नाम, मैसेज या लोकेशन कभी नहीं।")
    val params = t("Parameters", "पैरामीटर")
    val perStep = t("Per step", "हर कदम")
    val size = t("Model size", "मॉडल का आकार")
    val hood = t("Under the hood", "अंदर क्या चलता है")
    val hide = t("Hide", "छिपाएँ")
    val hoodModel = t("Model", "मॉडल")
    val hoodRuntime = t("Runs with", "इससे चलता है")
    val hoodDecides = t("Decides alerts", "अलर्ट कौन तय करता है")
    val byModel = t("The model", "मॉडल")
    val byRules = t("Simple rules, while the model learns", "सरल नियम, जब तक मॉडल सीखता है")
    val daysLearnedShort = t("days of their own rhythm", "अपनी दिनचर्या के दिन")
    val ringNote = t("It works from day one with what it already knows about daily rhythms. Each mark is one more day of this person's own rhythm, up to the last 30.", "यह पहले दिन से काम करता है। हर निशान इस व्यक्ति की अपनी दिनचर्या का एक और दिन है, पिछले 30 दिन तक।")
}

/**
 * The Rhythm tab: MaPa's on-device model, made visible. Everything here comes from what the parent's
 * phone already shares (the day record, the status and its model facts); a missing value shows nothing.
 */
@Composable
fun RhythmScreen(pids: List<String>, pid: String?, text: FamilyText, onPick: (String) -> Unit) {
    val r = RhythmText(text.lang)
    TabPage(r.title) {
        if (pid == null) { Text(text.noParents, style = MaterialTheme.typography.bodyLarge); return@TabPage }
        ParentPicker(pids, pid, onPick)
        val profile by remember(pid) { FamilyRepo.profile(pid) }.collectAsState(initial = null)
        val status by remember(pid) { FamilyRepo.status(pid) }.collectAsState(initial = null)
        val zone = FamilyRepo.zone(profile)
        val day by remember(pid, zone) { FamilyRepo.today(pid, zone) }.collectAsState(initial = null)
        val tags by remember(pid) { FamilyRepo.tags(pid) }.collectAsState(initial = emptyList())
        val routine by remember(pid) { FamilyRepo.routine(pid) }.collectAsState(initial = null)
        val now = System.currentTimeMillis()
        val person = FamilyRepo.person(profile)
        val name = profile?.name ?: ""
        val card = ParentCards.build(person, status, day, now, text.lang, profile?.showPaused ?: true)
        val labels = cardLabels(text.lang, text.aiLabel)
        val paused = card.state == RoutineState.PAUSED_BY_PARENT
        val old = card.stale || card.state == RoutineState.MONITORING_UNAVAILABLE

        Text(r.intro, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.l))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(card.stateLabel, stateColor(card.state))
            Spacer(Modifier.width(Space.m))
            Text(card.updatedAgo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 1. Expected vs today
        if (!paused) card.chart?.let { chart ->
            Spacer(Modifier.height(Space.xl))
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.gutter)) {
                    Eyebrow(r.expectedTitle)
                    Spacer(Modifier.height(Space.m))
                    ExpectedChart(chart, r.expectedTitle, labels.stripHours, old)
                    HorizontalDivider(Modifier.padding(vertical = Space.m), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Key(if (old) Mapa.colors.unavailable.copy(alpha = 0.4f) else Mapa.colors.usualBand, r.expectedKey)
                    Spacer(Modifier.height(Space.s))
                    Row {
                        Key(if (old) Mapa.colors.unavailable else Mapa.colors.accent, r.todayKey, Modifier.weight(1f))
                        Key(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), r.noDataKey)
                    }
                    if (old) Text(r.oldData, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.m))
                }
            }
        }

        // 2. MaPa will speak up if…
        val expectedMinute = card.chart?.expectedByBin?.let { it * 15 }
        val speak = when {
            paused -> r.speakPaused
            old -> r.speakStale
            card.state == RoutineState.UNUSUAL_INACTIVITY -> r.speakNow(name)
            expectedMinute != null -> r.speakUsual(name, Clock.time(expectedMinute, text.lang))
            card.state == RoutineState.ACTIVE -> r.speakActive(name)
            else -> null
        }
        speak?.let {
            Spacer(Modifier.height(Space.xxl))
            Eyebrow(r.speakTitle)
            Spacer(Modifier.height(Space.s))
            Text(it, style = MaterialTheme.typography.headlineSmall)
        }

        // 3. A fixed timer vs MaPa (only when both times are real)
        val lastAt = status?.lastInteractionAtMs?.takeIf { !old && !paused }
        if (lastAt != null && expectedMinute != null) {
            val timerAt = Instant.ofEpochMilli(lastAt + 24L * 3600 * 1000).atZone(zone)
            val tomorrow = timerAt.toLocalDate() != LocalDate.now(zone)
            Spacer(Modifier.height(Space.xxl))
            Eyebrow(r.compareTitle)
            Spacer(Modifier.height(Space.m))
            Row(Modifier.fillMaxWidth()) {
                CompareCol(r.timerLabel, r.timerLine(Clock.time(timerAt.hour * 60 + timerAt.minute, text.lang), tomorrow), MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                Spacer(Modifier.width(Space.l))
                CompareCol(r.mapaLabel, r.mapaLine(Clock.time(expectedMinute, text.lang)), Mapa.colors.accent, Mapa.colors.accent, Modifier.weight(1f))
            }
            Text(r.compareNote, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.m))
        }

        // 4. What MaPa has learned
        val s = status
        if (s != null) {
            Spacer(Modifier.height(Space.xxl))
            Eyebrow(r.learnedTitle)
            Spacer(Modifier.height(Space.s))
            Text(r.daysLearned(s.dayNumber), style = MaterialTheme.typography.headlineSmall)
            val ph = Phrases(text.lang)
            s.lastTrainedT?.let { Text(ph.rhythmUpdated(it), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs)) }
            Spacer(Modifier.height(Space.m))
            val since = now - 30L * 24 * 3600 * 1000
            val taggedDays = tags.filter { it.endMs > since }.sumOf { ((it.endMs - it.startMs) / (24L * 3600 * 1000)).coerceAtLeast(1) }.toInt()
            Guard(r.guardWait)
            if (taggedDays > 0) Guard(r.guardTags(taggedDays))
            if (routine?.answer == null && routine != null) Guard(r.guardRoutine)
        }

        // 5. On the parent's phone: the model card
        if (s != null && (s.params != null || s.stepMicros != null || s.runtime != null)) {
            var open by rememberSaveable(pid) { mutableStateOf(false) }
            Spacer(Modifier.height(Space.xxl))
            // The landing page's dark "model" section, in the app: one instrument, always dark, in both themes.
            Surface(
                onClick = { open = !open }, shape = MaterialTheme.shapes.large, color = Panel.bg,
                modifier = Modifier.fillMaxWidth().animateContentSize(),
            ) {
                Column(Modifier.padding(Space.gutter)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AiSpark(size = 18.dp, color = Panel.warm)
                        Spacer(Modifier.width(Space.s))
                        Text(r.onPhone(name), style = MaterialTheme.typography.titleMedium, color = Panel.ink)
                    }
                    Spacer(Modifier.height(Space.l))
                    DayRing(s.dayNumber, r.daysLearnedShort, r.ringNote, glow = !old, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(Space.l))
                    HorizontalDivider(color = Panel.hair)
                    Spacer(Modifier.height(Space.l))
                    Row(Modifier.fillMaxWidth()) {
                        s.params?.let { Big(String.format(java.util.Locale.US, "%,d", it), r.params, Modifier.weight(1f)) }
                        s.stepMicros?.let { Big("$it µs", r.perStep, Modifier.weight(1f)) }
                        s.modelBytes?.let { Big("${(it + 512) / 1024} KB", r.size, Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(Space.l))
                    Text(r.conclusionsOnly, style = MaterialTheme.typography.bodyLarge, color = Panel.ink)
                    Text(if (open) r.hide else r.hood + " →", style = MaterialTheme.typography.labelLarge, color = Panel.warm, modifier = Modifier.padding(top = Space.m))
                    if (open) {
                        HorizontalDivider(Modifier.padding(vertical = Space.m), color = Panel.hair)
                        HoodRow(r.hoodModel, s.model.ifBlank { null })
                        HoodRow(r.hoodRuntime, s.runtime)
                        HoodRow(r.hoodDecides, if (s.modelDecides && s.modelReady) r.byModel else r.byRules)
                    }
                }
            }
        }
        Text(text.notMedical, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xl))
    }
}

@Composable
private fun Key(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(12.dp).background(color, CircleShape))
        Spacer(Modifier.width(Space.s))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Guard(s: String) {
    Row(Modifier.padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 9.dp).size(6.dp).background(Mapa.colors.accent, CircleShape))
        Spacer(Modifier.width(Space.m))
        Text(s, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CompareCol(label: String, line: String, rule: Color, labelColor: Color, modifier: Modifier) {
    Row(modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        androidx.compose.foundation.layout.Box(Modifier.width(2.dp).fillMaxHeight().background(rule))
        Spacer(Modifier.width(Space.m))
        Column {
            Eyebrow(label, color = labelColor)
            Spacer(Modifier.height(Space.xs))
            Text(line, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The instrument panel's own colours: the landing page's dark model section, the same in light and dark mode. */
private object Panel {
    val bg = Color(0xFF141311)
    val ink = Color(0xFFF2EEE8)
    val muted = Color(0xFFB8AEA4)
    val warm = Color(0xFFF08A5D)
    val hair = Color(0xFF34302C)
}

@Composable
private fun Big(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineLarge, color = Panel.ink)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Panel.muted)
    }
}

@Composable
private fun HoodRow(label: String, value: String?) {
    value ?: return
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = Panel.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Panel.ink, textAlign = TextAlign.End)
    }
}

/**
 * The slow lane as an instrument (the landing page's dial): 30 marks round a ring, one per day the model reads.
 * Days already learned are terracotta and draw in once; the centre gives the count in the large serif.
 * [glow] is false on old data: then no halo.
 */
@Composable
private fun DayRing(days: Int, label: String, note: String, glow: Boolean, modifier: Modifier = Modifier) {
    val n = days.coerceIn(0, 30)
    val progress by app.mapa.shared.ui.drawIn(n)
    val pulse by app.mapa.shared.ui.breath()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Box(Modifier.size(200.dp).semantics { contentDescription = "$n $label. $note" }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val outer = size.minDimension / 2
                if (glow) drawCircle(Brush.radialGradient(listOf(Panel.warm.copy(alpha = 0.16f + 0.06f * pulse), Color.Transparent), center = c, radius = outer * 0.8f), outer * 0.8f, c)
                // Fine minute ring, like the dial's bezel.
                for (k in 0 until 120) {
                    val a = Math.toRadians(k * 3.0 - 90)
                    val r1 = outer * 0.97f; val r0 = r1 - (if (k % 10 == 0) 7 else 3).dp.toPx()
                    drawLine(Panel.hair, Offset(c.x + r0 * kotlin.math.cos(a).toFloat(), c.y + r0 * kotlin.math.sin(a).toFloat()),
                        Offset(c.x + r1 * kotlin.math.cos(a).toFloat(), c.y + r1 * kotlin.math.sin(a).toFloat()), 1.2f)
                }
                // 30 day marks.
                val lit = (n * progress).toInt()
                for (d in 0 until 30) {
                    val a = Math.toRadians(d * 12.0 - 90)
                    val r1 = outer * 0.84f; val r0 = outer * 0.70f
                    val on = d < lit
                    drawLine(if (on) Panel.warm else Panel.hair, Offset(c.x + r0 * kotlin.math.cos(a).toFloat(), c.y + r0 * kotlin.math.sin(a).toFloat()),
                        Offset(c.x + r1 * kotlin.math.cos(a).toFloat(), c.y + r1 * kotlin.math.sin(a).toFloat()), (if (on) 4f else 2.5f).dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$n", style = MaterialTheme.typography.displayLarge, color = Panel.ink)
                Text(label, style = MaterialTheme.typography.labelMedium, color = Panel.muted, textAlign = TextAlign.Center, modifier = Modifier.width(110.dp))
            }
        }
        Spacer(Modifier.height(Space.s))
        Text(note, style = MaterialTheme.typography.labelMedium, color = Panel.muted, textAlign = TextAlign.Center)
    }
}

/**
 * The model's expected chance of phone use across the waking day as a soft hill (Stitch's sky
 * colours), with today's real use as solid segments along the base, no-data hours dotted grey and
 * now as a dot on the curve. Grey with no glow when the data is old.
 */
@Composable
private fun ExpectedChart(chart: ParentCard.Chart, description: String, hourLabels: List<String>, old: Boolean) {
    val c = Mapa.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val warm = if (old) c.unavailable else c.accent
    val band = if (old) c.unavailable.copy(alpha = 0.25f) else c.usualBand
    val first = 20; val last = 92
    val span = (last - first).toFloat()
    Column(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(2.2f)) {
            val base = size.height - 14.dp.toPx(); val top = 6.dp.toPx()
            fun x(b: Float) = ((b - first) / span).coerceIn(0f, 1f) * size.width
            // Light smoothing so the curve reads as a rhythm, not a bar code.
            val p = DoubleArray(96) { b -> (-2..2).sumOf { k -> chart.usual.getOrNull((b + k).coerceIn(0, 95)) ?: 0.0 } / 5.0 }
            val maxP = (p.slice(first until last).maxOrNull() ?: 0.0).coerceAtLeast(0.2)
            fun y(b: Int) = base - ((p[b] / maxP).toFloat() * (base - top))
            val curve = Path().apply {
                moveTo(x(first.toFloat()), y(first))
                for (b in first + 1..last) lineTo(x(b.toFloat()), y(b.coerceAtMost(95)))
            }
            val fill = Path().apply { addPath(curve); lineTo(x(last.toFloat()), base); lineTo(x(first.toFloat()), base); close() }
            drawPath(fill, Brush.verticalGradient(listOf(band, band.copy(alpha = band.alpha * 0.15f)), startY = top, endY = base))
            drawPath(curve, warm.copy(alpha = if (old) 0.6f else 0.85f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            drawLine(muted.copy(alpha = 0.4f), Offset(0f, base), Offset(size.width, base), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            // Today's use along the base; hours with no data dotted grey.
            val seen = minOf(last, chart.nowBin + 1)
            val h = 7.dp.toPx(); val by = base + 7.dp.toPx()
            var b = first
            while (b < seen) {
                val v = chart.active.getOrNull(b) ?: -1
                var e = b
                while (e + 1 < seen && (chart.active.getOrNull(e + 1) ?: -1) == v) e++
                if (v == 1) drawLine(warm, Offset(x(b.toFloat()) + h / 2, by), Offset(x(e + 1f) - h / 2, by), h, cap = StrokeCap.Round)
                if (v == -1) drawLine(muted.copy(alpha = 0.6f), Offset(x(b.toFloat()), by), Offset(x(e + 1f), by), h / 2, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, h)))
                b = e + 1
            }
            if (chart.nowBin in first..last) {
                val o = Offset(x(chart.nowBin.toFloat()), y(chart.nowBin))
                if (!old) drawCircle(warm.copy(alpha = 0.18f), 14.dp.toPx(), o)
                drawCircle(warm, 6.dp.toPx(), o)
            }
        }
        Spacer(Modifier.height(Space.s))
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
            listOf(24, 48, 72, 88).zip(hourLabels).forEach { (bin, label) ->
                val f = (bin - first) / span
                Text(label, style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.offset(x = (maxWidth * f - 16.dp).coerceIn(0.dp, maxWidth - 48.dp)))
            }
        }
    }
}
