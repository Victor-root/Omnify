package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.components.AppTile
import com.looker.droidify.compose.components.DownloadProgressRow
import com.looker.droidify.compose.components.InstallingRow
import com.looker.droidify.compose.components.TileIconSize
import com.looker.droidify.compose.components.TvTileIconSize
import com.looker.droidify.compose.components.tvFocusScale
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
    // The real on-device versionName (read from the package manager), so an app installed before its
    // source was tracked still shows Update instead of Launch — see ExternalApp.hasUpdateGiven. Null
    // when not installed.
    installedVersionName: String? = null,
    // TV: the startup/down-escape focus target on the external detail screen. Attached to whichever
    // button ends up primary in this state (install/update/launch, or Cancel while a download/install is
    // in progress), mirroring the F-Droid catalogue detail screen's primaryActionFocusRequester. Null off
    // TV / for callers that don't need it (e.g. the tile card).
    primaryActionFocusRequester: FocusRequester? = null,
) {
    val installing =
        installState == InstallState.Pending || installState == InstallState.Installing
    val isTelevision = LocalIsTelevision.current
    // A phone-width button stretched to fill a tablet's much wider screen looked like an oversized
    // stray bar; capped to a comfortable reading width instead, matching the tablet breakpoint Material
    // itself uses (600dp) and the F-Droid catalogue detail screen's own primary button — phones (the
    // vast majority of devices) are completely untouched.
    val isTablet = !isTelevision && LocalConfiguration.current.screenWidthDp >= 600
    when {
        downloadStatus != null -> DownloadProgressRow(
            status = downloadStatus,
            onCancel = onCancel,
            modifier = modifier.fillMaxWidth(),
            cancelFocusRequester = primaryActionFocusRequester,
        )

        installing -> InstallingRow(
            onCancel = onCancel,
            modifier = modifier.fillMaxWidth(),
            cancelFocusRequester = primaryActionFocusRequester,
        )

        else -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(
                if (isTelevision) 24.dp else 8.dp,
                if (isTelevision || isTablet) Alignment.CenterHorizontally else Alignment.Start,
            ),
        ) {
            val primaryButtonModifier = when {
                isTelevision -> Modifier.height(60.dp).widthIn(min = 340.dp)
                isTablet -> Modifier.widthIn(min = 220.dp, max = 360.dp)
                else -> Modifier.weight(1f)
            }.tvFocusScale(1.10f).let {
                if (primaryActionFocusRequester != null) it.focusRequester(primaryActionFocusRequester) else it
            }
            val secondaryButtonModifier = if (isTelevision) {
                Modifier.height(60.dp).widthIn(min = 200.dp)
            } else {
                Modifier
            }.tvFocusScale(1.10f)
            when {
                !isInstalled -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = primaryButtonModifier,
                ) { Text(stringResource(R.string.install)) }

                app.hasUpdateGiven(installedVersionName) -> Button(
                    onClick = onInstallOrUpdate,
                    modifier = primaryButtonModifier,
                ) { Text(stringResource(R.string.update)) }

                else -> Button(
                    onClick = onLaunch,
                    modifier = primaryButtonModifier,
                ) { Text(stringResource(R.string.launch)) }
            }
            if (isInstalled) {
                OutlinedButton(onClick = onUninstall, modifier = secondaryButtonModifier) {
                    Text(stringResource(R.string.uninstall))
                }
                // "App info" now lives as a gear on the hero card itself (top-start, mirroring the
                // favourite heart) — see HeroCard's onManageClick, wired from ExternalAppDetailScreen —
                // same as the F-Droid catalogue detail screen, freeing this row for the primary/
                // uninstall buttons alone.
            }
        }
    }
}
