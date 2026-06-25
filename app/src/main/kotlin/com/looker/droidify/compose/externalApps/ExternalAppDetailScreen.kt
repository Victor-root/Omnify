package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalAppDetailScreen(
    appKey: String,
    viewModel: ExternalAppsViewModel,
    onBackClick: () -> Unit,
) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()
    val installedKeys by viewModel.installedKeys.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val readme by viewModel.readme.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.refreshInstalled()
        viewModel.reconcileInstalledLabels()
    }

    val app = apps.firstOrNull { it.key == appKey }
    val isInstalled = appKey in installedKeys
    val installedVersion = installedVersions[appKey]
    val uriHandler = LocalUriHandler.current

    // Download the project README (as HTML) once the app is known, to fill out the detail screen.
    LaunchedEffect(app?.key) {
        app?.let { viewModel.loadReadme(it) }
    }

    Scaffold(
        topBar = {
            // No title here: the app name sits next to the logo in the header below, so showing it
            // in the bar too would just duplicate it.
            TopAppBar(
                title = { },
                navigationIcon = { BackButton(onBackClick) },
            )
        },
        snackbarHost = { SnackbarHost(viewModel.snackbarHostState) },
    ) { contentPadding ->
        if (app == null) {
            // The source was removed (or not loaded yet); nothing to show.
            Spacer(Modifier.padding(contentPadding))
            return@Scaffold
        }
        // A compact, fixed top (icon, name, versions, repo link, actions); the README fills all the
        // remaining space and scrolls on its own (a WebView), so there is no nested scrolling and no
        // wasted room. Source management (removing the source) lives on the Repositories screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExternalAppIcon(app = app, isInstalled = isInstalled, size = 64.dp)
                Spacer(Modifier.width(16.dp))
                // Name next to the logo (the top bar carries none), then the versions and repo link.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    app.latestTag?.let { tag ->
                        Text(
                            text = stringResource(
                                R.string.external_repo_latest,
                                app.provider.label,
                                tag,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    installedVersion?.let { version ->
                        Text(
                            text = stringResource(R.string.external_installed_version, version),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = app.webUrl.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable { uriHandler.openUri(app.webUrl) }
                            .padding(top = 4.dp),
                    )
                }
            }

            ExternalLifecycleActions(
                app = app,
                downloadStatus = downloads[appKey],
                installState = installStates[appKey],
                isInstalled = isInstalled,
                onInstallOrUpdate = { viewModel.installOrUpdate(app) },
                onLaunch = { viewModel.launch(app) },
                onUninstall = { viewModel.uninstall(app) },
                onCancel = { viewModel.cancel(app) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (!isInstalled) {
                PreInstallNotice(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            // README — fills the remaining space and scrolls internally. GitHub leaves repo-relative
            // image paths un-rewritten, so the WebView resolves them against the raw content host.
            val currentReadme = readme
            if (currentReadme != null) {
                ReadmeWebView(
                    html = currentReadme,
                    baseUrl = "https://raw.githubusercontent.com/${app.owner}/${app.repo}/HEAD/",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Tells the user that the name/icon/version shown are the repository's until the app is installed
 *  (a release carries no app metadata, so the real ones are only known once the APK is on-device). */
@Composable
private fun PreInstallNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Outlined.Info, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.external_preinstall_notice),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
