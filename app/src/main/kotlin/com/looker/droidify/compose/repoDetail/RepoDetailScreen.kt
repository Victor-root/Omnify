package com.looker.droidify.compose.repoDetail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.looker.droidify.R
import com.looker.droidify.compose.appList.CatalogAppTile
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.errorButtonColors
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.repoDetail.components.LastUpdatedCard
import com.looker.droidify.compose.repoDetail.components.UnsyncedRepoState
import com.looker.droidify.compose.repoList.RepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIconRes
import com.looker.droidify.compose.settings.components.SwitchSettingItem
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.data.model.Repo
import com.looker.droidify.utility.text.toAnnotatedString
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    onBackClick: () -> Unit,
    onEditClick: (Int) -> Unit,
    onAppClick: (String) -> Unit,
    viewModel: RepoDetailViewModel = hiltViewModel(),
) {
    val repo by viewModel.repo.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val installedPackages by viewModel.installedPackages.collectAsState()
    val notInstalledCount by viewModel.notInstalledCount.collectAsState()
    val isInstallingAll by viewModel.isInstallingAll.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(RepoDetailTab.INFO) }

    if (showDeleteDialog) {
        DeleteRepositoryDialog(
            onConfirm = {
                viewModel.deleteRepository {
                    onBackClick()
                }
                showDeleteDialog = false
            },
            onDismiss = { showDeleteDialog = false },
        )
    }

    // TV / D-pad: drop focus from the header (top bar or tab row) into the content below.
    val contentFocusRequester = remember { FocusRequester() }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.tvDpadDownTo(contentFocusRequester)) {
                TopAppBar(
                    colors = accentTopAppBarColors(),
                    expandedHeight = AccentBarHeight,
                    title = { Text(stringResource(R.string.repository)) },
                    navigationIcon = { BackButton(onBackClick) },
                    actions = {
                        IconButton(onClick = { onEditClick(viewModel.repoId) }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(R.string.edit),
                            )
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    },
                )
                if (repo != null) {
                    RepoDetailTabRow(
                        selectedTab = selectedTab,
                        appCount = apps.size,
                        onSelectTab = { selectedTab = it },
                    )
                }
            }
        },
    ) { paddingValues ->
        val currentRepo = repo
        when {
            currentRepo == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.repository_not_found))
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .focusRequester(contentFocusRequester)
                        .focusGroup(),
                ) {
                    // A single-app repo's own declared icon is often unusable (many small self-hosted
                    // repos never customise it, and fdroidserver defaults to a QR code of the repo
                    // address); its one app's real launcher icon is always the better logo. Skipped for a
                    // repo that already has a curated logo (e.g. Cromite): some of those are curated
                    // precisely because the repo is unreachable to a non-browser client for anything
                    // under its /repo path, including its own app's icon, so overriding could replace a
                    // working hand-picked logo with a URL that fails to load. Only affects the Info tab's
                    // display, not the repo object used elsewhere on this screen.
                    val hasCuratedIcon = defaultRepoIcon(currentRepo.address) != null ||
                        defaultRepoIconRes(currentRepo.address) != null
                    val singleAppIcon = if (hasCuratedIcon) null else apps.singleOrNull()?.icon
                    val infoRepo = remember(currentRepo, singleAppIcon) {
                        singleAppIcon?.let { currentRepo.copy(icon = it) } ?: currentRepo
                    }
                    when (selectedTab) {
                        RepoDetailTab.INFO -> RepoInfoTab(
                            repo = infoRepo,
                            onToggle = viewModel::enableRepository,
                        )

                        RepoDetailTab.APPS -> RepoAppsTab(
                            repo = currentRepo,
                            apps = apps,
                            installedPackages = installedPackages,
                            notInstalledCount = notInstalledCount,
                            isInstallingAll = isInstallingAll,
                            isSyncing = isSyncing,
                            onAppClick = onAppClick,
                            onEnableRepo = { viewModel.enableRepository(true) },
                            onInstallAll = viewModel::installAll,
                        )
                    }
                }
            }
        }
    }
}

