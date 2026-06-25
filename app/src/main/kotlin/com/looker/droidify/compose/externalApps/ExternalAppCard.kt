package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.components.DownloadProgressRow
import com.looker.droidify.compose.components.InstallingRow
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.installer.model.InstallState

/**
 * A tracked external-source app, with the full install lifecycle — exactly like the F-Droid tabs:
 * a real download progress bar, then Install / Update / Open / Uninstall depending on state. Used
 * both on the "External" tab and on the source-management screen (where [onRemove] adds a trash
 * action to drop the source entirely).
 */
@Composable
fun ExternalAppCard(
    app: ExternalApp,
    downloadStatus: DownloadStatus?,
    installState: InstallState?,
    isInstalled: Boolean,
    onInstallOrUpdate: () -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = app.statusLine(isInstalled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onRemove != null) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tabler_trash),
                            contentDescription = stringResource(R.string.external_remove),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val installing =
                installState == InstallState.Pending || installState == InstallState.Installing
            when {
                downloadStatus != null -> DownloadProgressRow(
                    status = downloadStatus,
                    onCancel = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )

                installing -> InstallingRow(
                    onCancel = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )

                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        !isInstalled -> Button(
                            onClick = onInstallOrUpdate,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.install)) }

                        app.hasUpdate -> Button(
                            onClick = onInstallOrUpdate,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.update)) }

                        else -> Button(
                            onClick = onLaunch,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.launch)) }
                    }
                    if (isInstalled) {
                        OutlinedButton(onClick = onUninstall) {
                            Text(stringResource(R.string.uninstall))
                        }
                    }
                }
            }
        }
    }
}

/** Provider + version summary, e.g. "GitHub · v1.2.0 → v1.2.3" or "GitLab · v1.2.3". Symbol-only,
 *  so it needs no translation. */
private fun ExternalApp.statusLine(isInstalled: Boolean): String {
    val provider = provider.label
    val version = when {
        !isInstalled -> latestTag ?: installedTag ?: "?"
        hasUpdate -> "$installedTag → ${latestTag ?: "?"}"
        else -> installedTag ?: latestTag ?: "?"
    }
    return "$provider · $version"
}
