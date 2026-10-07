package com.looker.droidify.compose.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.looker.droidify.datastore.model.BackgroundStyle

/** The backdrop style the user picked, drawn by
 *  [com.looker.droidify.compose.components.FloatingAppCardsBackground]. Provided by [DroidifyTheme]. */
val LocalBackgroundStyle = staticCompositionLocalOf { BackgroundStyle.CONTOUR }
