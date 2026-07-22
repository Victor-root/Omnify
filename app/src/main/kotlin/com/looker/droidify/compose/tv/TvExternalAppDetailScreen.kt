package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvBringIntoViewOnFocus
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.compose.externalApps.ExternalLifecycleActions
import com.looker.droidify.external.Release
import kotlinx.coroutines.delay

/**
 * The Android TV detail screen for a tracked external (GitHub/GitLab/…) app — a lean presentation over
 * the same [ExternalAppsViewModel] the phone screen uses: icon + name + source + version, and the one
 * big Install / Update / Open / Uninstall control (reused verbatim as [ExternalLifecycleActions], so the
 * whole download/install lifecycle matches the phone build). The README, release history and language
 * details the phone screen shows are dropped for a couch-friendly page. Never composed off TV.
 */
@Composable
fun TvExternalAppDetailScreen(
    appKey: String,
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val signatureConflict by viewModel.signatureConflict.collectAsStateWithLifecycle()
    val readme by viewModel.readme.collectAsStateWithLifecycle()
    val releaseHistory by viewModel.releaseHistory.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()

    BackHandler { onBackClick() }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.reconcileInstalledLabels()
    }
    // Re-read install state on resume — notably returning from the system uninstall dialog — so the
    // button flips back to Install without leaving the screen (installManager.state alone doesn't
    // report a system uninstall).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshInstalled()
    }

    val app = apps.firstOrNull { it.key == appKey }

    if (app == null) {
        TvCentered { CircularProgressIndicator() }
        return
    }

    val installedVersion = installedVersions[appKey]
    val isInstalled = installedVersion != null

    // Same content the phone screen loads, so an external app's page is as rich as a catalogue one
    // (Omnify deliberately blurs the line between the two).
    LaunchedEffect(app.key) {
        viewModel.loadReadme(app)
        viewModel.loadReleaseHistory(app)
    }
    val description = remember(readme) {
        readme?.let { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }.orEmpty()
    }

    // Signature-conflict prompt (a different-signer install can't update in place), reused from the
    // phone screen's own flow.
    signatureConflict?.let { conflict ->
        val titleRes = if (conflict.isSystemApp) {
            R.string.signature_conflict_system_title
        } else {
            R.string.signature_conflict_title
        }
        val messageRes = if (conflict.isSystemApp) {
            R.string.signature_conflict_system_app
        } else {
            R.string.install_failed_signature_mismatch
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissSignatureConflict,
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(messageRes, app.label)) },
            confirmButton = {
                if (conflict.isSystemApp) {
                    TextButton(onClick = viewModel::dismissSignatureConflict) {
                        Text(stringResource(android.R.string.ok))
                    }
                } else {
                    TextButton(onClick = viewModel::confirmSignatureConflictUninstall) {
                        Text(stringResource(R.string.uninstall))
                    }
                }
            },
            dismissButton = {
                if (!conflict.isSystemApp) {
                    TextButton(onClick = viewModel::dismissSignatureConflict) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            },
        )
    }

    val primaryFocus = remember { FocusRequester() }
    var userInteracted by remember { mutableStateOf(false) }
    // Land focus on the action button when the screen opens; stop once the user takes over (same intent
    // as the catalogue TV detail screen).
    LaunchedEffect(app.key, installedVersion) {
        if (userInteracted) return@LaunchedEffect
        repeat(20) {
            if (runCatching { primaryFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(50)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) userInteracted = true
                false
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TvOverscan + 16.dp, vertical = TvOverscan),
        verticalArrangement = spacedBy(24.dp),
    ) {
        TvBackButton(onBackClick)

        Row(horizontalArrangement = spacedBy(24.dp)) {
            Box(modifier = Modifier.padding(top = 4.dp)) {
                ExternalAppIcon(app = app, isInstalled = isInstalled, size = 112.dp)
            }
            Column(verticalArrangement = spacedBy(8.dp)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${app.owner}/${app.repo}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = spacedBy(8.dp), verticalArrangement = spacedBy(8.dp)) {
                    (app.latestTag ?: app.installedVersionName)?.takeIf { it.isNotBlank() }?.let { TvChip(it) }
                    TvChip(app.provider.name.lowercase().replaceFirstChar { it.uppercase() })
                    if (app.supportsTelevision) TvChip(stringResource(R.string.discover_tv_apps))
                }
            }
        }

        Column(verticalArrangement = spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ExternalLifecycleActions(
                app = app,
                downloadStatus = downloads[appKey],
                installState = installStates[appKey],
                isInstalled = isInstalled,
                onInstallOrUpdate = { viewModel.installOrUpdate(app) },
                onLaunch = { viewModel.launch(app) },
                onUninstall = { viewModel.uninstall(app) },
                onCancel = { viewModel.cancel(app) },
                modifier = Modifier.fillMaxWidth(),
                installedVersionName = installedVersion,
                primaryActionFocusRequester = primaryFocus,
            )
            TvFavouriteButton(
                isFavourite = app.key in favourites,
                onToggle = { viewModel.toggleFavourite(app) },
            )
        }

        if (description.isNotBlank()) {
            TvSectionTitle(stringResource(R.string.description))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.tvBringIntoViewOnFocus(),
            )
        }

        releaseHistory?.takeIf { it.isNotEmpty() }?.let { releases ->
            TvSectionTitle(stringResource(R.string.versions))
            TvExternalVersionsList(releases = releases, installedTag = app.installedTag)
        }
    }
}

@Composable
private fun TvExternalVersionsList(releases: List<Release>, installedTag: String?) {
    Column(verticalArrangement = spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        // Newest first, capped — the whole release history would make an endless page on a remote.
        releases.take(8).forEach { release ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .tvFocusFill(RoundedCornerShape(16.dp))
                    .tvBringIntoViewOnFocus()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = release.tag,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (release.isPrerelease) {
                    Text(
                        text = stringResource(R.string.external_prerelease_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                if (installedTag != null && release.tag == installedTag) {
                    Text(
                        text = stringResource(R.string.installed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
