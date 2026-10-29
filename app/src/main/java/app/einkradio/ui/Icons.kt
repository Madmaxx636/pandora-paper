package app.einkradio.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small icon set drawn from path data, so no icon library is needed. */
object AppIcons {
    val Play = icon("M8 5v14l11-7z")
    val Pause = icon("M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val SkipNext = icon("M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z")
    // Thumbs-down is this one rotated 180 degrees (same as the desktop app's icons).
    val ThumbUp = icon(
        "M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 " +
            "7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23" +
            ".14-.47.14-.73v-2z",
    )
    val Pin = icon(
        "M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 " +
            "1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z",
    )
    val Shuffle = icon(
        "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 " +
            "20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z",
    )

    /** The "P with a note head" mark from the desktop app icon, in a 512 unit box. */
    val Logo: ImageVector = ImageVector.Builder(
        defaultWidth = 512.dp, defaultHeight = 512.dp, viewportWidth = 512f, viewportHeight = 512f,
    ).apply {
        addGroup(translationX = 14f, translationY = 8f)
        addPath(
            PathParser().parsePathString(
                "M214 100 H300 C390 100 432 150 432 215 C432 280 390 330 300 330 H266 V352 H214 Z " +
                    "M266 156 H298 C340 156 372 180 372 215 C372 250 340 274 298 274 H266 Z",
            ).toNodes(),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.White),
        )
        addPath(
            PathParser().parsePathString("M128 372 a62 62 0 1 0 124 0 a62 62 0 1 0 -124 0 Z").toNodes(),
            fill = SolidColor(Color.White),
        )
        clearGroup()
    }.build()

    private fun icon(path: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black)).build()
}
