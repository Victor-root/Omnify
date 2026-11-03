package com.looker.droidify.compose.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/** How long an updated app's tile takes to fade out of the Updates grid. */
private const val DEPARTURE_FADE_MS = 400

/**
 * How a tile leaves the Updates grid once its app is updated: it fades out while the tiles after it
 * glide into the place it leaves, one app at a time as each finishes, instead of vanishing at once and
 * the grid jumping. Nothing fades in, so the list opens as it always did.
 */
fun LazyGridItemScope.updateDeparture(): Modifier = Modifier.animateItem(
    fadeInSpec = null,
    placementSpec = spring(
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    ),
    fadeOutSpec = tween(durationMillis = DEPARTURE_FADE_MS, easing = FastOutSlowInEasing),
)
