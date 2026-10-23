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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

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

/**
 * An image loaded by Coil over a [Skeleton] while its load is under way, in place of an empty (on TV,
 * white) square. One already in memory never shows it. Meant for a Box, both filling the same [modifier].
 */
@Composable
fun AsyncImageWithSkeleton(
    model: Any?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    var loading by remember(model) { mutableStateOf(false) }
    if (loading) Skeleton(modifier)
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        onLoading = { loading = true },
        onSuccess = {
            loading = false
            onSuccess?.invoke(it)
        },
        onError = {
            loading = false
            onError?.invoke(it)
        },
        contentScale = contentScale,
        filterQuality = filterQuality,
    )
}
