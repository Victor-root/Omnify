package com.looker.droidify.compose.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R

/**
 * Tablet-landscape-only toggle between the two-pane "Play Store style" detail layout (the hero card
 * fixed on the left, the rest of the page scrolling on the right) and the normal single-column layout
 * — shown in the top bar only when the screen is actually eligible for the split layout (tablet width,
 * landscape orientation, and the Settings toggle left on), so it never appears on phones or in portrait.
 * One fixed icon (a two-pane glyph), just tinted with the accent colour while split view is active,
 * rather than swapping between two different icon shapes on every tap. Shared by the F-Droid catalogue
 * and external detail screens so the two behave identically.
 */
@Composable
fun SplitViewToggleAction(splitView: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = Icons.Filled.VerticalSplit,
            contentDescription = stringResource(
                if (splitView) {
                    R.string.split_view_switch_to_single
                } else {
                    R.string.split_view_switch_to_split
                },
            ),
            tint = if (splitView) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}
