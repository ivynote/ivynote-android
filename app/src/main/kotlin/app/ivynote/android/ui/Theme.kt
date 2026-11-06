package app.ivynote.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The web client's design tokens (`client/packages/ui/src/app.css`, guide
 * 10.2): a desk (canvas) with a sheet of paper (surface) on it, warm-neutral
 * inks, ivy green only for links, selection and switches, ink for primary buttons.
 */
@Immutable
data class IvyColors(
    val canvas: Color,
    val surface: Color,
    val sunken: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val hover: Color,
    val ivy: Color,
    val ivySoft: Color,
    val onIvy: Color,
    val ivyInk: Color,
    val warn: Color,
    val warnInk: Color,
    val warnSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val ok: Color,
    val dark: Boolean,
)

private fun rgb(r: Int, g: Int, b: Int) = Color(r, g, b)

val LightIvy = IvyColors(
    canvas = rgb(247, 248, 249), surface = rgb(255, 255, 255), sunken = rgb(241, 243, 245),
    ink = rgb(28, 31, 34), ink2 = rgb(70, 75, 80), muted = rgb(112, 118, 124), faint = rgb(160, 166, 172),
    line = rgb(230, 233, 236), hover = rgb(234, 237, 240),
    ivy = rgb(40, 122, 82), ivySoft = rgb(229, 243, 235), onIvy = rgb(255, 255, 255), ivyInk = rgb(27, 92, 60),
    warn = rgb(190, 134, 48), warnInk = rgb(110, 78, 26), warnSoft = rgb(252, 244, 228),
    danger = rgb(176, 62, 54), dangerSoft = rgb(252, 235, 233), ok = rgb(74, 142, 104), dark = false,
)

val DarkIvy = IvyColors(
    canvas = rgb(16, 18, 20), surface = rgb(25, 27, 30), sunken = rgb(34, 37, 41),
    ink = rgb(232, 235, 238), ink2 = rgb(190, 195, 200), muted = rgb(138, 144, 150), faint = rgb(96, 101, 107),
    line = rgb(44, 48, 52), hover = rgb(38, 41, 45),
    ivy = rgb(86, 180, 130), ivySoft = rgb(28, 50, 39), onIvy = rgb(10, 28, 18), ivyInk = rgb(150, 224, 182),
    warn = rgb(214, 170, 98), warnInk = rgb(232, 205, 158), warnSoft = rgb(48, 40, 26),
    danger = rgb(226, 128, 116), dangerSoft = rgb(58, 33, 31), ok = rgb(124, 190, 150), dark = true,
)

val LocalIvy = staticCompositionLocalOf { LightIvy }

object Ivy {
    val colors: IvyColors @Composable get() = LocalIvy.current
}

/** Titles and quotes in a serif, the interface in the system sans (guide 10.3). */
val Serif: FontFamily = FontFamily.Serif

@Composable
fun IvyTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkIvy else LightIvy
    val scheme = if (c.dark) {
        darkColorScheme(
            primary = c.ivy, onPrimary = c.onIvy, primaryContainer = c.ivySoft, onPrimaryContainer = c.ivyInk,
            secondary = c.ink2, onSecondary = c.canvas, secondaryContainer = c.sunken, onSecondaryContainer = c.ink,
            background = c.canvas, onBackground = c.ink, surface = c.canvas, onSurface = c.ink,
            surfaceVariant = c.sunken, onSurfaceVariant = c.muted, surfaceContainer = c.surface,
            surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.sunken,
            surfaceContainerLowest = c.canvas, outline = c.faint, outlineVariant = c.line,
            error = c.danger, errorContainer = c.dangerSoft, inverseSurface = c.ink, inverseOnSurface = c.canvas,
        )
    } else {
        lightColorScheme(
            primary = c.ivy, onPrimary = c.onIvy, primaryContainer = c.ivySoft, onPrimaryContainer = c.ivyInk,
            secondary = c.ink2, onSecondary = c.canvas, secondaryContainer = c.sunken, onSecondaryContainer = c.ink,
            background = c.canvas, onBackground = c.ink, surface = c.canvas, onSurface = c.ink,
            surfaceVariant = c.sunken, onSurfaceVariant = c.muted, surfaceContainer = c.surface,
            surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface, surfaceContainerHighest = c.sunken,
            surfaceContainerLowest = c.canvas, outline = c.faint, outlineVariant = c.line,
            error = c.danger, errorContainer = c.dangerSoft, inverseSurface = c.ink, inverseOnSurface = c.canvas,
        )
    }
    val base = Typography()
    val type = base.copy(
        headlineSmall = base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    )
    CompositionLocalProvider(LocalIvy provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

val SerifTitle = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold)
