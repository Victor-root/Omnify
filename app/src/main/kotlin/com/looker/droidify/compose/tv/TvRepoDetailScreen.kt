package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appList.CatalogAppTile
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.components.tvReadable
import com.looker.droidify.compose.repoDetail.DeleteRepositoryDialog
import com.looker.droidify.compose.repoDetail.FingerprintCard
import com.looker.droidify.compose.repoDetail.InstallAllButton
import com.looker.droidify.compose.repoDetail.RepoDetailViewModel
import com.looker.droidify.compose.repoDetail.components.LastUpdatedCard
import com.looker.droidify.compose.repoDetail.components.UnsyncedRepoState
import com.looker.droidify.compose.repoDetail.formatFingerprint
import com.looker.droidify.compose.repoList.RepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIconRes
import com.looker.droidify.utility.text.toAnnotatedString

/**
 * The Android TV repository detail screen — the same [RepoDetailViewModel] as the phone screen, but a
 * single scrolling page (the phone's Info / Apps tab row read poorly on a 10-foot screen) laid out in
 * the TV visual language: a large header with the repo's identity, description, last-updated, fingerprint
 * and enable toggle, then the repo's apps as the usual catalogue tile grid beneath. Never composed off
 * TV.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TvRepoDetailScreen(
    onBackClick: () -> Unit,
    onEditClick: (Int) -> Unit,
    onAppClick: (String) -> Unit,
    viewModel: RepoDetailViewModel,
) {
    val repo by viewModel.repo.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val installedPackages by viewModel.installedPackages.collectAsState()
    val notInstalledCount by viewModel.notInstalledCount.collectAsState()
    val isInstallingAll by viewModel.isInstallingAll.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    BackHandler { onBackClick() }

    if (showDeleteDialog) {
        DeleteRepositoryDialog(
            onConfirm = {
                viewModel.deleteRepository { onBackClick() }
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    val currentRepo = repo
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvAccentBackground()
        Column(modifier = Modifier.fillMaxSize()) {
            // Header row: back on the left, edit / delete on the right — the actions the phone screen's
            // top bar carried, kept as focus-scaled icon buttons in the TV style.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = TvOverscan + 8.dp, end = TvOverscan, top = TvOverscan, bottom = 4.dp),
            ) {
                TvBackButton(onBackClick)
                Spacer(Modifier.weight(1f))
                if (currentRepo != null) {
                    IconButton(
                        onClick = { onEditClick(viewModel.repoId) },
                        modifier = Modifier.tvFocusScale(),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit))
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.tvFocusScale(),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                    }
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(
                    start = TvOverscan + 8.dp,
                    end = TvOverscan + 8.dp,
                    top = 8.dp,
                    bottom = TvOverscan + 24.dp,
                ),
                horizontalArrangement = spacedBy(12.dp),
                verticalArrangement = spacedBy(12.dp),
            ) {
                if (currentRepo != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        TvRepoHeader(
                            repo = currentRepo,
                            onToggle = viewModel::enableRepository,
                        )
                    }

                    when {
                        !currentRepo.enabled -> item(span = { GridItemSpan(maxLineSpan) }, key = "disabled") {
                            UnsyncedRepoState(
                                onEnableClick = { viewModel.enableRepository(true) },
                                address = currentRepo.address,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            )
                        }

                        apps.isEmpty() && isSyncing -> item(span = { GridItemSpan(maxLineSpan) }, key = "syncing") {
                            TvRepoAppsMessage(loading = true)
                        }

                        apps.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                            TvRepoAppsMessage(loading = false)
                        }

                        else -> {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "apps-title") {
                                TvSectionTitle(stringResource(R.string.repo_tab_apps))
                            }
                            if (notInstalledCount > 0) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "install-all") {
                                    InstallAllButton(
                                        count = notInstalledCount,
                                        isInstalling = isInstallingAll,
                                        onClick = viewModel::installAll,
                                    )
                                }
                            }
                            gridItems(apps, key = { it.appId }) { app ->
                                CatalogAppTile(
                                    app = app,
                                    isInstalled = app.packageName.name in installedPackages,
                                    onClick = { onAppClick(app.packageName.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The repo's identity block: icon, name, its address as a focusable link, description, last-updated,
 *  fingerprint and the enable toggle — the phone Info tab's content, in one TV header. */
@Composable
private fun TvRepoHeader(
    repo: com.looker.droidify.data.model.Repo,
    onToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        RepoIcon(
            iconUrl = repo.icon?.path,
            fallbackUrl = defaultRepoIcon(repo.address),
            name = repo.name,
            modifier = Modifier.size(72.dp),
            fallbackRes = defaultRepoIconRes(repo.address),
        )
        Spacer(Modifier.height(10.dp))
        Text(text = repo.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        val handler = LocalUriHandler.current
        Text(
            text = repo.address,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .tvFocusFill(RoundedCornerShape(8.dp))
                .clickable { runCatching { handler.openUri(repo.address) } }
                .padding(vertical = 4.dp),
        )

        if (repo.description.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            val description = remember(repo.description) {
                repo.description.toAnnotatedString(onUrlClick = { runCatching { handler.openUri(it) } })
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.tvReadable(),
            )
        }

        if (repo.versionInfo != null) {
            Spacer(Modifier.height(14.dp))
            LastUpdatedCard(repo.versionInfo?.timestamp)
        }

        Spacer(Modifier.height(14.dp))
        FingerprintCard(title = stringResource(R.string.fingerprint), content = formatFingerprint(repo))

        Spacer(Modifier.height(14.dp))
        // The enable toggle as a single-button row (click anywhere flips it) — same interaction as the
        // repo list rows.
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusScale(1.02f)
                .tvFocusFill(MaterialTheme.shapes.large)
                .clickable { onToggle(!repo.enabled) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.repo_enabled_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (repo.enabled) {
                            stringResource(R.string.repo_enabled_desc_on)
                        } else {
                            stringResource(R.string.repo_enabled_desc_off)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(12.dp))
                Switch(checked = repo.enabled, onCheckedChange = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvRepoAppsMessage(loading: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularWavyProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = stringResource(if (loading) R.string.syncing else R.string.no_applications_available),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
