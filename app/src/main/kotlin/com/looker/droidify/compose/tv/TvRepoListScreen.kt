package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.externalApps.AddSourceState
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.compose.externalApps.PendingSharedSource
import com.looker.droidify.compose.repoList.AddExternalAccountDialog
import com.looker.droidify.compose.repoList.AddExternalSourceDialog
import com.looker.droidify.compose.repoList.AddSourceChooserDialog
import com.looker.droidify.compose.repoList.AppLauncherIcon
import com.looker.droidify.compose.repoList.EditExternalSourceDialog
import com.looker.droidify.compose.repoList.RepoIcon
import com.looker.droidify.compose.repoList.RepoListViewModel
import com.looker.droidify.compose.repoList.defaultRepoIcon
import com.looker.droidify.compose.repoList.defaultRepoIconRes
import com.looker.droidify.compose.settings.components.SettingHeader
import com.looker.droidify.data.model.Repo
import com.looker.droidify.external.ExternalAccount
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.utility.text.toAnnotatedString
import kotlinx.coroutines.delay

/**
 * The Android TV source-management screen: F-Droid repositories and external sources / accounts, laid
 * out in the TV visual language (overscan padding, a large title, roomy focus-scaled rows). It's a pure
 * reskin — every action (enable / disable / add / edit / remove / rescan) calls the exact same
 * [RepoListViewModel] / [ExternalAppsViewModel] methods as the phone screen, and the add / edit dialogs
 * are the very same composables, so the engine is identical to mobile. Never composed off TV.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TvRepoListScreen(
    viewModel: RepoListViewModel,
    onRepoClick: (Int) -> Unit,
    onBackClick: () -> Unit,
    onAddRepo: () -> Unit,
    onAccountClick: (String) -> Unit,
    onSourceClick: (String) -> Unit,
) {
    val repos by viewModel.stream.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncingRepoIds by viewModel.syncingRepoIds.collectAsStateWithLifecycle()

    // The External sources share this screen with the F-Droid repos: it's source management (enable /
    // disable / add / remove). The apps themselves live on the External tab. Same singleton repositories
    // back both, so the lists stay in sync everywhere — exactly as on the phone screen.
    val externalViewModel: ExternalAppsViewModel = hiltViewModel()
    val externalApps by externalViewModel.apps.collectAsStateWithLifecycle()
    val externalAccounts by externalViewModel.accounts.collectAsStateWithLifecycle()
    val externalInstalledKeys by externalViewModel.installedKeys.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        externalViewModel.refreshInstalled()
        externalViewModel.reconcileInstalledLabels()
    }

    var showAddChooser by rememberSaveable { mutableStateOf(false) }
    var showAddExternal by rememberSaveable { mutableStateOf(false) }
    var showAddAccount by rememberSaveable { mutableStateOf(false) }
    var editingExternal by remember { mutableStateOf<ExternalApp?>(null) }
    var prefillUrl by rememberSaveable { mutableStateOf("") }

    // A link shared into the app (system "Share" sheet) waiting to be added — one-shot, same handling as
    // the phone screen: reading it opens the right dialog pre-filled, then we clear it.
    val pendingShare by PendingSharedSource.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        val share = pendingShare ?: return@LaunchedEffect
        prefillUrl = share.url
        if (share.isAccount) showAddAccount = true else showAddExternal = true
        PendingSharedSource.clear()
    }

    // Same grouping as the phone screen (see RepoListScreen): accounts fold their discovered apps in, so
    // the flat source list shows only standalone (single-repo) sources; Omnify's own curated picks get
    // their own section, with the TV pack as a light sub-group inside it.
    val sortedAccounts = remember(externalAccounts) {
        externalAccounts.sortedBy { it.label.trim().lowercase() }
    }
    val accountAppCounts = remember(externalApps) {
        externalApps.mapNotNull { it.accountKey }.groupingBy { it }.eachCount()
    }
    val sortedExternalApps = remember(externalApps) {
        externalApps.filter { it.accountKey == null }.sortedBy { it.label.trim().lowercase() }
    }
    val regularAccounts = remember(sortedAccounts) { sortedAccounts.filter { !it.curated } }
    val curatedAccounts = remember(sortedAccounts) { sortedAccounts.filter { it.curated } }
    val regularExternalApps = remember(sortedExternalApps) { sortedExternalApps.filter { !it.curated } }
    val curatedExternalApps = remember(sortedExternalApps) {
        sortedExternalApps.filter { it.curated && !it.curatedTv }
            .sortedBy { if (it.key == ExternalApp.OMNIFY_REPO_KEY) "" else it.label.trim().lowercase() }
    }
    val curatedTvApps = remember(sortedExternalApps) {
        sortedExternalApps.filter { it.curated && it.curatedTv }
            .sortedBy { it.label.trim().lowercase() }
    }
    val sortedRepos = remember(repos) { repos.sortedBy { it.name.trim().lowercase() } }

    BackHandler { onBackClick() }

    // Android TV must always land the D-pad focus somewhere on entry, or a remote press with nothing
    // focused times out input dispatch and kills the app. Retried briefly because the list isn't laid
    // out on the very first frame.
    val contentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { contentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvAccentBackground()
        LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(contentFocus)
            .focusGroup(),
        contentPadding = PaddingValues(
            start = TvOverscan + 8.dp,
            end = TvOverscan + 8.dp,
            top = TvOverscan,
            bottom = TvOverscan + 24.dp,
        ),
        verticalArrangement = spacedBy(6.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = spacedBy(12.dp)) {
                TvBackButton(onBackClick)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.repositories),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TvAddSourceButton(onClick = { showAddChooser = true })
                }
                if (isSyncing) {
                    Column {
                        Text(
                            text = stringResource(R.string.syncing),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }

        // External sources / accounts the user added themselves.
        item(key = "external-header") { SettingHeader(stringResource(R.string.repo_section_external)) }
        if (regularAccounts.isEmpty() && regularExternalApps.isEmpty()) {
            item(key = "external-empty") {
                Text(
                    text = stringResource(R.string.external_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        items(regularAccounts, key = { "acc-${it.key}" }) { account ->
            TvAccountRow(
                account = account,
                appCount = accountAppCounts[account.key] ?: 0,
                onOpen = { onAccountClick(account.key) },
                onToggle = { externalViewModel.setAccountEnabled(account, !account.enabled) },
                onRescan = { externalViewModel.rescanAccount(account) },
                onRemove = { externalViewModel.removeAccount(account) },
            )
        }
        items(regularExternalApps, key = { "ext-${it.key}" }) { app ->
            TvSourceRow(
                app = app,
                isInstalled = app.key in externalInstalledKeys,
                onOpen = { onSourceClick(app.key) },
                onToggle = { externalViewModel.setSourceEnabled(app, !app.enabled) },
                onEdit = { editingExternal = app },
                onRemove = { externalViewModel.remove(app.key) },
            )
        }

        // F-Droid repositories.
        item(key = "repos-header") { SettingHeader(stringResource(R.string.repo_section_fdroid)) }
        items(sortedRepos, key = { "repo-${it.id}" }) { repo ->
            TvRepoRow(
                repo = repo,
                isSyncing = repo.id in syncingRepoIds,
                onOpen = { onRepoClick(repo.id) },
                onToggle = { viewModel.toggleRepo(repo) },
            )
        }

        // Omnify's own curated picks (the repo, the Victor-root account, and the Made-for-TV pack).
        item(key = "omnify-picks-header") {
            SettingHeader(stringResource(R.string.repo_section_omnify_picks))
        }
        items(curatedExternalApps, key = { "cur-ext-${it.key}" }) { app ->
            TvSourceRow(
                app = app,
                isInstalled = app.key in externalInstalledKeys,
                onOpen = { onSourceClick(app.key) },
                onToggle = { externalViewModel.setSourceEnabled(app, !app.enabled) },
                onEdit = null,
                onRemove = null,
                brandWithAppIcon = app.key == ExternalApp.OMNIFY_REPO_KEY,
            )
        }
        items(curatedAccounts, key = { "cur-acc-${it.key}" }) { account ->
            TvAccountRow(
                account = account,
                appCount = accountAppCounts[account.key] ?: 0,
                onOpen = { onAccountClick(account.key) },
                onToggle = { externalViewModel.setAccountEnabled(account, !account.enabled) },
                onRescan = { externalViewModel.rescanAccount(account) },
                onRemove = { externalViewModel.removeAccount(account) },
            )
        }
        if (curatedTvApps.isNotEmpty()) {
            item(key = "omnify-picks-tv-subheader") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.discover_tv_apps),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(curatedTvApps, key = { "cur-tv-${it.key}" }) { app ->
                TvSourceRow(
                    app = app,
                    isInstalled = app.key in externalInstalledKeys,
                    onOpen = { onSourceClick(app.key) },
                    onToggle = { externalViewModel.setSourceEnabled(app, !app.enabled) },
                    onEdit = null,
                    onRemove = null,
                )
            }
        }
    }
    }

    if (showAddChooser) {
        AddSourceChooserDialog(
            onDismiss = { showAddChooser = false },
            onChooseFdroid = {
                showAddChooser = false
                onAddRepo()
            },
            onChooseExternal = {
                showAddChooser = false
                showAddExternal = true
            },
            onChooseAccount = {
                showAddChooser = false
                showAddAccount = true
            },
        )
    }
    if (showAddAccount) {
        val addState by externalViewModel.addState.collectAsStateWithLifecycle()
        val hasGithubToken by externalViewModel.hasGithubToken.collectAsStateWithLifecycle()
        val githubRemaining by externalViewModel.githubRateLimitRemaining.collectAsStateWithLifecycle()
        LaunchedEffect(addState) {
            if (addState == AddSourceState.SUCCESS) {
                showAddAccount = false
                prefillUrl = ""
                externalViewModel.consumeAddState()
            }
        }
        AddExternalAccountDialog(
            isLoading = addState != AddSourceState.IDLE,
            hasGithubToken = hasGithubToken,
            githubRemaining = githubRemaining,
            initialUrl = prefillUrl,
            onDismiss = {
                showAddAccount = false
                prefillUrl = ""
                externalViewModel.consumeAddState()
            },
            onAdd = { url, customName, includeForks, includePrereleases, muteUpdates, apkFilter ->
                externalViewModel.addAccount(
                    url = url,
                    customName = customName,
                    includeForks = includeForks,
                    includePrereleases = includePrereleases,
                    muteUpdates = muteUpdates,
                    apkFilter = apkFilter,
                )
            },
        )
    }
    if (showAddExternal) {
        val addState by externalViewModel.addState.collectAsStateWithLifecycle()
        val addError by externalViewModel.addError.collectAsStateWithLifecycle()
        val hasGithubToken by externalViewModel.hasGithubToken.collectAsStateWithLifecycle()
        val githubRemaining by externalViewModel.githubRateLimitRemaining.collectAsStateWithLifecycle()
        LaunchedEffect(addState) {
            if (addState == AddSourceState.SUCCESS) {
                showAddExternal = false
                prefillUrl = ""
                externalViewModel.consumeAddState()
            }
        }
        AddExternalSourceDialog(
            isLoading = addState != AddSourceState.IDLE,
            errorMessage = addError,
            hasGithubToken = hasGithubToken,
            githubRemaining = githubRemaining,
            initialUrl = prefillUrl,
            onDismiss = {
                showAddExternal = false
                prefillUrl = ""
                externalViewModel.consumeAddState()
            },
            onAdd = { url, includePrereleases, customName, muteUpdates, apkFilter ->
                externalViewModel.addSource(
                    url = url,
                    includePrereleases = includePrereleases,
                    customName = customName,
                    muteUpdates = muteUpdates,
                    apkFilter = apkFilter,
                )
            },
        )
    }
    editingExternal?.let { app ->
        val iconCandidates by produceState<List<String>?>(initialValue = null, app.key) {
            value = externalViewModel.loadIconCandidates(app)
        }
        EditExternalSourceDialog(
            app = app,
            iconCandidates = iconCandidates,
            onDismiss = { editingExternal = null },
            onSave = { customName, includePrereleases, muteUpdates, apkFilter, iconUrl ->
                externalViewModel.updateSource(
                    app = app,
                    customName = customName,
                    includePrereleases = includePrereleases,
                    muteUpdates = muteUpdates,
                    apkFilter = apkFilter,
                    iconUrl = iconUrl,
                )
                editingExternal = null
            },
        )
    }
}

/** The "+" add-source action in the header, sized and focus-scaled for the remote. */
@Composable
private fun TvAddSourceButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.tvFocusScale()) {
        Icon(
            painter = painterResource(R.drawable.ic_tabler_plus),
            contentDescription = stringResource(R.string.add_source_title),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Shared shell for a management row. On TV the whole row (icon, text AND the switch) is a single focus
 * target: clicking anywhere flips the enable switch — the simplest interaction on a remote. The switch is
 * display-only, and the secondary actions (open/details, edit, remove, rescan) live in the overflow menu
 * beside it, which is the only other focus target.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvManagementRow(
    onOpen: () -> Unit,
    enabled: Boolean,
    onToggle: () -> Unit,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: @Composable () -> Unit,
    extraMenuItems: (@Composable (dismiss: () -> Unit) -> Unit)? = null,
    progress: (@Composable () -> Unit)? = null,
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Column(
        // A focusGroup (never itself focused) so the row-toggle target and the overflow menu are true
        // siblings the remote moves between — a focusable parent containing focusable children breaks
        // D-pad descent (see the phone screen's RepoItem for the same reasoning).
        modifier = Modifier.fillMaxWidth().focusGroup(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .tvFocusFill(RoundedCornerShape(16.dp))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Box(modifier = Modifier.size(56.dp).alpha(contentAlpha)) { icon() }
                Spacer(Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f).alpha(contentAlpha)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle()
                }
                Spacer(Modifier.size(12.dp))
                // Display-only (onCheckedChange = null) — it isn't a second focus target; the row toggles
                // it. Accent-coloured when on, like the settings rows.
                Switch(checked = enabled, onCheckedChange = null)
            }
            Spacer(Modifier.size(8.dp))
            if (extraMenuItems == null) {
                // Only "details" would be in the menu, so skip the kebab and offer a direct info button.
                IconButton(onClick = onOpen, modifier = Modifier.tvFocusScale()) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tv_info),
                        contentDescription = stringResource(R.string.details),
                    )
                }
            } else {
                TvOverflowMenu { dismiss ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.details)) },
                        onClick = { dismiss(); onOpen() },
                    )
                    extraMenuItems.invoke(dismiss)
                }
            }
        }
        progress?.invoke()
    }
}

