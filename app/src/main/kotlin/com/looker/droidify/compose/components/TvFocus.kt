package com.looker.droidify.compose.components

import android.util.Log
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
 * Temporary D-pad navigation diagnostics: when [debugLabel] is set on a `tvFocus*`/`tvReadable`/
 * `tvPageScroll` call, every focus gain and every D-pad key seen by [Modifier.tvDpadKeyLog] is logged
 * under this tag with a timestamp, so a logcat capture (`adb logcat -s TvFocusDebug`) shows exactly
 * which element gained focus after which key press — far more precise than a verbal description for
 * chasing an intermittent "down jumps back up" / "left jumps to the bottom" style bug. Remove once the
 * underlying issue is found; not meant to ship long-term (labels are opt-in and default to null/no-op
 * everywhere they aren't explicitly passed, so leaving this in is otherwise inert).
 */
private const val TV_FOCUS_DEBUG_TAG = "TvFocusDebug"

private fun tvFocusDebugLog(message: String) {
    Log.d(TV_FOCUS_DEBUG_TAG, message)
}

/**
 * Android TV D-pad navigation diagnostics: logs every key event this node's `onPreviewKeyEvent` sees,
 * before anything else on the screen gets a chance to act on or consume it — attach at a screen's root
 * (e.g. the `Scaffold`'s own modifier) to get one authoritative timeline of every D-pad press, to
 * correlate against the `tvFocus*` modifiers' own "gained focus" logs elsewhere on the same screen. See
 * [tvFocusDebugLog]. Never consumes the event (always returns false), so it can't change behavior.
 */
fun Modifier.tvDpadKeyLog(screenLabel: String): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown) {
        tvFocusDebugLog("[$screenLabel] KEY ${event.key} at ${System.currentTimeMillis()}")
    }
    false
}

/**
 * Android TV only: the focused element scales up and is lifted above its neighbours. Draw-only
 * (graphicsLayer + zIndex), so layout never moves; the zIndex means that when the zoom does spill past
 * the element's box it pops *in front* of its neighbours rather than appearing to collide with them.
 * The modest default keeps that spill small enough to sit within normal row gaps. A no-op on touch. Not
 * suited to full-width rows, whose scaled width would overflow the screen — use [tvFocusFill] there.
 */
@Composable
fun Modifier.tvFocusScale(focusedScale: Float = 1.1f, debugLabel: String? = null): Modifier {
    if (!LocalIsTelevision.current) return this
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "tvFocusScale")
    return this
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused && debugLabel != null) {
                tvFocusDebugLog("FOCUS -> $debugLabel (tvFocusScale) at ${System.currentTimeMillis()}")
            }
        }
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
 *
 * Left/right are consumed (not left to Compose's own focus search) rather than merely left alone: a
 * full-width block like this has no horizontal sibling to move to, so a default 2D search's own
 * distance heuristic falls back to whatever candidate is geometrically closest *anywhere on screen* —
 * confirmed with a real device log to jump all the way to the top bar's back button from a block
 * halfway down a long description, which is never useful (there's nothing intentionally reachable that
 * way from here) and reads as the D-pad randomly teleporting focus away.
 */
@Composable
fun Modifier.tvReadable(debugLabel: String? = null): Modifier {
    if (!LocalIsTelevision.current) return this
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val alpha by animateFloatAsState(if (focused) 0.10f else 0f, label = "tvReadable")
    return this
        .clip(shape)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused && debugLabel != null) {
                tvFocusDebugLog("FOCUS -> $debugLabel (tvReadable) at ${System.currentTimeMillis()}")
            }
        }
        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), shape)
        .onPreviewKeyEvent { event ->
            val blocked = event.type == KeyEventType.KeyDown &&
                (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
            if (blocked && debugLabel != null) {
                tvFocusDebugLog(
                    "$debugLabel (tvReadable) blocks ${event.key} at ${System.currentTimeMillis()}",
                )
            }
            blocked
        }
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
fun Modifier.tvPageScroll(
    scrollState: ScrollState,
    stepPx: Int,
    debugLabel: String? = null,
    // Explicit hand-off targets for once a bound (top/bottom) is reached, instead of leaving Compose's
    // own default focus search to find one. This node's layout bounds span its full (often off-screen-
    // tall) content rather than just the visible viewport, and that default search reasons about those
    // oversized bounds when picking/settling on a candidate — confirmed via real logcat to silently
    // scroll the page back up by ~300px right after paging to the very bottom, even though it reported
    // having "moved focus" and no focus change was actually observed. Null means "nothing to hand off
    // to here" — the event is still consumed, just without moving focus or touching scroll further.
    downTarget: FocusRequester? = null,
    upTarget: FocusRequester? = null,
): Modifier {
    if (!LocalIsTelevision.current) return this
    val scope = rememberCoroutineScope()
    return this
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || stepPx <= 0) {
                false
            } else if (event.key == Key.DirectionDown && scrollState.value < scrollState.maxValue) {
                if (debugLabel != null) {
                    tvFocusDebugLog(
                        "$debugLabel (tvPageScroll) consumes DOWN, scrollState=${scrollState.value}/" +
                            "${scrollState.maxValue} at ${System.currentTimeMillis()}",
                    )
                }
                scope.launch { scrollState.animateScrollBy(stepPx.toFloat()) }
                true
            } else if (event.key == Key.DirectionUp && scrollState.value > 0) {
                if (debugLabel != null) {
                    tvFocusDebugLog(
                        "$debugLabel (tvPageScroll) consumes UP, scrollState=${scrollState.value}/" +
                            "${scrollState.maxValue} at ${System.currentTimeMillis()}",
                    )
                }
                scope.launch { scrollState.animateScrollBy(-stepPx.toFloat()) }
                true
            } else if (event.key == Key.DirectionDown || event.key == Key.DirectionUp) {
                // Hands off focus explicitly rather than leaving it to Compose's own default focus
                // search — see this function's own doc comment on downTarget/upTarget for why. The
                // "bring the newly focused item into view" scroll this can still trigger is suppressed at
                // the screen level (see ExternalAppDetailScreen's own BringIntoViewSpec override) rather
                // than fought here, since that reflex fires on every focus change below this point, not
                // just this one hand-off.
                val target = if (event.key == Key.DirectionDown) downTarget else upTarget
                val moved = target?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
                if (debugLabel != null) {
                    tvFocusDebugLog(
                        "$debugLabel (tvPageScroll) at bound, requestFocus(${event.key})=$moved, scrollState=" +
                            "${scrollState.value}/${scrollState.maxValue} at ${System.currentTimeMillis()}",
                    )
                }
                true
            } else {
                false
            }
        }
        .onFocusChanged {
            if (it.isFocused && debugLabel != null) {
                tvFocusDebugLog("FOCUS -> $debugLabel (tvPageScroll) at ${System.currentTimeMillis()}")
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
fun Modifier.tvDpadDownTo(target: FocusRequester, debugLabel: String? = null): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            val result = runCatching { target.requestFocus() }
            if (debugLabel != null) {
                tvFocusDebugLog(
                    "$debugLabel (tvDpadDownTo) redirects DOWN, requestFocus success=" +
                        "${result.isSuccess} at ${System.currentTimeMillis()}",
                )
            }
            result.isSuccess
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
    debugLabel: String? = null,
): Modifier {
    if (!LocalIsTelevision.current) return this
    var focused by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (focused) 0.16f else 0f, label = "tvFocusFill")
    return this
        .clip(shape)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused && debugLabel != null) {
                tvFocusDebugLog("FOCUS -> $debugLabel (tvFocusFill) at ${System.currentTimeMillis()}")
            }
        }
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
