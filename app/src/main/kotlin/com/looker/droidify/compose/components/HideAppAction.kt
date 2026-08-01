package com.looker.droidify.compose.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R

/**
 * Top-bar toggle to hide/unhide this app everywhere (Discover, Installed, Updates, …). See
 * [com.looker.droidify.datastore.Settings.hiddenApps]. An open eye while visible, a crossed-out one once
 * hidden. Deliberately left untinted, same reasoning as [SplitViewToggleAction]: the top bar already
 * supplies a contrasting colour for its action icons. Shared by the F-Droid catalogue and external detail
 * screens so the two behave identically.
 */
@Composable
fun HideAppAction(isHidden: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(if (isHidden) R.string.unhide_app else R.string.hide_app),
        )
    }
}
