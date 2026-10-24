package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A `Scaffold`'s content padding for the outside of a scrolling container: every side but the bottom.
 * The bottom ([PaddingValues.calculateBottomPadding]) belongs inside the scrolled content (a lazy list's
 * own `contentPadding`, or padding after `verticalScroll`), so the content runs on under the navigation
 * bar and only its end stops clear of it. Padded outside, the container itself stopped short of the bar,
 * and what scrolled past was cut off on a line above it.
 */
@Composable
fun PaddingValues.aroundScroll(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        top = calculateTopPadding(),
        end = calculateEndPadding(direction),
        bottom = 0.dp,
    )
}
