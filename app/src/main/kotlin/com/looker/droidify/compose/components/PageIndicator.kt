package com.looker.droidify.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.looker.droidify.R

/**
 * Where the person is among [pageCount] pages: a dot per page, the current one stretched into an accent
 * pill. The change eases rather than snaps, and says "page x of y" to a screen reader.
 */
@Composable
fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.welcome_page_FORMAT, currentPage + 1, pageCount)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        repeat(pageCount) { page ->
            val selected = page == currentPage
            val width by animateDpAsState(
                targetValue = if (selected) SELECTED_WIDTH else DOT_SIZE,
                label = "pageIndicatorWidth",
            )
            val color by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                },
                label = "pageIndicatorColor",
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(DOT_SIZE)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

private val DOT_SIZE = 8.dp
private val SELECTED_WIDTH = 28.dp
