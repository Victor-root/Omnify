package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appList.CatalogAppTile
import com.looker.droidify.compose.components.FingerprintCard
import com.looker.droidify.compose.components.TvInitialFocus
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvReadable
import com.looker.droidify.compose.repoDetail.DeleteRepositoryDialog
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
 * The Android TV repository detail screen — the same [RepoDetailViewModel] as the phone screen, laid out
 * like an app's page ([TvAppDetailPanes]): the repository in a card on the left (identity, enable switch,
 * edit and delete, description, last update, fingerprint), its apps as the usual catalogue tile grid on
 * the right. Never composed off TV.
 */
@OptIn(ExperimentalFoundationApi::class)
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

    // The enable switch holds the startup focus, the main thing to do with a repository from here.
    val toggleFocus = remember { FocusRequester() }
    TvInitialFocus(toggleFocus)
    val leftScroll = rememberScrollState()

    val currentRepo = repo
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvAccentBackground()
        if (currentRepo == null) return@Box
        // Laid out like an app's page: the repository itself in a card on the left, its apps on the right.
        TvAppDetailPanes(
            leftScroll = leftScroll,
            leftBringIntoViewSpec = LocalBringIntoViewSpec.current,
            onKeyDown = {},
            left = {
                TvRepoCard(
                    repo = currentRepo,
                    toggleFocus = toggleFocus,
                    onToggle = viewModel::enableRepository,
                    onEdit = { onEditClick(viewModel.repoId) },
                    onDelete = { showDeleteDialog = true },
                )
            },
            right = {
                TvSectionTitle(stringResource(R.string.repo_tab_apps))
                when {
                    !currentRepo.enabled -> UnsyncedRepoState(
                        onEnableClick = { viewModel.enableRepository(true) },
                        address = currentRepo.address,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )

                    apps.isEmpty() && isSyncing -> TvRepoAppsMessage(loading = true)

                    apps.isEmpty() -> TvRepoAppsMessage(loading = false)

                    else -> {
                        if (notInstalledCount > 0) {
                            InstallAllButton(
                                count = notInstalledCount,
                                isInstalling = isInstallingAll,
                                onClick = viewModel::installAll,
                            )
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                            horizontalArrangement = spacedBy(12.dp),
                            verticalArrangement = spacedBy(12.dp),
                        ) {
                            gridItems(apps, key = { it.appId }, contentType = { "app-tile" }) { app ->
                                CatalogAppTile(
                                    app = app,
                                    isInstalled = app.packageName.name in installedPackages,
                                    onClick = { onAppClick(app.packageName.name) },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}

/** The repository's own card: icon, name, its address as a focusable link, the enable switch, edit and
 *  delete, then its description, last update and fingerprint, the phone's Info tab in one card. */
@Composable
private fun TvRepoCard(
    repo: com.looker.droidify.data.model.Repo,
    toggleFocus: FocusRequester,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val handler = LocalUriHandler.current
    Row(horizontalArrangement = spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        RepoIcon(
            iconUrl = repo.icon?.path,
            fallbackUrl = defaultRepoIcon(repo.address),
            name = repo.name,
            modifier = Modifier.size(96.dp),
            fallbackRes = defaultRepoIconRes(repo.address),
        )
        Column(verticalArrangement = spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(text = repo.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
        }
    }

    // The enable switch as one row (click anywhere flips it), the same interaction as the repository
    // list's rows.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(toggleFocus)
            .tvFocusFill(MaterialTheme.shapes.large)
            .clickable { onToggle(!repo.enabled) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
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

    // Edit and delete, discreet like an app's favourite and hide.
    Row(horizontalArrangement = spacedBy(8.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        TvSecondaryAction(
            icon = Icons.Default.Edit,
            label = stringResource(R.string.edit),
            active = false,
            onClick = onEdit,
        )
        TvSecondaryAction(
            icon = Icons.Default.Delete,
            label = stringResource(R.string.delete),
            active = false,
            onClick = onDelete,
        )
    }

    if (repo.description.isNotEmpty()) {
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

    if (repo.versionInfo != null) LastUpdatedCard(repo.versionInfo.timestamp)

    FingerprintCard(title = stringResource(R.string.fingerprint), content = formatFingerprint(repo))
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
