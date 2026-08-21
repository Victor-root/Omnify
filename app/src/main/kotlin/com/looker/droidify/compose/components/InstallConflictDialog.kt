package com.looker.droidify.compose.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.InstallConflict
import com.looker.droidify.compose.appDetail.InstallConflictReason

/**
 * Explains an update Android refuses to apply in place, and offers the only way through it: uninstall
 * the copy on the device first, after which the already-downloaded APK installs by itself (see
 * [InstallConflict]). A system app has no way through at all, so that case only says so.
 *
 * Shared by every detail screen, catalogue and external source, phone and TV. Without a dialog the
 * update simply fails and the button goes back to offering the same update, which is what a
 * differently-signed app looked like on the TV catalogue screen: pressing update, over and over, with
 * nothing ever explaining why nothing happened.
 */
@Composable
fun InstallConflictDialog(
    conflict: InstallConflict,
    appName: String,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val titleRes = if (conflict.isSystemApp) {
        R.string.signature_conflict_system_title
    } else {
        R.string.signature_conflict_title
    }
    val messageRes = when {
        conflict.isSystemApp -> R.string.signature_conflict_system_app
        conflict.reason == InstallConflictReason.VERSION_DOWNGRADE ->
            R.string.install_failed_version_downgrade
        else -> R.string.install_failed_signature_mismatch
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes, appName)) },
        confirmButton = {
            if (conflict.isSystemApp) {
                // A system app can't be uninstalled — nothing to do but acknowledge.
                TextButton(onClick = onDismiss, modifier = Modifier.tvFocusScale()) {
                    Text(stringResource(android.R.string.ok))
                }
            } else {
                TextButton(onClick = onUninstall, modifier = Modifier.tvFocusScale()) {
                    Text(stringResource(R.string.uninstall))
                }
            }
        },
        dismissButton = {
            if (!conflict.isSystemApp) {
                TextButton(onClick = onDismiss, modifier = Modifier.tvFocusScale()) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}