/** A kebab (⋮) overflow button opening a dropdown of row actions, focus-scaled for the remote. */
@Composable
private fun TvOverflowMenu(content: @Composable (dismiss: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.tvFocusScale()) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.more_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

/** An F-Droid repository management row. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvRepoRow(
    repo: Repo,
    isSyncing: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    TvManagementRow(
        onOpen = onOpen,
        enabled = repo.enabled,
        onToggle = onToggle,
        icon = {
            RepoIcon(
                iconUrl = repo.icon?.path,
                fallbackUrl = defaultRepoIcon(repo.address),
                name = repo.name,
                modifier = Modifier.size(56.dp),
                fallbackRes = defaultRepoIconRes(repo.address),
            )
        },
        title = repo.name,
        subtitle = {
            if (repo.description.isNotEmpty()) {
                Text(
                    text = repo.description.toAnnotatedString { },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        progress = if (isSyncing) {
            {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 2.dp, bottom = 8.dp),
                )
            }
        } else {
            null
        },
    )
}

/** A single external source (single-repo) management row. */
@Composable
private fun TvSourceRow(
    app: ExternalApp,
    isInstalled: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    brandWithAppIcon: Boolean = false,
) {
    TvManagementRow(
        onOpen = onOpen,
        enabled = app.enabled,
        onToggle = onToggle,
        icon = {
            if (brandWithAppIcon) {
                AppLauncherIcon(modifier = Modifier.size(56.dp))
            } else {
                ExternalAppIcon(app = app, isInstalled = isInstalled, size = 56.dp)
            }
        },
        title = app.label,
        subtitle = {
            Text(
                text = "${app.sourceLabel} · ${app.path}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extraMenuItems = if (onEdit != null || onRemove != null) {
            { dismiss ->
                if (onEdit != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.external_edit_source)) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { dismiss(); onEdit() },
                    )
                }
                if (onRemove != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.external_remove)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_tabler_trash), contentDescription = null)
                        },
                        onClick = { dismiss(); onRemove() },
                    )
                }
            }
        } else {
            null
        },
    )
}

