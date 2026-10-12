package com.looker.droidify.compose.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.looker.droidify.compose.components.FloatingAppCardsBackground

/** The backdrop sits ~1.7x the phone's strength so it reads on a big screen, where the content is
 *  sparser and further away. */
private const val TV_BACKDROP_INTENSITY = 1.7f

/**
 * The Android TV screen background: the backdrop the user picked in the settings (see
 * [FloatingAppCardsBackground]), tinted from their accent and following the light, dark and black
 * themes, over the theme background.
 *
 * Meant to be the first (bottom) child of a full-screen container, with the real content drawn on top.
 */
@Composable
fun TvAccentBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        FloatingAppCardsBackground(enableOnTelevision = true, intensity = TV_BACKDROP_INTENSITY)
    }
}
