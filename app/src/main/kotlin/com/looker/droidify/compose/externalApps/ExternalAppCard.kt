package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.components.AppTile
import com.looker.droidify.compose.components.DownloadProgressRow
import com.looker.droidify.compose.components.InstallingRow
import com.looker.droidify.compose.components.TileIconSize
import com.looker.droidify.compose.components.TvTileIconSize
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.installer.model.InstallState

/**
 * An external app as a tile — identical to the F-Droid catalogue tiles ([AppTile]). Tapping opens the
 * external detail screen, where the install lifecycle lives, so the External tab looks and behaves
 * exactly like the other tabs.
 */
@Composable
fun ExternalAppTile(
    app: ExternalApp,
    isInstalled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSize = if (LocalIsTelevision.current) TvTileIconSize else TileIconSize
    AppTile(
        name = app.label,
        isInstalled = isInstalled,
        onClick = onClick,
        modifier = modifier,
    ) {
        ExternalAppIcon(app = app, isInstalled = isInstalled, size = iconSize)
    }
}

/**
 * The install lifecycle for an external app — a real download progress bar, then
 * Install / Update / Open / Uninstall depending on state, exactly like the F-Droid detail screen.
 * Shown on the external detail screen.
 */
@Composable
fun ExternalLifecycleActions(
    app: ExternalApp,
    downloadStatus: DownloadStatus?,
    installState: InstallState?,
    isInstalled: Boolean,
    onInstallOrUpdate: () -> Unit,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val installing =
        installState == InstallState.Pending || installState == InstallState.Installing
    when {
        downloadStatus != null -> DownloadProgressRow(
            status = downloadStatus,
            onCancel = onCancel,
            modifier = modifier.fillMaxWidth(),
        )

        installing -> InstallingRow(
            onCancel = onCancel,
            modifier = modifier.fillMaxWidth(),
        )

        else -> Row(
            modifier = modifier.fillMaxWidth(),
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
