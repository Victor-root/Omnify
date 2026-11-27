package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.AccentAlertDialog
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.external.ReleaseAsset
import com.looker.droidify.network.DataSize

/**
 * Asks before installing a file picked among a release's APKs. Picking one is following it: the source's
 * APK filter is set to it, so its updates take the same file, which this says, along with how to come
 * back. Picking Omnify's own choice again ([backToAuto]) clears that filter instead.
 */
@Composable
internal fun InstallVariantDialog(
    label: String,
    asset: ReleaseAsset,
    version: String,
    backToAuto: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    AccentAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (backToAuto) {
                    stringResource(R.string.install_variant_back_to_auto)
                } else {
                    stringResource(R.string.install_variant_FORMAT, label)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(text = label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = asset.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    val size = asset.size?.let { DataSize(it).toString() }
                    Text(
                        text = if (size != null) {
                            stringResource(R.string.install_variant_file_FORMAT, version, size)
                        } else {
                            stringResource(R.string.version_FORMAT, version)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(if (backToAuto) R.string.install_variant_back_DESC else R.string.install_variant_DESC),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, modifier = Modifier.tvFocusScale()) {
                Text(stringResource(R.string.install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusScale()) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
