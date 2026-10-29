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

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = when {
        Prefs.eink -> EinkColors
        isSystemInDarkTheme() -> darkColorScheme()
        else -> lightColorScheme()
    }
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density, base.fontScale * Prefs.textScale),
    ) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
