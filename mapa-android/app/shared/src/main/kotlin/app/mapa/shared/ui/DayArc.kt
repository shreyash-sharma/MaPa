package app.mapa.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/**
 * Today against the usual day, drawn as the Stitch "sky arc": the hours from 6 AM run along a soft
 * curve. A warm window under the curve marks when this parent usually uses the phone, today's use
 * lies on the curve as solid segments, an open circle marks "usually by", and a glowing dot marks now.
 * [stale] turns it all grey with no glow: old data must never look like a good day.
 * As on the landing page, the day draws itself in once, a soft sky glows under the curve, hour ticks
 * run along the horizon like an instrument, and the "now" pebble breathes slowly.
 * [timeOf] formats a minute of the day in the reader's language.
 */
@Composable
fun DayArc(
    chart: ParentCard.Chart,
    description: String,
    timeOf: (Int) -> String,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
) {
    val c = Mapa.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.outlineVariant
    val warm = if (stale) c.unavailable else c.accent
    val glow = if (stale) c.unavailable.copy(alpha = 0.18f) else c.usualBand
    val progress by drawIn(chart.nowBin)
    val pulse by breath()

    // Window: from 6 AM to a little after now (at least to noon, at most 11 PM), like Stitch's morning arc.
    val start = 24
    val expected = chart.expectedByBin
    val end = maxOf(48, minOf(92, maxOf(chart.nowBin, expected ?: 0) + 6))
    val span = (end - start).toFloat()
    fun f(bin: Int) = ((bin - start) / span).coerceIn(0f, 1f)

    Column(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(2.4f)) {
            val left = 12.dp.toPx(); val right = size.width - 12.dp.toPx()
            val base = size.height - 4.dp.toPx(); val top = 10.dp.toPx()
            val ctrlY = 2 * top - base // so the curve's peak sits at [top]
            fun x(t: Float) = left + (right - left) * t
            // Quadratic Bézier from (left, base) through control (mid, ctrlY) to (right, base); x is linear in t.
            fun y(t: Float) = (1 - t) * (1 - t) * base + 2 * (1 - t) * t * ctrlY + t * t * base
            fun at(bin: Int) = Offset(x(f(bin)), y(f(bin)))

            // Usual window: warm columns under the curve, stronger where the habit is stronger.
            val binW = (right - left) / span
            for (b in start until end) {
                val p = chart.usual.getOrNull(b) ?: 0.0
                if (p > 0.15) {
                    val t = f(b)
                    drawRect(
                        Brush.verticalGradient(listOf(glow.copy(alpha = glow.alpha * (0.4f + 0.6f * p.toFloat())), glow.copy(alpha = glow.alpha * 0.12f)), startY = y(t), endY = base),
                        topLeft = Offset(x(t), y(t)), size = androidx.compose.ui.geometry.Size(binW + 0.5f, base - y(t)),
                    )
                }
            }
            // The sky under the curve: a soft warm glow from the top, never on old data.
            val arc = Path().apply { moveTo(left, base); quadraticTo((left + right) / 2, ctrlY, right, base) }
            if (!stale) drawPath(
                Path().apply { addPath(arc); close() },
                Brush.verticalGradient(listOf(warm.copy(alpha = 0.14f * progress), Color.Transparent), startY = top, endY = base),
            )
            // Horizon with hour ticks (longer every 6 hours), like the ruler on the landing page.
            drawLine(track, Offset(left - 6f, base), Offset(right + 6f, base), 1.5f)
            for (h in (start / 4)..(end / 4)) {
                val bx = x(f(h * 4))
                val long = h % 6 == 0
                drawLine(track, Offset(bx, base), Offset(bx, base - (if (long) 9 else 5).dp.toPx()), if (long) 2f else 1.2f)
            }
            drawPath(arc, track, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))

            // Today's phone use: solid segments on the curve; hours with no data stay dotted grey.
            fun segment(from: Int, to: Int, color: Color, width: Float, dashed: Boolean = false) {
                val p = Path()
                var b = from.toFloat()
                p.moveTo(x(f(from)), y(f(from)))
                while (b <= to + 1) { val t = f(b.toInt()).let { _ -> ((b - start) / span).coerceIn(0f, 1f) }; p.lineTo(x(t), y(t)); b += 0.25f }
                drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3f, 7f)) else null))
            }
            var b = start
            // The draw-in reveals today from 6 AM to now.
            val last = minOf(end, start + ((chart.nowBin + 1 - start) * progress).toInt().coerceAtLeast(0))
            while (b < last) {
                val v = chart.active.getOrNull(b) ?: -1
                var e = b
                while (e + 1 < last && (chart.active.getOrNull(e + 1) ?: -1) == v) e++
                when (v) {
                    1 -> segment(b, e, warm, 5.dp.toPx())
                    -1 -> segment(b, e, muted.copy(alpha = 0.5f), 3.dp.toPx(), dashed = true)
                }
                b = e + 1
            }

            // "Usually by": an open circle on the curve with a dashed drop to the horizon.
            expected?.takeIf { it in start..end }?.let { e ->
                val o = at(e)
                drawLine(warm.copy(alpha = 0.6f), Offset(o.x, o.y + 6.dp.toPx()), Offset(o.x, base), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                drawCircle(c.paper, 5.dp.toPx(), o)
                drawCircle(warm, 5.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            }
            // Now: a glowing pebble (no glow when the data is old).
            if (chart.nowBin in start..end && progress > 0.98f) {
                val o = at(chart.nowBin)
                drawLine(if (stale) muted else warm, Offset(o.x, o.y + 8.dp.toPx()), Offset(o.x, base), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                if (!stale) {
                    drawCircle(warm.copy(alpha = 0.10f + 0.10f * pulse), (14 + 6 * pulse).dp.toPx(), o)
                    drawCircle(warm.copy(alpha = 0.35f), 9.dp.toPx(), o)
                }
                drawCircle(if (stale) muted else warm, 6.dp.toPx(), o)
                drawCircle(c.paper, 6.dp.toPx(), o, style = Stroke(1.5.dp.toPx()))
            }
        }
        // Labels under the horizon: start, "usually by" (warm), now (bold), end. Each at its real place.
        BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp)) {
            val inner = maxWidth - 24.dp
            fun place(bin: Int) = (12.dp + inner * f(bin) - 36.dp).coerceIn(0.dp, maxWidth - 72.dp)
            val labels = buildList {
                add(Triple(start, timeOf(start * 15), 0))
                expected?.takeIf { it in start..end && kotlin.math.abs(it - chart.nowBin) >= 5 }?.let { add(Triple(it, timeOf(it * 15), 1)) }
                if (chart.nowBin in start..end) add(Triple(chart.nowBin, timeOf(chart.nowBin * 15), 2))
                add(Triple(end, timeOf(end * 15), 0))
            }
            // Drop the edge labels where they would touch a marker label.
            val markers = labels.filter { it.third != 0 }.map { it.first }
            labels.filter { l -> l.third != 0 || markers.none { kotlin.math.abs(it - l.first) < span * 0.18f } }.forEach { (bin, text, kind) ->
                Text(
                    text,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (kind == 2) FontWeight.Bold else FontWeight.Medium),
                    color = when (kind) { 1 -> warm; 2 -> ink; else -> muted },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp).offset(x = place(bin)),
                )
            }
        }
    }
}
