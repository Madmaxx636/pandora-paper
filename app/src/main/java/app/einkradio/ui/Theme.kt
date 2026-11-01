package app.einkradio.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import app.einkradio.Prefs

private val Ink = Color.Black
private val Paper = Color.White

// Same blue as the desktop app's icon.
private val Accent = Color(0xFF3A6BFF)
private val AccentDark = Color(0xFF1C3AA8)
private val AccentOnDark = Color(0xFF7C9BFF)

/** Pure black on pure white: no greys, so nothing dithers on a monochrome panel. */
private val WhiteColors = lightColorScheme(
    primary = Ink, onPrimary = Paper,
    secondary = Ink, onSecondary = Paper,
    primaryContainer = Paper, onPrimaryContainer = Ink,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Paper, onSurfaceVariant = Ink,
    outline = Ink, outlineVariant = Ink,
)

/** The same, inverted: white on pure black. */
private val BlackColors = darkColorScheme(
    primary = Paper, onPrimary = Ink,
    secondary = Paper, onSecondary = Ink,
    primaryContainer = Ink, onPrimaryContainer = Paper,
    background = Ink, onBackground = Paper,
    surface = Ink, onSurface = Paper,
    surfaceVariant = Ink, onSurfaceVariant = Paper,
    outline = Paper, outlineVariant = Paper,
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
    val systemDark = isSystemInDarkTheme()
    val dark = when (Prefs.theme) {
        Prefs.THEME_BLACK -> true
        Prefs.THEME_WHITE -> false
        else -> systemDark
    }
    val colors = when (Prefs.theme) {
        Prefs.THEME_WHITE -> WhiteColors
        Prefs.THEME_BLACK -> BlackColors
        else -> if (dark) DarkColors else LightColors
    }

    // Keep the status and navigation bar icons readable against the page.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    val base = LocalDensity.current
    CompositionLocalProvider(
        // Density scales both dp and sp, so the font scale is divided by the interface scale to leave the
        // text size to its own setting.
        LocalDensity provides Density(
            density = base.density * Prefs.uiScale,
            fontScale = base.fontScale * Prefs.textScale / Prefs.uiScale,
        ),
    ) {
        MaterialTheme(colorScheme = colors) {
            // Text and icons that don't set a colour use this. Left alone it is plain black, which
            // disappears on the Black theme and on a dark Color theme.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
        }
    }
}

/** Secondary text colour; stays solid in the monochrome themes so it doesn't render as dithered grey. */
@Composable
fun mutedColor(): Color {
    val fg = MaterialTheme.colorScheme.onBackground
    return if (Prefs.eink) fg else fg.copy(alpha = 0.65f)
}
