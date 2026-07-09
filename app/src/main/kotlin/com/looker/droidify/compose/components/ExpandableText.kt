package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R

/** Collapsed height used when the caller doesn't know how much viewport space is actually left below
 *  the text (e.g. before the first layout pass has measured it). */
private const val FALLBACK_COLLAPSED_LINES = 8

/** Never collapse to fewer lines than this, even if the computed available space is very small (a
 *  description started right near the bottom of the screen, say). */
private const val MIN_COLLAPSED_LINES = 3

/**
 * Long body text (an app's description, ...) collapsed to fill the space available below it down to
 * the bottom of the screen — [availableHeightPx] is that space, in pixels, as measured by the caller
 * (see AppDetailScreen/ExternalAppDetailScreen); null falls back to a fixed line count. Used
 * identically by the F-Droid catalogue and external detail screens so a long description doesn't force
 * the whole page into an endless scroll on either.
 *
 * The "Show more" toggle is a real filled button, centred below the collapsed text, only shown once
 * the text actually overflows the collapsed height — never for a description short enough to already
 * fit. Tapping it reveals the full text and removes the button for good (this composable never
 * collapses back), rather than a small link that keeps toggling both ways.
 */
@Composable
fun ExpandableText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    availableHeightPx: Int? = null,
    // Tablet-landscape split view: the right pane is dedicated space for this content, so there's no
    // reason to collapse it — always show the full text there, with no button at all.
    alwaysExpanded: Boolean = false,
) {
    var expanded by remember(text) { mutableStateOf(alwaysExpanded) }
    var isOverflowing by remember(text) { mutableStateOf(false) }
    // A single line's own height in this exact style, measured once (a plain synchronous text
    // measurement, not a real composition, so this never flashes the full text before collapsing).
    val textMeasurer = rememberTextMeasurer()
    val lineHeightPx = remember(style) {
        textMeasurer.measure(AnnotatedString("Ag"), style = style).size.height
    }
    val collapsedMaxLines = remember(availableHeightPx, lineHeightPx) {
        val fromAvailableSpace = if (availableHeightPx != null && lineHeightPx > 0) {
            availableHeightPx / lineHeightPx
        } else {
            null
        }
        (fromAvailableSpace ?: FALLBACK_COLLAPSED_LINES).coerceAtLeast(MIN_COLLAPSED_LINES)
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            // Only measured while collapsed: once expanded the button is gone for good, so there's
            // nothing left to decide based on a later re-layout at full height.
            onTextLayout = { result -> if (!expanded) isOverflowing = result.hasVisualOverflow },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!alwaysExpanded && !expanded && isOverflowing) {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.show_more))
            }
        }
    }
}
