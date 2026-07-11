package com.looker.droidify.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.looker.droidify.compose.theme.LocalIsTelevision
import kotlinx.coroutines.launch

/**
 * Android TV overscan safe-area inset: every screen with a scrollable grid/list should add this to its
 * content padding so a focused item's scaled-up highlight near a screen edge isn't clipped by the TV's
 * overscan. Shared so every screen uses the same inset instead of drifting apart.
 */
val TvOverscan = 24.dp

/**
 * Android TV only: the focused element scales up and is lifted above its neighbours. Draw-only
 * (graphicsLayer + zIndex), so layout never moves; the zIndex means that when the zoom does spill past
 * the element's box it pops *in front* of its neighbours rather than appearing to collide with them.
 * The modest default keeps that spill small enough to sit within normal row gaps. A no-op on touch. Not
 * suited to full-width rows, whose scaled width would overflow the screen — use [tvFocusFill] there.
 */
@Composable
fun Modifier.tvFocusScale(focusedScale: Float = 1.1f): Modifier {
    if (!LocalIsTelevision.current) return this
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "tvFocusScale")
    return this
        .onFocusChanged { focused = it.isFocused }
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Android TV only: turns a non-interactive block (a long description paragraph, the what's-new text…)
 * into a D-pad focus stop with a faint highlight. Without it the remote skips straight over the text
 * from one button to the next; as a focus stop, landing on it scrolls it into view so it can be read.
 * A no-op on touch.
 */
@Composable
fun Modifier.tvReadable(): Modifier {
    if (!LocalIsTelevision.current) return this
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val alpha by animateFloatAsState(if (focused) 0.10f else 0f, label = "tvReadable")
    return this
        .clip(shape)
        .onFocusChanged { focused = it.isFocused }
        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), shape)
        .focusable()
}

/**
 * Android TV only: makes a tall, non-interactive block (e.g. a README rendered in a WebView) a single
 * D-pad focus stop that pages the surrounding [scrollState] up/down by [stepPx] instead of stepping
 * through the links and images inside it. "Down" pages down until the page bottom, then releases focus;
 * "up" pages up until the page top, then releases (so focus can return to the controls above). A no-op
 * on touch. The block itself should be made non-focusable internally (e.g. a non-focusable WebView) so
 * focus lands here, not on its contents.
 */
@Composable
fun Modifier.tvPageScroll(scrollState: ScrollState, stepPx: Int): Modifier {
    if (!LocalIsTelevision.current) return this
    val scope = rememberCoroutineScope()
    return this
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || stepPx <= 0) {
                false
            } else if (event.key == Key.DirectionDown && scrollState.value < scrollState.maxValue) {
                scope.launch { scrollState.animateScrollBy(stepPx.toFloat()) }
                true
            } else if (event.key == Key.DirectionUp && scrollState.value > 0) {
                scope.launch { scrollState.animateScrollBy(-stepPx.toFloat()) }
                true
            } else {
                false
            }
        }
        .focusable()
}

/**
 * D-pad bridge: when the focused descendant is at the bottom of this container and the user presses
 * "down", hand focus to [target] (typically the screen content below a top bar or tab row). Material3's
 * top bars and tab rows don't release focus downward on their own, leaving a remote user stuck in the
 * header; this is the explicit escape hatch. A no-op on touch (touch never sends D-pad keys), so it
 * needs no television guard.
 */
fun Modifier.tvDpadDownTo(target: FocusRequester): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            runCatching { target.requestFocus() }.isSuccess
        } else {
            false
        }
    }

/**
 * Android TV only: fills the background of a focused composable with a soft accent tint, aligned to
 * the element's own bounds and [shape]. Layout-neutral (only a background draw, no size change), so it
 * is safe inside scrolling lists and is the right choice for full-width rows (section headers, list
 * rows) where a scale would overflow the screen edges. A no-op on touch.
 *
 * The [clip] is applied first, above the caller's `clickable`, so the focused element's default
 * Material focus indication (a grey state layer drawn on the clickable's own rectangular bounds) is
 * forced into the same rounded [shape]; otherwise that grey layer stays a hard square regardless of
 * the fill shape.
 */
@Composable
fun Modifier.tvFocusFill(
    shape: Shape,
    color: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    if (!LocalIsTelevision.current) return this
    var focused by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (focused) 0.16f else 0f, label = "tvFocusFill")
    return this
        .clip(shape)
        .onFocusChanged { focused = it.isFocused }
        .background(color.copy(alpha = alpha), shape)
}

/**
 * Android TV only: draws an accent outline around a focused composable. Layout-neutral, so it is safe
 * in scrolling lists. A no-op on touch. Still used on the detail screen; the home screen now uses
 * [tvFocusScale] / [tvFocusFill] for a cleaner look.
 */
@Composable
fun Modifier.tvFocusOutline(
    shape: Shape,
    color: Color = MaterialTheme.colorScheme.primary,
    width: Dp = 3.dp,
): Modifier {
    if (!LocalIsTelevision.current) return this
    var focused by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (focused) 1f else 0f, label = "tvFocusOutline")
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(width, color.copy(alpha = alpha), shape)
}
