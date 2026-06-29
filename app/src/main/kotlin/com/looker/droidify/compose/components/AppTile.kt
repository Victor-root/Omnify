package com.looker.droidify.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.looker.droidify.compose.theme.LocalIsTelevision

/** The icon size shared by every catalogue/external app tile (touch). */
val TileIconSize = 72.dp

/** The larger icon size used on Android TV, where there's far more screen to fill and the tiles sit on
 *  a uniform card. */
val TvTileIconSize = 104.dp

/** How much the focused tile grows on Android TV. */
private const val TvFocusedScale = 1.1f

/** The rounded box drawn behind/around a focused tile on Android TV. */
private val TvTileShape = RoundedCornerShape(16.dp)

/**
 * An app shown as a compact tile (F-Droid Discover style): a large rounded [icon] with an optional
 * "installed" check, and the name on (up to) two centred lines. Shared by the Discover carousels and
 * by every tab's grid so apps look identical everywhere — only the [icon] slot differs (a catalogue
 * icon vs. an external/provider icon). The caller sets the width via [modifier] (a fixed width in a
 * carousel, the cell width in a grid).
 */
@Composable
fun AppTile(
    name: String,
    isInstalled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    // Android TV: the focused tile scales up inside a green focus box (see below). The scale is applied
    // *below* the clickable in the chain so it only changes drawing, never the tile's measured size —
    // scaling the layout bounds instead made the grid jitter as it kept re-scrolling to fit the enlarged
    // item. No effect on touch (flag is false).
    val isTelevision = LocalIsTelevision.current
    var focused by remember { mutableStateOf(false) }
    // Only build the animations on TV, so the touch path is exactly as before (no animation objects).
    val scale = if (isTelevision) {
        animateFloatAsState(if (focused) TvFocusedScale else 1f, label = "tvTileScale").value
    } else {
        1f
    }
    // The focused tile also gains a soft green fill and a solid green outline (the theme accent), so it
    // reads clearly even at a glance from across the room. Both fade with focus; off on touch.
    val accent = MaterialTheme.colorScheme.primary
    val fillAlpha = if (isTelevision) {
        animateFloatAsState(if (focused) 0.18f else 0f, label = "tvTileFill").value
    } else {
        0f
    }
    val borderAlpha = if (isTelevision) {
        animateFloatAsState(if (focused) 1f else 0f, label = "tvTileBorder").value
    } else {
        0f
    }

    Column(
        verticalArrangement = spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            // Observe focus and lift the focused tile above its neighbours. Both leave layout untouched,
            // so they don't move anything; only applied on TV.
            .then(
                if (isTelevision) {
                    Modifier
                        .onFocusChanged { focused = it.isFocused }
                        .zIndex(if (focused) 1f else 0f)
                } else {
                    Modifier
                },
            )
            // Drop the clickable's default state layer on TV (a grey square drawn on the tile's
            // rectangular bounds); our own green focus box below replaces it. The touch path keeps its
            // normal ripple.
            .then(
                if (isTelevision) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            // Below the clickable so none of this changes the tile's measured size: the draw-only zoom,
            // then the green focus box (outline + soft fill) that scales up with it. Touch keeps just the
            // original vertical padding.
            .then(
                if (isTelevision) {
                    Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .padding(4.dp)
                        .border(2.dp, accent.copy(alpha = borderAlpha), TvTileShape)
                        .background(accent.copy(alpha = fillAlpha), TvTileShape)
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                } else {
                    Modifier.padding(vertical = 8.dp)
                },
            ),
    ) {
        Box {
            icon()
            if (isInstalled) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
