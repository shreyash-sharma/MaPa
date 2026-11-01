package app.mapa.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mapa.routine.RoutineState
import app.mapa.shared.R

/*
 * MaPa design tokens. See design/MaPa-design.md for the reasoning.
 * Cream paper and sumi ink (Stitch 'Kinship editorial'): Newsreader headlines, Manrope for reading.
 */

/** Colours Material's scheme has no slot for. */
@Immutable
data class MapaColors(
    val paper: Color,
    val sunk: Color,
    val muted: Color,
    val accent: Color,
    val usualBand: Color,
    val hairline: Color,
    val active: Color,
    val quiet: Color,
    val unusual: Color,
    val unavailable: Color,
    val paused: Color,
    /** Tinted card ground (Stitch "surface-container-low") and the warm wash at the top of a hero card. */
    val card: Color,
    val wash: Color,
)

// "Kinship editorial" (Stitch export, 2026-10-27): cream paper, sumi-ink actions, terracotta for today's
// marks and the AI spark, warm stone for quiet details. Unusual stays an earthen orange, never alarm red.
private val LightExtra = MapaColors(
    paper = Color(0xFFFCF9F4),
    sunk = Color(0xFFF0EDE9),
    muted = Color(0xFF4B4640),
    accent = Color(0xFFA23E18),
    usualBand = Color(0xFFFFB59C).copy(alpha = 0.55f),
    hairline = Color(0xFFE5E2DD),
    active = Color(0xFF2F6E4A),
    quiet = Color(0xFF55657A),
    unusual = Color(0xFFA23E18),
    unavailable = Color(0xFF6B655E),
    paused = Color(0xFF7A5A3A),
    card = Color(0xFFF6F3EE),
    wash = Color(0xFFFBEDE4),
)

private val DarkExtra = MapaColors(
    paper = Color(0xFF141311),
    sunk = Color(0xFF24211E),
    muted = Color(0xFFB8AEA4),
    accent = Color(0xFFF08A5D),
    usualBand = Color(0xFFF08A5D).copy(alpha = 0.30f),
    hairline = Color(0xFF34302C),
    active = Color(0xFF8FD3A8),
    quiet = Color(0xFFAFC0D4),
    unusual = Color(0xFFF59A6E),
    unavailable = Color(0xFFB5ADA5),
    paused = Color(0xFFD9B88F),
    card = Color(0xFF1D1B19),
    wash = Color(0xFF2A1F19),
)

private val Ink = Color(0xFF1C1C19)

private val Light = lightColorScheme(
    primary = Color(0xFF1A1816),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF390C00),
    secondary = Color(0xFFA23E18),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF6F2000),
    background = LightExtra.paper,
    onBackground = Ink,
    surface = LightExtra.paper,
    onSurface = Ink,
    surfaceVariant = LightExtra.sunk,
    onSurfaceVariant = LightExtra.muted,
    surfaceContainerLow = LightExtra.card,
    surfaceContainer = LightExtra.sunk,
    surfaceContainerHigh = Color(0xFFEBE8E3),
    surfaceContainerHighest = Color(0xFFE5E2DD),
    outline = Color(0xFF7D766F),
    outlineVariant = Color(0xFFCEC5BD),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF3EEE8),
    onPrimary = Color(0xFF1A1816),
    primaryContainer = Color(0xFF4A2A1C),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = DarkExtra.accent,
    onSecondary = Color(0xFF390C00),
    secondaryContainer = Color(0xFF4A2A1C),
    onSecondaryContainer = Color(0xFFFFDBCF),
    background = DarkExtra.paper,
    onBackground = Color(0xFFEDE7E0),
    surface = DarkExtra.paper,
    onSurface = Color(0xFFEDE7E0),
    surfaceVariant = DarkExtra.sunk,
    onSurfaceVariant = DarkExtra.muted,
    surfaceContainerLow = DarkExtra.card,
    surfaceContainer = DarkExtra.sunk,
    surfaceContainerHigh = Color(0xFF2A2724),
    surfaceContainerHighest = Color(0xFF322E2A),
    outline = Color(0xFF8C847C),
    outlineVariant = DarkExtra.hairline,
    error = Color(0xFFFFB4AB),
)

@OptIn(ExperimentalTextApi::class)
private fun atkinson(weight: Int) = androidx.compose.ui.text.font.Font(
    R.font.atkinson_next,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Atkinson Hyperlegible Next: designed for low-vision readers, which is who reads the parent app. */
val Atkinson = FontFamily(atkinson(400), atkinson(500), atkinson(600), atkinson(700), atkinson(800))

@OptIn(ExperimentalTextApi::class)
private fun newsreader(weight: Int, italic: Boolean = false) = androidx.compose.ui.text.font.Font(
    R.font.newsreader,
    weight = FontWeight(weight),
    style = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", 36f)),
)

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = androidx.compose.ui.text.font.Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/**
 * Stitch's type (both SIL Open Font License, files in res/font, licences in shared/licenses):
 * Newsreader for editorial headlines, Manrope for everything people read in detail.
 * Neither has Devanagari; Hindi falls back to the phone's Noto Serif / Sans Devanagari.
 */
val Newsreader = FontFamily(newsreader(400), newsreader(500), newsreader(400, italic = true))
val Manrope = FontFamily(manrope(300), manrope(400), manrope(500), manrope(600), manrope(700))

/** Large time readouts (Stitch "numeral-hero": Manrope Light). */
val Numerals: FontFamily = Manrope

/** Editorial headlines: Newsreader, as in the Stitch design. */
val Editorial: FontFamily get() = Newsreader

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0, family: FontFamily = Manrope) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.sp,
)

/** Larger than Material's default: parents read this app, often without glasses. Nothing under 14sp. */
private val MapaType = Typography(
    displayLarge = style(44, 52, 400, -0.8, Editorial),
    displayMedium = style(38, 46, 400, -0.6, Editorial),
    displaySmall = style(34, 42, 400, -0.5, Editorial),
    headlineLarge = style(30, 38, 400, -0.4, Editorial),
    headlineMedium = style(26, 34, 400, -0.3, Editorial),
    headlineSmall = style(23, 30, 400, -0.2, Editorial),
    titleLarge = style(20, 26, 600),
    titleMedium = style(18, 24, 600),
    titleSmall = style(16, 22, 600),
    bodyLarge = style(18, 28, 400),
    bodyMedium = style(16, 24, 400),
    bodySmall = style(15, 22, 400),
    labelLarge = style(16, 20, 600),
    labelMedium = style(15, 20, 500),
    labelSmall = style(14, 18, 500),
)

/** Radius by hierarchy, not one radius for everything. */
private val MapaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LocalMapaColors = staticCompositionLocalOf { LightExtra }

object Mapa {
    val colors: MapaColors
        @Composable @ReadOnlyComposable get() = LocalMapaColors.current
}

@Composable
fun MapaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMapaColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = MapaType, shapes = MapaShapes, content = content)
    }
}

/** One colour per state, always shown next to its label. Unavailable is grey: not good news, not bad news. */
@Composable
@ReadOnlyComposable
fun stateColor(s: RoutineState): Color = Mapa.colors.let {
    when (s) {
        RoutineState.ACTIVE -> it.active
        RoutineState.QUIET -> it.quiet
        RoutineState.UNUSUAL_INACTIVITY -> it.unusual
        RoutineState.MONITORING_UNAVAILABLE -> it.unavailable
        RoutineState.PAUSED_BY_PARENT -> it.paused
    }
}
