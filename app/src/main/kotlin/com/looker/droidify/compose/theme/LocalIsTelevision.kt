package com.looker.droidify.compose.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the UI is running on Android TV. Provided once by [DroidifyTheme] and read by TV-only
 * behaviour (visible D-pad focus, overscan margins). Defaults to false, so the touch UI and previews
 * are never affected.
 */
val LocalIsTelevision = staticCompositionLocalOf { false }
