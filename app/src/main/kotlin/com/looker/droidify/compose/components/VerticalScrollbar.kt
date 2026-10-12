package com.looker.droidify.compose.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a thin scroll indicator along the right edge of a lazy list, so a list that is cut off by its
 * container says so and shows how far along it is. Nothing is drawn while everything fits.
 *
 * The position is read from the list state in the draw phase only, so scrolling redraws the indicator
 * without recomposing anything. Rows differ in height, so the content length is estimated from the
 * average of the rows on screen: close enough for an indicator, which is not a control.
 */
@Composable
fun Modifier.verticalScrollbar(
    state: LazyListState,
    thickness: Dp = 4.dp,
    edgePadding: Dp = 3.dp,
): Modifier {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    return drawWithContent {
        drawContent()
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return@drawWithContent
        val averageItemPx = visible.sumOf { it.size }.toFloat() / visible.size
        val contentPx = averageItemPx * state.layoutInfo.totalItemsCount
        val viewportPx = size.height
        if (contentPx <= viewportPx) return@drawWithContent
        val scrolledPx = state.firstVisibleItemIndex * averageItemPx + state.firstVisibleItemScrollOffset
        val thumbPx = (viewportPx * viewportPx / contentPx).coerceAtLeast(MIN_THUMB.toPx())
        val progress = (scrolledPx / (contentPx - viewportPx)).coerceIn(0f, 1f)
        val thicknessPx = thickness.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - thicknessPx - edgePadding.toPx(), (viewportPx - thumbPx) * progress),
            size = Size(thicknessPx, thumbPx),
            cornerRadius = CornerRadius(thicknessPx / 2),
        )
    }
}

/** The shortest the indicator gets, so a very long list still leaves something to see. */
private val MIN_THUMB = 24.dp
