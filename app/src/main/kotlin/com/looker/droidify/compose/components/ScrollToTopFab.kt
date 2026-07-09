package com.looker.droidify.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R
import kotlinx.coroutines.launch

/**
 * A FAB that appears once [scrollState] has scrolled a fair distance down and smooth-scrolls it
 * back to the top on tap. Shared by the F-Droid and external app detail screens: both are a single
 * long column (a tall header card, then a description/README below it) where jumping back to the
 * header without a manual long swipe is worth offering.
 */
@Composable
fun ScrollToTopFab(scrollState: ScrollState) {
    val coroutineScope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = scrollState.value > 800,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        FloatingActionButton(
            onClick = { coroutineScope.launch { scrollState.animateScrollTo(0) } },
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.scroll_to_top),
            )
        }
    }
}

/** Upward scroll speed (px/s) for [ScrollToTopFab] (grid overload). Fast enough to feel snappy from a
 *  few screens down, and since it's a constant speed the trip just takes proportionally longer the
 *  deeper you start — always a genuine, seamless scroll rather than a jump. */
private const val SCROLL_UP_PX_PER_SECOND = 30_000f

/**
 * Same FAB, for the home screen's app grid ([LazyGridState] instead of a plain [ScrollState]).
 *
 * A [LazyGridState] has no "smooth scroll to the top pixel" the way a [ScrollState] does:
 * [LazyGridState.animateScrollToItem] scrolls item-by-item, which visibly snapped between this grid's
 * very differently-sized items (huge carousels vs small tiles), and a decay fling decelerates and stops
 * short of the top from a deep position (leaving a teleport for the remainder). So instead we drive the
 * scroll ourselves at a constant pixel velocity, one animation frame at a time, until the grid genuinely
 * can't scroll up any further — one continuous motion all the way to the top from any starting depth,
 * with no per-item snapping and no instant jump.
 */
@Composable
fun ScrollToTopFab(gridState: LazyGridState) {
    val coroutineScope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = gridState.canScrollBackward,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        FloatingActionButton(
            onClick = {
                coroutineScope.launch {
                    gridState.scroll {
                        var lastFrameNanos = 0L
                        while (gridState.canScrollBackward) {
                            // Advance by the distance one constant-speed frame covers. Keyed off the real
                            // elapsed frame time so the speed stays the same regardless of frame rate.
                            val stepPx = withFrameNanos { now ->
                                val deltaSeconds =
                                    if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / 1e9f
                                lastFrameNanos = now
                                SCROLL_UP_PX_PER_SECOND * deltaSeconds
                            }
                            if (stepPx <= 0f) continue
                            // Negative = towards the top. scrollBy returns what it actually consumed; 0
                            // means we've hit the top edge (belt-and-braces with the while condition).
                            if (scrollBy(-stepPx) == 0f) break
                        }
                    }
                }
            },
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.scroll_to_top),
            )
        }
    }
}
