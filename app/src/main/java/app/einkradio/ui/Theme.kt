package app.einkradio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.einkradio.Prefs

private val Ink = Color.Black
private val Paper = Color.White

// Same blue as the desktop app's icon.
private val Accent = Color(0xFF3A6BFF)
private val AccentDark = Color(0xFF1C3AA8)
private val AccentOnDark = Color(0xFF7C9BFF)

/** Pure black on pure white: no greys, so nothing dithers on a monochrome panel. */
private val EinkColors = lightColorScheme(
    primary = Ink, onPrimary = Paper,
    secondary = Ink, onSecondary = Paper,
    primaryContainer = Paper, onPrimaryContainer = Ink,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Paper, onSurfaceVariant = Ink,
    outline = Ink, outlineVariant = Ink,
)

private val LightColors = lightColorScheme(
    primary = Accent, onPrimary = Paper,
    background = Paper, onBackground = Color(0xFF14161C),
    surface = Paper, onSurface = Color(0xFF14161C),
    outline = Color(0xFFB9BDC9),
)

private val DarkColors = darkColorScheme(
    primary = AccentOnDark, onPrimary = Color(0xFF0B1330),
    background = Color(0xFF101218), onBackground = Color(0xFFF1F2F6),
    surface = Color(0xFF101218), onSurface = Color(0xFFF1F2F6),
    outline = Color(0xFF4A4F5E),
)

val AccentTop = Accent
val AccentBottom = AccentDark

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = when {
        Prefs.eink -> EinkColors
        isSystemInDarkTheme() -> DarkColors
        else -> LightColors
    }
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * Prefs.textScale),
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** Secondary text colour; stays solid in e-ink mode so it doesn't render as dithered grey. */
@Composable
fun mutedColor(): Color {
    val fg = MaterialTheme.colorScheme.onBackground
    return if (Prefs.eink) fg else fg.copy(alpha = 0.65f)
}
