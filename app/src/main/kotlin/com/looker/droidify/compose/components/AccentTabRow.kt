package com.looker.droidify.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import com.looker.droidify.compose.theme.LocalAccentBarColor
import com.looker.droidify.compose.theme.LocalIsTelevision
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

/**
 * Tabs named by [labels] under an accent header: pills on a touch screen ([AccentPillTabRow]), the header's
 * own tab row on a television ([AccentTabRow]).
 */
@Composable
fun AccentTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalIsTelevision.current) {
        AccentTabRow(selectedTabIndex = selectedIndex, modifier = modifier) {
            labels.forEachIndexed { index, label ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.tvFocusScale(),
                    selectedContentColor = LocalOnAccentBarColor.current,
                    unselectedContentColor = LocalOnAccentBarColor.current.copy(alpha = 0.7f),
                    text = { Text(label) },
                )
            }
        }
    } else {
        AccentPillTabRow(modifier) {
            labels.forEachIndexed { index, label ->
                AccentPillTab(selected = index == selectedIndex, onClick = { onSelect(index) }, description = label) { shown ->
                    AccentPillFace(shown) { color, faceModifier ->
                        AccentPillLabel(label, color, faceModifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
    }
}

/** An unpicked pill: the accent darkened a little, whatever accent the user chose. */
private val UnpickedPillColor = Color.Black.copy(alpha = 0.18f)

/**
 * The tabs under an accent header on a touch screen: a pill each, side by side across the header, with
 * the header's bottom margin below them. Filled with [AccentPillTab]s. Television keeps [AccentTabRow].
 */
@Composable
fun AccentPillTabRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(LocalAccentBarColor.current)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 18.dp),
        content = content,
    )
}

/**
 * One tab of an [AccentPillTabRow]. The one shown takes the header's contrasting colour, with the accent
 * on its face ([AccentPillFace]). [shown] says how far it is the one shown, 0 to 1, for tabs that follow
 * pages as they are dragged; read only while drawing, so following a drag recomposes nothing. Left out,
 * it eases between the two as [selected] changes. [content] gets the same value.
 */
@Composable
fun RowScope.AccentPillTab(
    selected: Boolean,
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    shown: (() -> Float)? = null,
    height: Dp = 44.dp,
    shape: Shape = CircleShape,
    content: @Composable BoxScope.(shown: () -> Float) -> Unit,
) {
    val eased = animateFloatAsState(if (selected) 1f else 0f, label = "pillTab")
    val shownNow = shown ?: { eased.value }
    val onAccent = LocalOnAccentBarColor.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .weight(1f)
            .height(height)
            .clip(shape)
            .drawBehind { drawRect(lerp(UnpickedPillColor, onAccent, shownNow())) }
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = selected
                role = Role.Tab
            },
    ) {
        content(shownNow)
    }
}

/**
 * An [AccentPillTab]'s face (its name, its icon or both), drawn twice: in the header's contrasting
 * colour, and in the accent on top of it, crossfading as [shown] goes from 0 to 1.
 */
@Composable
fun AccentPillFace(shown: () -> Float, face: @Composable (color: Color, modifier: Modifier) -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        face(LocalOnAccentBarColor.current, Modifier.graphicsLayer { alpha = 1f - shown() })
        face(LocalAccentBarColor.current, Modifier.graphicsLayer { alpha = shown() })
    }
}

/** An [AccentPillTab]'s name: one bold line whatever the language, shrunk to fit rather than wrapped or cut. */
@Composable
fun AccentPillLabel(text: String, color: Color, modifier: Modifier = Modifier, size: TextUnit = 14.sp) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = size, fontWeight = FontWeight.Bold),
        maxLines = 1,
        autoSize = shrinkToFit(size),
        modifier = modifier,
    )
}

