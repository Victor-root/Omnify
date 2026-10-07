package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import com.looker.droidify.compose.theme.LocalEdgeToEdge
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.LocalStatusBarContent
import kotlin.math.roundToInt

/**
 * The header behaviour every screen shares: under edge-to-edge the whole header slides off the top as
 * the page scrolls down and comes back on the slightest scroll up (Material 3 "enter always"). Pinned
 * otherwise, and always on TV, where a remote couldn't bring it back. Hand [scaffoldModifier] to the
 * screen's Scaffold and [headerModifier] to the outermost element of its top bar slot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class CollapsibleHeader internal constructor(
    private val scrollBehavior: TopAppBarScrollBehavior,
    enabled: Boolean,
) {
    /** Lets the screen's scrolling content drive the header. */
    val scaffoldModifier: Modifier =
        if (enabled) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier

    /** Slides the header off the top as the content scrolls. */
    val headerModifier: Modifier =
        if (enabled) Modifier.collapsingHeader(scrollBehavior) else Modifier

    /** How many pixels of the header have slid away: 0 while it is fully shown. */
    val collapsedPx: Int get() = -scrollBehavior.state.heightOffset.roundToInt()

    /** Brings the header back, for when what sits below it changes enough that it could otherwise stay
     *  hidden with nothing left to scroll (a short tab, for one). */
    fun reveal() {
        scrollBehavior.state.heightOffset = 0f
    }
}

/** The [CollapsibleHeader] for the screen calling it. Created unconditionally so the call site stays
 *  stable, and only wired up when the header may actually collapse. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberCollapsibleHeader(): CollapsibleHeader {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val enabled = LocalEdgeToEdge.current && !LocalIsTelevision.current
    if (enabled) CollapsingHeaderStatusBar(scrollBehavior)
    return remember(scrollBehavior, enabled) { CollapsibleHeader(scrollBehavior, enabled) }
}

/**
 * Collapses the element this modifies (a screen's whole header) off the top of the screen as the body
 * scrolls, driven by [scrollBehavior]'s enter-always logic. It reports a height that shrinks with the
 * scroll offset, so the Scaffold slides the body up into the freed space, and translates the header by
 * the same amount, so the header leaves and the content slides behind the status bar together.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.collapsingHeader(scrollBehavior: TopAppBarScrollBehavior): Modifier =
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
 * Tells the theme whenever this screen's content, rather than its collapsing header, fills most of the
 * status bar, so the status-bar icons switch from contrasting with the header's accent to contrasting
 * with that content (white icons would otherwise land on a white page in light mode). Driven off the
 * scroll state through a snapshotFlow, so the screen doesn't recompose on every frame of the scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsingHeaderStatusBar(scrollBehavior: TopAppBarScrollBehavior) {
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
