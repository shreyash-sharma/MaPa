package app.mapa.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.mapa.shared.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Page gutter and vertical rhythm shared by every screen. */
object Space {
    val gutter = 20.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** The MaPa wordmark: the emblem and the editorial serif. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Emblem(size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Text("MaPa", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Small sentence-case label above a block (Stitch's "folio" marker, kept readable: 14sp, no capitals). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp), color = color)
}

/** A row-shaped top bar that sits below the status bar (callers apply the insets). */
@Composable
fun TopBar(modifier: Modifier = Modifier, start: @Composable RowScope.() -> Unit, end: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.gutter - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = start)
        Row(verticalAlignment = Alignment.CenterVertically, content = end)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier.padding(top = Space.xxl, bottom = Space.m)) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        sub?.let {
            Spacer(Modifier.size(Space.xs))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A flat grouped list (settings style). Rows inside are separated by hairlines. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { Column(content = content) }
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = Space.l), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun GroupRow(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(Space.m)) }
        Column(Modifier.weight(1f), content = content)
        trailing?.let { Spacer(Modifier.width(Space.m)); it() }
    }
}

private val Pill = RoundedCornerShape(percent = 50)

/**
 * The one main action: a full-width sumi-ink pill, 64dp tall. A leading icon sits in its own soft
 * circle and the label is followed by an arrow, as in the Stitch design.
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    Button(
        onClick, modifier.fillMaxWidth().heightIn(min = 64.dp), enabled = enabled, shape = Pill,
        contentPadding = PaddingValues(start = if (leading != null) Space.m else Space.xl, end = Space.xl, top = Space.m, bottom = Space.m),
    ) {
        leading?.let {
            Box(
                Modifier.size(44.dp).background(LocalContentColor.current.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { it() }
            Spacer(Modifier.width(Space.l))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = if (leading != null) TextAlign.Start else TextAlign.Center, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(22.dp))
    }
}

/** The quieter action: a tinted pill with no border. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) {
    Button(
        onClick, modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled, shape = Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.m),
    ) {
        leading?.let { it(); Spacer(Modifier.width(Space.s)) }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** Dot plus label; the label carries the meaning, the colour only reinforces it. */
@Composable
fun StatusPill(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(color.copy(alpha = 0.10f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(Space.s))
        Text(label, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** MaPa's mark for on-device AI: a four-point spark, drawn as a vector so it themes with the app. */
/** The MaPa emblem (the same picture as the app icon). */
@Composable
fun Emblem(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Image(painterResource(R.drawable.mapa_emblem), contentDescription = null, modifier = modifier.size(size), contentScale = ContentScale.Fit)
}

@Composable
fun AiSpark(modifier: Modifier = Modifier, size: Dp = 16.dp, color: Color = Mapa.colors.accent) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = w / 2
        val waist = w * 0.14f
        val p = Path().apply {
            moveTo(c, 0f)
            quadraticTo(c + waist, c - waist, w, c)
            quadraticTo(c + waist, c + waist, c, w)
            quadraticTo(c - waist, c + waist, 0f, c)
            quadraticTo(c - waist, c - waist, c, 0f)
            close()
        }
        drawPath(p, color)
    }
}

/** The honest-limits line every screen carries: activity patterns only, never health or safety. */
@Composable
fun Disclaimer(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(top = Space.xl),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Circle with a person's initial, for family member rows. */
@Composable
fun Initial(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Time-of-day greeting key: 0 morning (5-12), 1 afternoon (12-17), 2 evening. */
fun dayPart(hour: Int = java.time.LocalTime.now().hour): Int = when (hour) {
    in 5..11 -> 0
    in 12..16 -> 1
    else -> 2
}

/** The emblem inside a soft terracotta halo that breathes slowly, as on the landing page. Still when motion is off. */
@Composable
fun EmblemHalo(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    val pulse by breath()
    val warm = Mapa.colors.accent
    Box(modifier.size(size * 2), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(warm.copy(alpha = 0.20f + 0.08f * pulse), warm.copy(alpha = 0.05f), Color.Transparent),
                ),
                radius = this.size.minDimension / 2 * (0.86f + 0.14f * pulse),
            )
        }
        Emblem(size = size)
    }
}

/** [text] with each of [names] set in italic terracotta: the people in a sentence stand out, like on the landing page. */
fun accentNames(text: String, names: List<String>, accent: Color) = androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    names.filter { it.isNotBlank() }.forEach { n ->
        var i = text.indexOf(n)
        while (i >= 0) {
            addStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), i, i + n.length)
            i = text.indexOf(n, i + n.length)
        }
    }
}