/** A whole-account external source management row. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvAccountRow(
    account: ExternalAccount,
    appCount: Int,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    TvManagementRow(
        onOpen = onOpen,
        enabled = account.enabled,
        onToggle = onToggle,
        icon = {
            if (account.key == ExternalAccount.OMNIFY_KEY) {
                AppLauncherIcon(modifier = Modifier.size(56.dp))
            } else {
                RepoIcon(
                    iconUrl = account.iconUrl,
                    fallbackUrl = null,
                    name = account.label,
                    modifier = Modifier.size(56.dp),
                )
            }
        },
        title = account.label,
        subtitle = {
            val isScanning = account.enabled && appCount == 0 && account.lastScan == 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isScanning) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(12.dp))
                    Spacer(Modifier.size(6.dp))
                }
                val status = when {
                    appCount > 0 -> stringResource(R.string.external_account_apps, appCount)
                    !account.enabled -> stringResource(R.string.external_account_disabled)
                    isScanning -> stringResource(R.string.external_account_scanning)
                    else -> stringResource(R.string.external_account_apps, 0)
                }
                Text(
                    text = "${account.sourceLabel} · $status",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        extraMenuItems = { dismiss ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.external_account_rescan)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_tabler_refresh), contentDescription = null)
                },
                onClick = { dismiss(); onRescan() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.external_remove)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_tabler_trash), contentDescription = null)
                },
                onClick = { dismiss(); onRemove() },
            )
        },
    )
}
