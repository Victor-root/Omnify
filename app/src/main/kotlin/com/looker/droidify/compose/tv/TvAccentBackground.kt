package com.looker.droidify.compose.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.theme.LocalBackgroundStyle
import com.looker.droidify.datastore.model.BackgroundStyle

/** The backdrop sits ~1.7x the phone's strength so it reads on a big screen, where the content is
 *  sparser and further away. */
private const val TV_BACKDROP_INTENSITY = 1.7f

/** How much black goes over the backdrop in the dark themes, so the content stays easy to read from the
 *  couch whichever style is picked. */
private const val TV_BACKDROP_DIM = 0.35f

/**
 * The Android TV screen background: the backdrop the user picked in the settings (see
 * [FloatingAppCardsBackground]), tinted from their accent and following the light, dark and black
 * themes, over the theme background.
 *
 * Meant to be the first (bottom) child of a full-screen container, with the real content drawn on top.
 */
@Composable
fun TvAccentBackground(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    // Every style but Aurora starts by painting its own opaque base over the whole box, which would only
    // cover this fill.
    val fill = if (LocalBackgroundStyle.current == BackgroundStyle.AURORA) Modifier.background(background) else Modifier
    Box(modifier = modifier.fillMaxSize().then(fill)) {
        FloatingAppCardsBackground(enableOnTelevision = true, intensity = TV_BACKDROP_INTENSITY)
        // Left alone in the light theme, where a darker backdrop would only cut the contrast of the
        // dark text on it.
        if (background.luminance() < 0.5f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = TV_BACKDROP_DIM)))
        }
    }
}
