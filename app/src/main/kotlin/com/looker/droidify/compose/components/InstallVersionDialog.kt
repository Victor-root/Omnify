package com.looker.droidify.compose.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R

/**
 * Confirms installing a specific version the user tapped in a versions list. For a normal case it
 * offers Install; for a downgrade (an older version while a newer one is installed) it explains Android
 * won't replace it in place and offers to uninstall the current version first. Shared by the F-Droid
 * catalogue's version list and the external app source's release list.
 */
@Composable
fun InstallVersionDialog(
    versionName: String,
    isDowngrade: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.install_version_FORMAT, versionName)) },
        text = if (isDowngrade) {
            { Text(stringResource(R.string.install_version_downgrade_DESC)) }
        } else {
            null
        },
        confirmButton = {
            if (isDowngrade) {
                TextButton(onClick = onUninstall, modifier = Modifier.tvFocusScale()) {
                    Text(stringResource(R.string.uninstall))
                }
            } else {
                TextButton(onClick = onInstall, modifier = Modifier.tvFocusScale()) {
                    Text(stringResource(R.string.install))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusScale()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