private enum class RepoDetailTab { INFO, APPS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoDetailTabRow(
    selectedTab: RepoDetailTab,
    appCount: Int,
    onSelectTab: (RepoDetailTab) -> Unit,
) {
    TabRow(selectedTabIndex = selectedTab.ordinal) {
        Tab(
            selected = selectedTab == RepoDetailTab.INFO,
            onClick = { onSelectTab(RepoDetailTab.INFO) },
            text = { Text(stringResource(R.string.repo_tab_info)) },
        )
        Tab(
            selected = selectedTab == RepoDetailTab.APPS,
            onClick = { onSelectTab(RepoDetailTab.APPS) },
            text = {
                val label = if (appCount > 0) {
                    "${stringResource(R.string.repo_tab_apps)} ($appCount)"
                } else {
                    stringResource(R.string.repo_tab_apps)
                }
                Text(label)
            },
        )
    }
}

@Composable
private fun RepoInfoTab(
    repo: Repo,
    onToggle: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        RepoIcon(
            iconUrl = repo.icon?.path,
            fallbackUrl = defaultRepoIcon(repo.address),
            name = repo.name,
            modifier = Modifier.size(64.dp),
            fallbackRes = defaultRepoIconRes(repo.address),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = repo.name,
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(4.dp))

        val address = remember(repo.address) {
            buildAnnotatedString {
                withLink(LinkAnnotation.Url(repo.address)) {
                    append(repo.address)
                }
            }
        }

        Text(
            text = address,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primaryContainer,
            textDecoration = TextDecoration.Underline,
        )

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(repo.description.isNotEmpty()) {
            val handler = LocalUriHandler.current
            // Parsing the HTML description is expensive; do it once per description instead of on
            // every recomposition.
            val description = remember(repo.description) {
                repo.description.toAnnotatedString(onUrlClick = { handler.openUri(it) })
            }
            Column {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        AnimatedVisibility(repo.versionInfo != null) {
            Column {
                LastUpdatedCard(repo.versionInfo?.timestamp)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        FingerprintCard(
            title = stringResource(R.string.fingerprint),
            content = formatFingerprint(repo),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            SwitchSettingItem(
                title = stringResource(R.string.repo_enabled_title),
                description = if (repo.enabled) {
                    stringResource(R.string.repo_enabled_desc_on)
                } else {
                    stringResource(R.string.repo_enabled_desc_off)
                },
                checked = repo.enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

/**
 * The repository's own apps as the usual catalogue tile grid, so it reads exactly like every other
 * app list in Omnify.
 *
 * The empty states are deliberately precise, so an enabled repo is never wrongly labelled "not
 * synced": only a genuinely *disabled* repo shows the enable prompt. An enabled repo with no apps yet
 * shows a spinner while a sync runs, or a neutral "no apps" message once it has settled (some repos
 * legitimately serve nothing this device can install).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RepoAppsTab(
    repo: Repo,
    apps: List<AppMinimal>,
    installedPackages: Set<String>,
    notInstalledCount: Int,
    isInstallingAll: Boolean,
    isSyncing: Boolean,
    onAppClick: (String) -> Unit,
    onEnableRepo: () -> Unit,
    onInstallAll: () -> Unit,
) {
    val isTelevision = LocalIsTelevision.current
    when {
        // Genuinely off: offer to enable it. This is the ONLY case that claims "not enabled".
        !repo.enabled -> {
            UnsyncedRepoState(
                onEnableClick = onEnableRepo,
                address = repo.address,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Enabled but nothing to show yet while a sync is in flight: a spinner, not a "no apps" claim.
        apps.isEmpty() && isSyncing -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularWavyProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.syncing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Enabled, sync settled, still nothing: the repo simply serves no apps for this device.
        apps.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_applications_available),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (isTelevision) 150.dp else 100.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // One tap to download and install every app of this repo not already installed,
                // instead of opening each app. Shown only when there's at least one to fetch.
                if (notInstalledCount > 0) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "install-all") {
                        InstallAllButton(
                            count = notInstalledCount,
                            isInstalling = isInstallingAll,
                            onClick = onInstallAll,
                        )
                    }
                }
                items(apps, key = { it.appId }) { app ->
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InstallAllButton(
    count: Int,
    isInstalling: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = !isInstalling,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        if (isInstalling) {
            CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
        } else {
            Icon(imageVector = Icons.Filled.Download, contentDescription = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isInstalling) {
                stringResource(R.string.installing_all)
            } else {
                stringResource(R.string.install_all_FORMAT, count)
            },
        )
    }
}

@Composable
private fun FingerprintCard(
    title: String,
    content: AnnotatedString,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DeleteRepositoryDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = null,
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .padding(12.dp),
            )
        },
        title = { Text(stringResource(R.string.delete_repository)) },
        text = { Text(stringResource(R.string.delete_repository_confirm)) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.errorButtonColors(),
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun formatFingerprint(repo: Repo): AnnotatedString {
    return repo.fingerprint?.let { fingerprint ->
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(
                    fingerprint.value.windowed(2, 2, false)
                        .take(32).joinToString(separator = " ") { it.uppercase(Locale.US) },
                )
            }
        }
    } ?: buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
            append(stringResource(R.string.repository_unsigned_DESC))
        }
    }
}
