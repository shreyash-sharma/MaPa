package app.mapa.shared.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** False when the person turned animations off in Android settings: then nothing moves. */
@Composable
fun motionAllowed(): Boolean {
    val ctx = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
}

/** 0 to 1 once, when a chart first appears, so the day draws itself in (one calm moment, then still). */
@Composable
fun drawIn(key: Any?, millis: Int = 1100): State<Float> {
    if (!motionAllowed()) return remember { mutableFloatStateOf(1f) }
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(millis, easing = FastOutSlowInEasing)) }
    return a.asState()
}

/** A slow breath (2.8 s) for the "now" marker. Fixed at its middle value when motion is off. */
@Composable
fun breath(): State<Float> {
    if (!motionAllowed()) return remember { mutableFloatStateOf(0.5f) }
    return rememberInfiniteTransition(label = "now").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Reverse), label = "breath",
    )
}
