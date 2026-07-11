package com.looker.droidify.compose.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R

/**
 * First-launch prompt to let the app install APKs from this source, so the user grants it once up
 * front instead of being interrupted at their first install. [onAllow] opens the system page;
 * [onDismiss] is "Later" (and the dialog-dismiss).
 */
@Composable
fun UnknownSourcesDialog(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_apk_install),
                contentDescription = null,
            )
        },
        title = { Text(stringResource(R.string.allow_unknown_sources)) },
        text = { Text(stringResource(R.string.allow_unknown_sources_DESC)) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.allow)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.later)) }
        },
    )
}
