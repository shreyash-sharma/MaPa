package app.mapa.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mapa.core.family.ParentCard

/** Waking hours shown on the strip: 5 AM to 11 PM (15-minute bins 20..92). */
private const val FIRST_BIN = 20
private const val LAST_BIN = 92

/**
 * Today on one line, as in the Stitch home card: the parent's usual rhythm as a soft terracotta band,
 * today's phone use as ink marks, "now" as a dot and the expected-by time as a dashed tick.
 * [stale] greys everything out: old data must never look like a good day.
 */
@Composable
fun DayStrip(
    chart: ParentCard.Chart,
    description: String,
    hourLabels: List<String>,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
    tall: Boolean = false,
) {
    val c = Mapa.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = if (stale) c.unavailable else MaterialTheme.colorScheme.onSurface
    val band = if (stale) c.unavailable.copy(alpha = 0.18f) else c.usualBand
    val mark = if (stale) c.unavailable else c.accent
    val track = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
    val h: Dp = if (tall) 64.dp else 40.dp
    val pulse by breath()
    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(h)) {
            val span = (LAST_BIN - FIRST_BIN).toFloat()
            fun x(bin: Int) = ((bin - FIRST_BIN) / span).coerceIn(0f, 1f) * size.width
            val binW = size.width / span
            val mid = size.height / 2
            val trackH = size.height * 0.26f
            drawRoundRect(track, Offset(0f, mid - trackH / 2), Size(size.width, trackH), CornerRadius(trackH / 2))
            // Hour ticks under the track, longer at 6 AM, noon and 6 PM: an instrument, not a progress bar.
            for (hr in (FIRST_BIN / 4)..(LAST_BIN / 4)) {
                val tx = x(hr * 4)
                val long = hr % 6 == 0
                drawLine(muted.copy(alpha = if (long) 0.55f else 0.3f), Offset(tx, size.height - (if (long) 7 else 4).dp.toPx()), Offset(tx, size.height), if (long) 2f else 1.2f)
            }
            // The usual rhythm: a soft halo, taller where the habit is stronger.
            for (b in FIRST_BIN until LAST_BIN) {
                val p = chart.usual.getOrNull(b) ?: 0.0
                if (p > 0.08) {
                    val hh = trackH * (1.2f + 1.2f * p.toFloat())
                    drawRect(band.copy(alpha = band.alpha * (0.3f + 0.7f * p.toFloat())), Offset(x(b), mid - hh / 2), Size(binW + 0.5f, hh))
                }
            }
            // Hours with no data (phone off or not observed): a grey dotted line, never left blank.
            var g = FIRST_BIN
            val seen = minOf(LAST_BIN, chart.nowBin + 1)
            while (g < seen) {
                if ((chart.active.getOrNull(g) ?: -1) == -1) {
                    var e = g
                    while (e + 1 < seen && (chart.active.getOrNull(e + 1) ?: -1) == -1) e++
                    drawLine(muted.copy(alpha = 0.7f), Offset(x(g), mid), Offset(x(e) + binW, mid), trackH * 0.5f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, trackH)))
                    g = e + 1
                } else g++
            }
            // Today's use: runs of used 15-minute bins drawn as one rounded segment each.
            var b = FIRST_BIN
            val end = minOf(LAST_BIN, chart.nowBin + 1)
            while (b < end) {
                if (chart.active.getOrNull(b) == 1) {
                    var e = b
                    while (e + 1 < end && chart.active.getOrNull(e + 1) == 1) e++
                    val w = (x(e) + binW - x(b)).coerceAtLeast(trackH)
                    drawRoundRect(mark, Offset(x(b), mid - trackH / 2), Size(w, trackH), CornerRadius(trackH / 2))
                    b = e + 1
                } else b++
            }
            chart.expectedByBin?.takeIf { it in FIRST_BIN..LAST_BIN }?.let { e ->
                drawLine(muted, Offset(x(e), 0f), Offset(x(e), size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            if (chart.nowBin in FIRST_BIN..LAST_BIN) {
                val nx = x(chart.nowBin)
                if (!stale) drawCircle(mark.copy(alpha = 0.10f + 0.12f * pulse), size.height * (0.38f + 0.12f * pulse), Offset(nx, mid))
                drawLine(ink, Offset(nx, mid - size.height * 0.45f), Offset(nx, mid + size.height * 0.45f), 2.5f)
                drawCircle(ink, 6f, Offset(nx, mid - size.height * 0.45f))
            }
        }
        Spacer(Modifier.height(6.dp))
        // 6 AM, 12 PM, 6 PM and 10 PM, placed at their real positions on the strip.
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
            val bins = listOf(24, 48, 72, 88)
            bins.zip(hourLabels).forEach { (b, label) ->
                val f = (b - FIRST_BIN) / (LAST_BIN - FIRST_BIN).toFloat()
                Text(label, style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.offset(x = maxWidth * f - 16.dp))
            }
        }
    }
}

/** Two keys under the large strip: the colour carries the meaning only together with these words. */
@Composable
fun StripLegend(usual: String, today: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(Mapa.colors.usualBand, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(usual, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Box(Modifier.size(12.dp).background(Mapa.colors.accent, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(today, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
