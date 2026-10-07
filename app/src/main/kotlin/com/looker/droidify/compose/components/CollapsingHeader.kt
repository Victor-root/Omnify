package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import com.looker.droidify.compose.theme.LocalStatusBarContent
import kotlin.math.roundToInt

/**
 * Collapses the element this modifies (a screen's whole header) off the top of the screen as the body
 * scrolls, driven by [scrollBehavior]'s enter-always logic. It reports a height that shrinks with the
 * scroll offset, so the Scaffold slides the body up into the freed space, and translates the header by
 * the same amount, so the header leaves and the content slides behind the status bar together.
 *
 * Pair it with [CollapsingHeaderStatusBar], so the status-bar icons follow what is behind them.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.collapsingHeader(scrollBehavior: TopAppBarScrollBehavior): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        // The header can collapse by its full height; tell the scroll behaviour so it clamps there.
        scrollBehavior.state.heightOffsetLimit = -placeable.height.toFloat()
        val offsetY = scrollBehavior.state.heightOffset.roundToInt() // 0 (shown) .. -height (hidden)
        val measuredHeight = (placeable.height + offsetY).coerceAtLeast(0)
        layout(placeable.width, measuredHeight) {
            placeable.place(0, offsetY)
        }
    }

/**
 * Tells the theme whenever this screen's content, rather than its [collapsingHeader], fills most of the
 * status bar, so the status-bar icons switch from contrasting with the header's accent to contrasting
 * with that content (white icons would otherwise land on a white page in light mode). Driven off the
 * scroll state through a snapshotFlow, so the screen doesn't recompose on every frame of the scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingHeaderStatusBar(scrollBehavior: TopAppBarScrollBehavior) {
    val statusBarContent = LocalStatusBarContent.current
    val statusBarPx = WindowInsets.statusBars.getTop(LocalDensity.current)
    val owner = remember { Any() }
    LaunchedEffect(scrollBehavior, statusBarContent, statusBarPx) {
        try {
            snapshotFlow {
                val headerHeightPx = -scrollBehavior.state.heightOffsetLimit
                val headerBottomPx = scrollBehavior.state.heightOffset + headerHeightPx
                headerHeightPx > 0f && statusBarPx > 0 && statusBarPx - headerBottomPx > statusBarPx / 2f
            }.collect { showsContent -> statusBarContent.publish(owner, showsContent) }
        } finally {
            statusBarContent.release(owner)
        }
    }
}
