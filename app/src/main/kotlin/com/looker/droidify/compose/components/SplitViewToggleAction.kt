package com.looker.droidify.compose.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R

/**
 * Tablet-landscape-only toggle between the two-pane "Play Store style" detail layout (the hero card
 * fixed on the left, the rest of the page scrolling on the right) and the normal single-column layout
 * — shown in the top bar only when the screen is actually eligible for the split layout (tablet width,
 * landscape orientation, and the Settings toggle left on), so it never appears on phones or in portrait.
 * A single fixed icon (a two-pane glyph). Deliberately left untinted (no explicit `tint`): the top bar
 * (see accentTopAppBarColors) already supplies a contrasting `LocalContentColor` for its action icons —
 * a previous version tinted this icon with the theme's accent colour while active, which is the exact
 * colour the bar itself is drawn in, making the icon invisible. Shared by the F-Droid catalogue and
 * external detail screens so the two behave identically.
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
        )
    }
}
