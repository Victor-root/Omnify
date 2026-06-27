package com.looker.droidify.compose.theme

/**
 * The accent color swatches shown on the "Palette" tab of the theme picker.
 *
 * These are the Material colours used by the maintainer's MaterialFiles fork (mostly the _500 shades;
 * red is the deeper #D32F2F). They're applied raw (vivid) — see [DroidifyTheme]. The app's default
 * accent is green (#4CAF50), one of these entries — see
 * [com.looker.droidify.datastore.DEFAULT_THEME_COLOR].
 */
val accentColorPalette: List<Int> = listOf(
    0xFFD32F2F.toInt(), // red (default)
    0xFFE91E63.toInt(), // pink
    0xFF9C27B0.toInt(), // purple
    0xFF673AB7.toInt(), // deep purple
    0xFF3F51B5.toInt(), // indigo
    0xFF2196F3.toInt(), // blue
    0xFF03A9F4.toInt(), // light blue
    0xFF00BCD4.toInt(), // cyan
    0xFF009688.toInt(), // teal
    0xFF4CAF50.toInt(), // green
    0xFF8BC34A.toInt(), // light green
    0xFFCDDC39.toInt(), // lime
    0xFFFFEB3B.toInt(), // yellow
    0xFFFFC107.toInt(), // amber
    0xFFFF9800.toInt(), // orange
    0xFFFF5722.toInt(), // deep orange
    0xFF795548.toInt(), // brown
    0xFF9E9E9E.toInt(), // gray
    0xFF607D8B.toInt(), // blue gray
)
