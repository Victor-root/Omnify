package com.looker.droidify.compose.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay

/** How long an updated app's tile takes to shrink and fade out of the Updates grid. */
private const val DEPARTURE_MS = 450

/** How small a departing tile gets before it is gone. */
private const val DEPARTED_SCALE = 0.6f

/** One tile of a grid whose items can leave it gracefully: [leaving] while it shrinks away. */
class Departing<T>(val item: T, val leaving: Boolean)

/**
 * [items] as a grid shows them, with each item that just left the list kept in its place, [leaving],
 * for as long as it takes to shrink and fade out ([updateDeparture]), then dropped, when the tiles after
 * it close the gap. Worked out as the list changes rather than a frame later, so nothing flickers.
 * Without [enabled], and when it has just been turned on (another tab's list being replaced by this
 * one), items leave at once, as they always did.
 */
@Composable
fun <T> rememberDepartures(items: List<T>, key: (T) -> Any, enabled: Boolean): List<Departing<T>> {
    val last = remember { LastShown<T>() }
    val merged = remember(items, enabled) {
        val previous = last.tiles.takeIf { enabled && last.enabled }.orEmpty()
        val present = items.mapTo(HashSet(), key)
        val merged = items.mapTo(ArrayList()) { Departing(it, leaving = false) }
        // Each one that left goes back where it was, so it shrinks in place rather than jumping.
        previous.forEachIndexed { index, tile ->
            if (key(tile.item) !in present) {
                merged.add(index.coerceAtMost(merged.size), Departing(tile.item, leaving = true))
            }
        }
        merged
    }
    var settled by remember { mutableStateOf<List<Departing<T>>?>(null) }
    val shown = if (settled === merged) merged.filterNot { it.leaving } else merged
    SideEffect {
        last.tiles = shown
        last.enabled = enabled
    }
    LaunchedEffect(merged) {
        if (merged.any { it.leaving }) {
            delay(DEPARTURE_MS.toLong())
            settled = merged
        }
    }
    return shown
}

/** What [rememberDepartures] showed last, to tell which items have just left. */
private class LastShown<T> {
    var tiles: List<Departing<T>> = emptyList()
    var enabled = false
}

/**
 * How a tile moves in the Updates grid: while [leaving] it shrinks and fades out where it stands, and
 * once it is gone the tiles after it glide into its place, one app at a time as each finishes, instead
 * of vanishing at once and the grid jumping. Nothing fades in, so the list opens as it always did.
 */
@Composable
fun LazyGridItemScope.updateDeparture(leaving: Boolean): Modifier {
    val presence = animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(durationMillis = DEPARTURE_MS, easing = FastOutSlowInEasing),
        label = "updateDeparture",
    )
    return Modifier
        .animateItem(
            fadeInSpec = null,
            placementSpec = spring(
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntOffset.VisibilityThreshold,
            ),
            fadeOutSpec = null,
        )
        .graphicsLayer {
            val shown = presence.value
            alpha = shown
            val scale = DEPARTED_SCALE + (1f - DEPARTED_SCALE) * shown
            scaleX = scale
            scaleY = scale
        }
}
