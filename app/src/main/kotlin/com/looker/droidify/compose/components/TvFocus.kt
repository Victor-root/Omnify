package com.looker.droidify.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.theme.LocalIsTelevision

/**
 * Android TV only: draws an accent outline around a composable while it holds D-pad focus, so the
 * selection is visible without a pointer. Layout-neutral (it only observes focus and draws a border,
 * neither of which changes size), so it can be applied to any element — including ones inside a
 * scrolling list — as a single modifier without the re-scroll jitter that scaling the bounds causes.
 * A no-op on touch devices, leaving the phone UI unchanged.
 *
 * For app tiles the grid uses a scale-and-outline highlight instead (see AppTile); this is the simple
 * outline used for tabs, icon buttons and the like.
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
