package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import com.looker.droidify.compose.theme.LocalAccentBarColor
import com.looker.droidify.compose.theme.LocalOnAccentBarColor

// Material3's suggested replacements (PrimaryTabRow/SecondaryTabRow) don't just rename this: their
// `indicator` lambda is scoped to a different receiver (TabIndicatorScope, not this custom indicator's
// List<TabPosition>) and their default colours belong to a distinct visual variant, so swapping would
// risk a real look change, not a mechanical one, for no functional benefit here (every colour/indicator
// this row cares about is already explicitly set below).
/**
 * A [TabRow] styled to blend into the accent-coloured header above it, same background colour, a
 * short rounded pill under the selected tab instead of a full-width bar, no divider line, so the tabs
 * read as part of one continuous header instead of a separate bar underneath it. Shared by every
 * screen whose tabs sit directly below the top app bar. Callers supply their own [Tab][androidx.compose.material3.Tab]
 * children (each still setting its own selected/unselected content colour), only the row's own chrome
 * lives here.
 *
 * [position] places the pill between tabs, as a tab index with a fraction (1.5 is halfway from the
 * second tab to the third), for tabs that are pages it can follow as they are dragged. Read only when
 * the pill is placed, so a drag moves it without recomposing the row.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccentTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    position: (() -> Float)? = null,
    tabs: @Composable () -> Unit,
) {
    TabRow(
        modifier = modifier,
        selectedTabIndex = selectedTabIndex,
        containerColor = LocalAccentBarColor.current,
        contentColor = LocalOnAccentBarColor.current,
        indicator = { tabPositions ->
            AccentTabIndicator(position ?: { selectedTabIndex.toFloat() }, tabPositions)
        },
        divider = {},
        tabs = tabs,
    )
}

/**
 * [AccentTabRow]'s scrollable counterpart, for a tab count that isn't fixed ahead of time (e.g. one tab
 * per repository offering an app) and can outgrow an even, fixed-width split. Same chrome, just built on
 * [ScrollableTabRow] instead of [TabRow].
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccentScrollableTabRow(
    selectedTabIndex: Int,
    edgePadding: Dp,
    modifier: Modifier = Modifier,
    tabs: @Composable () -> Unit,
) {
    ScrollableTabRow(
        modifier = modifier,
        selectedTabIndex = selectedTabIndex,
        containerColor = LocalAccentBarColor.current,
        contentColor = LocalOnAccentBarColor.current,
        edgePadding = edgePadding,
        indicator = { tabPositions -> AccentTabIndicator({ selectedTabIndex.toFloat() }, tabPositions) },
        divider = {},
        tabs = tabs,
    )
}

@Composable
private fun AccentTabIndicator(position: () -> Float, tabPositions: List<TabPosition>) {
    if (tabPositions.isNotEmpty()) {
        val pillWidth = 28.dp
        Box(
            Modifier
                .fillMaxWidth()
                .wrapContentSize(Alignment.BottomStart)
                .offset {
                    val at = position().coerceIn(0f, tabPositions.lastIndex.toFloat())
                    val from = tabPositions[at.toInt()]
                    val to = tabPositions[minOf(at.toInt() + 1, tabPositions.lastIndex)]
                    val centre = lerp(
                        (from.left + from.width / 2).toPx(),
                        (to.left + to.width / 2).toPx(),
                        at - at.toInt(),
                    )
                    IntOffset((centre - pillWidth.toPx() / 2).roundToInt(), (-8).dp.roundToPx())
                }
                .width(pillWidth)
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(LocalOnAccentBarColor.current),
        )
    }
}
