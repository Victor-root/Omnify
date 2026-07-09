package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.looker.droidify.utility.common.extension.openAppInfo

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
    // A phone-width button stretched to fill a tablet's much wider screen looked like an oversized
    // stray bar; capped to a comfortable reading width instead, matching the tablet breakpoint Material
    // itself uses (600dp) and the F-Droid catalogue detail screen's own primary button — phones (the
    // vast majority of devices) are completely untouched.
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
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
            horizontalArrangement = Arrangement.spacedBy(
                8.dp,
                if (isTablet) Alignment.CenterHorizontally else Alignment.Start,
            ),
        ) {
            val primaryButtonModifier = if (isTablet) {
                Modifier.widthIn(min = 220.dp, max = 360.dp)
            } else {
                Modifier.weight(1f)
            }
            when {
                !isInstalled -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = primaryButtonModifier,
                ) { Text(stringResource(R.string.install)) }

                app.hasUpdate -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = primaryButtonModifier,
                ) { Text(stringResource(R.string.update)) }

                else -> Button(
                    onClick = onLaunch,
                    modifier = primaryButtonModifier,
                ) { Text(stringResource(R.string.launch)) }
            }
            if (isInstalled) {
                OutlinedButton(onClick = onUninstall) {
                    Text(stringResource(R.string.uninstall))
                }
                // Android's own "App info" page — uninstall, clear cache/data, permissions, battery,
                // notifications — instead of reimplementing any of that system-level management here.
                // Same button as the F-Droid catalogue detail screen.
                app.packageName?.let { packageName ->
                    val context = LocalContext.current
                    IconButton(onClick = { context.openAppInfo(packageName) }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.manage),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
