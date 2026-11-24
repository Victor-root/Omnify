package com.looker.droidify.compose.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
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
import com.looker.droidify.compose.theme.EmphasizedDecelerate
import kotlinx.coroutines.delay

/** How long an app's tile takes to grow into the Updates grid, or to shrink and fade out of it. */
private const val DEPARTURE_MS = 450

/** How small a departing tile gets before it is gone. */
private const val DEPARTED_SCALE = 0.6f

/** How small an arriving tile starts, nearer its size than a departing one ends: it settles in. */
private const val ARRIVING_SCALE = 0.85f

/**
 * One tile of a grid whose items can come and go gracefully: [arriving] while it grows in, [leaving]
 * while it shrinks away.
 */
class Departing<T>(val item: T, val leaving: Boolean, val arriving: Boolean = false)

/**
 * [items] as a grid shows them, with each item that just left the list kept in its place, [leaving],
 * for as long as it takes to shrink and fade out ([updateDeparture]), then dropped, when the tiles after
 * it close the gap, and each item that just joined it [arriving] for as long as it takes to grow in.
 * Worked out as the list changes rather than a frame later, so nothing flickers. Once that time is up a
 * tile is plain again, so one only scrolled to later doesn't play an entrance it was not seen making.
 * Without [enabled], and when it has just been turned on (another tab's list being replaced by this
 * one), items come and leave at once, as they always did.
 */
@Composable
fun <T> rememberDepartures(items: List<T>, key: (T) -> Any, enabled: Boolean): List<Departing<T>> {
    val last = remember { LastShown<T>() }
    val merged = remember(items, enabled) {
        val animating = enabled && last.enabled
        val previous = last.tiles.takeIf { animating }.orEmpty()
        val present = items.mapTo(HashSet(), key)
        val shownBefore = previous.mapTo(HashSet()) { key(it.item) }
        val merged = items.mapTo(ArrayList()) {
            Departing(it, leaving = false, arriving = animating && key(it) !in shownBefore)
        }
        // Each one that left goes back where it was, so it shrinks in place rather than jumping.
        previous.forEachIndexed { index, tile ->
            if (key(tile.item) !in present) {
                merged.add(index.coerceAtMost(merged.size), Departing(tile.item, leaving = true))
            }
        }
        merged
    }
    var settled by remember { mutableStateOf<List<Departing<T>>?>(null) }
    val shown = remember(merged, settled) {
        if (settled !== merged) {
            merged
        } else {
            merged.mapNotNull { tile ->
                when {
                    tile.leaving -> null
                    tile.arriving -> Departing(tile.item, leaving = false)
                    else -> tile
                }
            }
        }
    }
    SideEffect {
        last.tiles = shown
        last.enabled = enabled
    }
    LaunchedEffect(merged) {
        if (merged.any { it.leaving || it.arriving }) {
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
 * How a tile moves in the Updates grid: [arriving], it grows and fades in where it lands, the tiles
 * after it gliding aside to make room, as apps with an update turn up one after another; while
 * [leaving] it shrinks and fades out where it stands, and once it is gone the tiles after it glide into
 * its place, one app at a time as each finishes, instead of vanishing at once and the grid jumping. A
 * list merely opened shows its tiles as they are, without an entrance.
 */
@Composable
fun LazyGridItemScope.updateDeparture(leaving: Boolean, arriving: Boolean): Modifier {
    // Read only when drawn, so a tile coming or going doesn't recompose on every frame.
    val presence = remember { Animatable(if (arriving) 0f else 1f) }
    LaunchedEffect(leaving) {
        val easing = if (leaving) FastOutSlowInEasing else EmphasizedDecelerate
        presence.animateTo(if (leaving) 0f else 1f, tween(durationMillis = DEPARTURE_MS, easing = easing))
    }
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
            val from = if (leaving) DEPARTED_SCALE else ARRIVING_SCALE
            val scale = from + (1f - from) * shown
            scaleX = scale
            scaleY = scale
        }
}
