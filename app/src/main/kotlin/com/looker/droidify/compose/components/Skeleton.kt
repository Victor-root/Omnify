package com.looker.droidify.compose.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Stands in for a picture still loading: the room it will take, gently pulsing, so a slow load reads as
 * under way rather than as an empty space. The pulse only changes the layer's alpha while drawing, so it
 * never recomposes or lays anything out again. The caller sizes and clips it.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.28f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 800), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            // The text colour, faded: stands out on any background, light or dark, where a surface tone
            // nearly matched the list behind it.
            .background(MaterialTheme.colorScheme.onSurface),
    )
}
