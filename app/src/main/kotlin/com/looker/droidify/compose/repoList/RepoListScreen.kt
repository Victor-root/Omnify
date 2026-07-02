package com.looker.droidify.compose.repoList

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.externalApps.AddSourceState
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.compose.externalApps.PendingSharedSource
import com.looker.droidify.data.model.Repo
import com.looker.droidify.external.ExternalAccount
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.utility.text.toAnnotatedString
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.accentTopAppBarColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RepoListScreen(
    viewModel: RepoListViewModel,
    onRepoClick: (Int) -> Unit,
    onBackClick: () -> Unit,
    onAddRepo: () -> Unit,
) {
    val repos by viewModel.stream.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()

    // The External sources live alongside the F-Droid repos here: this screen is source management
    // (enable / disable / add / remove). The apps themselves (install, launch…) are on the External
    // tab. Both are backed by the same singleton repositories, so the lists stay in sync everywhere.
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

    // URL pre-filled into the add dialog when the screen was opened from a shared link. Held here so it
    // can seed whichever dialog we open.
    var prefillUrl by rememberSaveable { mutableStateOf("") }
    // A link shared into the app (system "Share" sheet) waiting to be added. It's a one-shot: reading it
    // opens the right dialog pre-filled, then we clear it so it can't reopen — the screen is rebuilt
    // several times around the add, and anything persistent (a nav arg) would re-trigger every time.
    val pendingShare by PendingSharedSource.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShare) {
        val share = pendingShare ?: return@LaunchedEffect
        prefillUrl = share.url
        // A whole-account link (owner only) opens the account dialog; a single repo link the source
        // dialog. The chooser is skipped: we already know which one from the URL shape.
        if (share.isAccount) showAddAccount = true else showAddExternal = true
        PendingSharedSource.clear()
    }

    // Each section lists its sources one after another, sorted alphabetically by display name. Now that
    // every repo has a real logo (or a letter monogram), the icons make scanning easy without grouping.
    // Account sources show as one row each; the apps they discovered are folded into that account, so the
    // flat list shows only standalone (single-repo) sources, with the accounts listed above them.
    val sortedAccounts = remember(externalAccounts) {
        externalAccounts.sortedBy { it.label.trim().lowercase() }
    }
    val accountAppCounts = remember(externalApps) {
        externalApps.mapNotNull { it.accountKey }.groupingBy { it }.eachCount()
    }
    val sortedExternalApps = remember(externalApps) {
        externalApps.filter { it.accountKey == null }.sortedBy { it.label.trim().lowercase() }
    }
    val sortedRepos = remember(repos) {
        repos.sortedBy { it.name.trim().lowercase() }
    }

    // TV / D-pad: the top bar doesn't release focus downward on its own; this lets "down" drop from the
    // header into the list. No effect on touch.
    val contentFocusRequester = remember { FocusRequester() }

    Scaffold(
        snackbarHost = { SnackbarHost(externalViewModel.snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    colors = accentTopAppBarColors(),
                    expandedHeight = AccentBarHeight,
                    modifier = Modifier.tvDpadDownTo(contentFocusRequester),
                    title = { Text(text = stringResource(R.string.repositories)) },
                    navigationIcon = { BackButton(onBackClick) },
                    actions = {
                        IconButton(onClick = { showAddChooser = true }) {
                            Icon(
                                painterResource(R.drawable.ic_tabler_plus),
                                contentDescription = stringResource(R.string.add_source_title),
                            )
                        }
                    },
                )
                if (isSyncing) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }
            }
        },
    ) { contentPadding ->
        LazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier
                .focusRequester(contentFocusRequester)
                .focusGroup(),
        ) {
            item(key = "external-header") {
                SectionHeader(title = stringResource(R.string.tab_external))
            }
            if (sortedAccounts.isEmpty() && sortedExternalApps.isEmpty()) {
                item(key = "external-empty") {
                    Text(
                        text = stringResource(R.string.external_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // The built-in Omnify repo is pinned at the very top and can only be enabled/disabled (no
            // edit/remove, since it's the app's own update channel).
            val omnifyApp = sortedExternalApps.firstOrNull { it.key == ExternalApp.OMNIFY_REPO_KEY }
            if (omnifyApp != null) {
                item(key = "ext-${omnifyApp.key}") {
                    ExternalSourceItem(
                        app = omnifyApp,
                        isInstalled = omnifyApp.key in externalInstalledKeys,
                        onToggle = { externalViewModel.setSourceEnabled(omnifyApp, !omnifyApp.enabled) },
                        onEdit = null,
                        onRemove = null,
                        brandWithAppIcon = true,
                    )
                }
            }
            items(sortedAccounts, key = { "acc-${it.key}" }) { account ->
                ExternalAccountItem(
                    account = account,
                    appCount = accountAppCounts[account.key] ?: 0,
                    onToggle = { externalViewModel.setAccountEnabled(account, !account.enabled) },
                    onRescan = { externalViewModel.rescanAccount(account) },
                    onRemove = { externalViewModel.removeAccount(account) },
                )
            }
            items(
                sortedExternalApps.filter { it.key != ExternalApp.OMNIFY_REPO_KEY },
                key = { "ext-${it.key}" },
            ) { app ->
                ExternalSourceItem(
                    app = app,
                    isInstalled = app.key in externalInstalledKeys,
                    onToggle = { externalViewModel.setSourceEnabled(app, !app.enabled) },
                    onEdit = { editingExternal = app },
                    onRemove = { externalViewModel.remove(app.key) },
                )
            }

            item(key = "repos-header") {
                SectionHeader(title = stringResource(R.string.repo_section_fdroid))
            }
            items(sortedRepos, key = { "repo-${it.id}" }) { repo ->
                RepoItem(
                    onClick = { onRepoClick(repo.id) },
                    onToggle = { viewModel.toggleRepo(repo) },
                    repo = repo,
                )
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
        LaunchedEffect(addState) {
            if (addState == AddSourceState.SUCCESS) {
                showAddAccount = false
                prefillUrl = ""
                externalViewModel.consumeAddState()
            }
        }
        AddExternalAccountDialog(
            isLoading = addState == AddSourceState.LOADING,
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
        // Keep the dialog up (with a spinner) until the add actually finishes, then close on success —
        // so a slow GitHub response can't make it look like nothing happened.
        LaunchedEffect(addState) {
            if (addState == AddSourceState.SUCCESS) {
                showAddExternal = false
                prefillUrl = ""
                externalViewModel.consumeAddState()
            }
        }
        AddExternalSourceDialog(
            isLoading = addState == AddSourceState.LOADING,
            errorMessage = addError,
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
        // Launcher icons found in the repo, for the picker. null = still loading, empty = none found.
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

/** The uppercase first letter (or digit) of a name for the monogram avatar; non-alphanumeric starts
 *  (and anything past Z) fold to '#'. */
private fun letterOf(name: String): Char {
    val first = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return '#'
    return if (first in 'A'..'Z') first else '#'
}

/** A repo's icon: a bundled glyph ([fallbackRes]) when set, else the synced repo icon when it loads,
 *  else the themed letter monogram, so every repo has a distinct, recognizable avatar even when its
 *  index ships no icon. */
@Composable
internal fun RepoIcon(
    iconUrl: String?,
    fallbackUrl: String?,
    name: String,
    modifier: Modifier = Modifier,
    fallbackRes: Int? = null,
) {
    val shape = MaterialTheme.shapes.large
    // Prefer our curated logo when we have one: those default repos were hand-picked precisely because
    // their own declared icon is unusable (the generic F-Droid placeholder or a QR code), so a synced
    // icon must not override it once the repo is enabled and synced. Repos with no curated logo just use
    // their synced icon. Only the letter monogram remains if neither is available or the image fails to
    // load. Logos are always shown in full colour (no greyscale/dim): the toggle already signals the
    // on/off state, and most default repos are disabled, so dimming them all made the list look faded.
    val url = fallbackUrl?.takeIf { it.isNotBlank() } ?: iconUrl
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier = modifier.clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // A bundled glyph (the "multiple apps" icon for collection repos) wins over the synced icon,
            // which for those repos is only a QR code. Drawn as a tinted glyph on a themed tile.
            fallbackRes != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(fallbackRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                )
            }

            !url.isNullOrBlank() && !failed -> AsyncImage(
                model = url,
                contentDescription = null,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )

            else -> MonogramAvatar(name = name)
        }
    }
}

/** The fallback avatar: the name's first letter on a theme container colour, picked deterministically
 *  from the letter so a given repo keeps a stable colour. The caller handles any disabled dimming. */
@Composable
private fun MonogramAvatar(name: String) {
    val letter = letterOf(name)
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
    )
    val (background, foreground) = palette[letter.code % palette.size]
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = foreground,
        )
    }
}

/** The app's own launcher icon, rendered from the package manager (always present and correct across
 *  Android versions). Used to brand the built-in Omnify account row. */
@Composable
private fun AppLauncherIcon(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = MaterialTheme.shapes.large
    val icon = remember {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(width = 192, height = 192)
                .asImageBitmap()
        }.getOrNull()
    }
    Box(modifier = modifier.clip(shape), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            MonogramAvatar(name = "Omnify")
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun RepoItem(
    onClick: () -> Unit,
    onToggle: () -> Unit,
    repo: Repo,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(modifier),
    ) {
        RepoIcon(
            iconUrl = repo.icon?.path,
            fallbackUrl = defaultRepoIcon(repo.address),
            name = repo.name,
            modifier = Modifier.size(48.dp),
            fallbackRes = defaultRepoIconRes(repo.address),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = repo.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (repo.description.isNotEmpty()) {
                Text(
                    text = repo.description.toAnnotatedString { },
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 4,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        FilledIconToggleButton(
            checked = repo.enabled,
            onCheckedChange = { onToggle() },
        ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null)
        }
    }
}

/** A tracked external source as a management row: logo, name, owner/repo, an enable/disable toggle
 *  (like a repo) and edit/remove buttons. App actions (install, launch…) intentionally live elsewhere,
 *  on the External tab; this row only manages the source. [onEdit]/[onRemove] are null for a pinned
 *  built-in source (the Omnify repo), which can only be toggled. */
@Composable
private fun ExternalSourceItem(
    app: ExternalApp,
    isInstalled: Boolean,
    onToggle: () -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    // The built-in Omnify source is branded with the app's own launcher icon (always correct), instead
    // of the discovered repo/avatar icon which would otherwise fall back to the GitHub owner's avatar.
    brandWithAppIcon: Boolean = false,
) {
    val contentAlpha = if (app.enabled) 1f else 0.4f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        if (brandWithAppIcon) {
            AppLauncherIcon(modifier = Modifier.size(48.dp).alpha(contentAlpha))
        } else {
            ExternalAppIcon(
                app = app,
                isInstalled = isInstalled,
                size = 48.dp,
                modifier = Modifier.alpha(contentAlpha),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        Column(
            modifier = Modifier
                .weight(1F)
                .alpha(contentAlpha),
        ) {
            Text(
                text = app.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${app.sourceLabel} · ${app.path}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        FilledIconToggleButton(
            checked = app.enabled,
            onCheckedChange = { onToggle() },
        ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null)
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.external_edit_source),
                )
            }
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
}

/** A whole-account source as a management row: the account avatar, its name, "provider · N apps", an
 *  enable/disable toggle (which cascades to its apps), a rescan button (look for newly published apps)
 *  and a remove button. The account's individual apps appear on the External tab. */
@Composable
private fun ExternalAccountItem(
    account: ExternalAccount,
    appCount: Int,
    onToggle: () -> Unit,
    onRescan: () -> Unit,
    onRemove: () -> Unit,
) {
    val contentAlpha = if (account.enabled) 1f else 0.4f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        if (account.key == ExternalAccount.OMNIFY_KEY) {
            // The built-in Omnify source is branded with the app's own logo so it's recognisable.
            AppLauncherIcon(
                modifier = Modifier
                    .size(48.dp)
                    .alpha(contentAlpha),
            )
        } else {
            RepoIcon(
                iconUrl = account.iconUrl,
                fallbackUrl = null,
                name = account.label,
                modifier = Modifier
                    .size(48.dp)
                    .alpha(contentAlpha),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        Column(
            modifier = Modifier
                .weight(1F)
                .alpha(contentAlpha),
        ) {
            Text(text = account.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Subtitle: the app count once known; "disabled" while off (it isn't scanned until enabled);
            // "searching…" during the first scan of a freshly-enabled account.
            val status = when {
                appCount > 0 -> stringResource(R.string.external_account_apps, appCount)
                !account.enabled -> stringResource(R.string.external_account_disabled)
                account.lastScan == 0L -> stringResource(R.string.external_account_scanning)
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
        Spacer(modifier = Modifier.size(8.dp))
        FilledIconToggleButton(
            checked = account.enabled,
            onCheckedChange = { onToggle() },
        ) {
            Icon(imageVector = Icons.Default.Check, contentDescription = null)
        }
        IconButton(onClick = onRescan) {
            Icon(
                painter = painterResource(R.drawable.ic_tabler_refresh),
                contentDescription = stringResource(R.string.external_account_rescan),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                painter = painterResource(R.drawable.ic_tabler_trash),
                contentDescription = stringResource(R.string.external_remove),
            )
        }
    }
}

/** Asks which kind of source to add: an F-Droid repository, a single external repo/app, or a whole
 *  external account (all of its apps), explaining each, then routes to the matching add flow. */
@Composable
private fun AddSourceChooserDialog(
    onDismiss: () -> Unit,
    onChooseFdroid: () -> Unit,
    onChooseExternal: () -> Unit,
    onChooseAccount: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_source_title)) },
        text = {
            Column {
                AddSourceOption(
                    iconRes = R.drawable.ic_tabler_box,
                    title = stringResource(R.string.add_source_fdroid),
                    description = stringResource(R.string.add_source_fdroid_desc),
                    onClick = onChooseFdroid,
                )
                Spacer(Modifier.height(8.dp))
                AddSourceOption(
                    iconRes = R.drawable.ic_apk_install,
                    title = stringResource(R.string.add_source_external),
                    description = stringResource(R.string.add_source_external_desc),
                    onClick = onChooseExternal,
                )
                Spacer(Modifier.height(8.dp))
                AddSourceOption(
                    iconRes = R.drawable.ic_person,
                    title = stringResource(R.string.add_source_account),
                    description = stringResource(R.string.add_source_account_desc),
                    onClick = onChooseAccount,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** One tappable option (icon + bold title + explanation) in the add-source chooser. */
@Composable
private fun AddSourceOption(
    iconRes: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddExternalSourceDialog(
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onAdd: (
        url: String,
        includePrereleases: Boolean,
        customName: String,
        muteUpdates: Boolean,
        apkFilter: String,
    ) -> Unit,
    initialUrl: String = "",
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var name by rememberSaveable { mutableStateOf("") }
    var includePrereleases by rememberSaveable { mutableStateOf(false) }
    var muteUpdates by rememberSaveable { mutableStateOf(false) }
    var apkFilter by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        // Stay cancellable even while loading, so a slow/stuck request can't trap the user.
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.external_add_source)) },
        text = {
            if (isLoading) {
                // While the add runs (release lookup + repo metadata), show a clear "working" state so
                // a slow network never looks like a no-op.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularWavyProgressIndicator()
                    Text(
                        text = stringResource(R.string.external_adding),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.external_source_url_label)) },
                        singleLine = true,
                        isError = errorMessage != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The failure reason shown inline (a snackbar would sit behind the dialog scrim).
                    if (errorMessage != null) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    SourceOptionFields(
                        name = name,
                        onNameChange = { name = it },
                        nameHint = null,
                        includePrereleases = includePrereleases,
                        onPrereleasesChange = { includePrereleases = it },
                        muteUpdates = muteUpdates,
                        onMuteChange = { muteUpdates = it },
                        apkFilter = apkFilter,
                        onApkFilterChange = { apkFilter = it },
                    )
                }
            }
        },
        confirmButton = {
            if (!isLoading) {
                TextButton(
                    onClick = { onAdd(url, includePrereleases, name, muteUpdates, apkFilter) },
                    enabled = url.isNotBlank(),
                ) {
                    Text(stringResource(R.string.external_add))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Adds a whole-account source: an account URL plus the same per-source options (applied to every
 *  discovered app) and an "include forks" toggle (some accounts publish their apps as forks). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddExternalAccountDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onAdd: (
        url: String,
        customName: String,
        includeForks: Boolean,
        includePrereleases: Boolean,
        muteUpdates: Boolean,
        apkFilter: String,
    ) -> Unit,
    initialUrl: String = "",
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var name by rememberSaveable { mutableStateOf("") }
    var includeForks by rememberSaveable { mutableStateOf(false) }
    var includePrereleases by rememberSaveable { mutableStateOf(false) }
    var muteUpdates by rememberSaveable { mutableStateOf(false) }
    var apkFilter by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.external_add_account)) },
        text = {
            if (isLoading) {
                // Discovery lists the account's repos and checks each for a release, which can take a
                // while, so show a clear "working" state.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularWavyProgressIndicator()
                    Text(
                        text = stringResource(R.string.external_account_adding),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.external_account_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(8.dp))
                    SourceOptionFields(
                        name = name,
                        onNameChange = { name = it },
                        nameHint = null,
                        includePrereleases = includePrereleases,
                        onPrereleasesChange = { includePrereleases = it },
                        muteUpdates = muteUpdates,
                        onMuteChange = { muteUpdates = it },
                        apkFilter = apkFilter,
                        onApkFilterChange = { apkFilter = it },
                    )
                    CheckboxRow(
                        checked = includeForks,
                        onCheckedChange = { includeForks = it },
                        label = stringResource(R.string.external_include_forks),
                    )
                }
            }
        },
        confirmButton = {
            if (!isLoading) {
                TextButton(
                    onClick = { onAdd(url, name, includeForks, includePrereleases, muteUpdates, apkFilter) },
                    enabled = url.isNotBlank(),
                ) {
                    Text(stringResource(R.string.external_add))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Edits an existing external source's settings (the URL is fixed, so it isn't shown). */
@Composable
private fun EditExternalSourceDialog(
    app: ExternalApp,
    iconCandidates: List<String>?,
    onDismiss: () -> Unit,
    onSave: (
        customName: String,
        includePrereleases: Boolean,
        muteUpdates: Boolean,
        apkFilter: String,
        iconUrl: String?,
    ) -> Unit,
) {
    var name by rememberSaveable(app.key) {
        mutableStateOf(if (app.nameOverridden) app.label else "")
    }
    var includePrereleases by rememberSaveable(app.key) { mutableStateOf(app.includePrereleases) }
    var muteUpdates by rememberSaveable(app.key) { mutableStateOf(app.muteUpdates) }
    var apkFilter by rememberSaveable(app.key) { mutableStateOf(app.apkFilter ?: "") }
    // The chosen icon URL; null means "use the account avatar / automatic".
    var selectedIcon by rememberSaveable(app.key) { mutableStateOf(app.repoIconUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.external_edit_source)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "${app.sourceLabel} · ${app.path}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                IconPickerSection(
                    candidates = iconCandidates,
                    avatarUrl = app.iconUrl,
                    selected = selectedIcon,
                    onSelect = { selectedIcon = it },
                )
                Spacer(Modifier.size(8.dp))
                SourceOptionFields(
                    name = name,
                    onNameChange = { name = it },
                    nameHint = app.label,
                    includePrereleases = includePrereleases,
                    onPrereleasesChange = { includePrereleases = it },
                    muteUpdates = muteUpdates,
                    onMuteChange = { muteUpdates = it },
                    apkFilter = apkFilter,
                    onApkFilterChange = { apkFilter = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, includePrereleases, muteUpdates, apkFilter, selectedIcon) },
            ) {
                Text(stringResource(R.string.external_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * The icon picker shown in the edit dialog: the launcher icons found in the source repo, plus the
 * account avatar, as selectable thumbnails. Selecting the avatar means "automatic". While the repo is
 * being scanned a small spinner shows; when nothing is found the section is hidden and the card simply
 * falls back to the avatar.
 */
@Composable
private fun IconPickerSection(
    candidates: List<String>?,
    avatarUrl: String?,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    when {
        candidates == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.external_icon_searching),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        candidates.isEmpty() -> Unit

        else -> {
            Text(
                text = stringResource(R.string.external_icon_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                candidates.forEach { url ->
                    IconChoice(url = url, selected = selected == url, onClick = { onSelect(url) })
                }
                if (avatarUrl != null) {
                    IconChoice(
                        url = avatarUrl,
                        selected = selected == null,
                        onClick = { onSelect(null) },
                    )
                }
            }
        }
    }
}

/** A single selectable icon thumbnail, ringed in the accent colour when chosen. */
@Composable
private fun IconChoice(url: String, selected: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = shape,
            )
            .clickable(onClick = onClick),
    )
}

/** Per-source option fields shared by the add and edit dialogs. */
@Composable
private fun SourceOptionFields(
    name: String,
    onNameChange: (String) -> Unit,
    nameHint: String?,
    includePrereleases: Boolean,
    onPrereleasesChange: (Boolean) -> Unit,
    muteUpdates: Boolean,
    onMuteChange: (Boolean) -> Unit,
    apkFilter: String,
    onApkFilterChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.external_custom_name)) },
        placeholder = if (nameHint != null) {
            { Text(nameHint) }
        } else {
            null
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.size(8.dp))
    OutlinedTextField(
        value = apkFilter,
        onValueChange = onApkFilterChange,
        label = { Text(stringResource(R.string.external_apk_filter)) },
        supportingText = { Text(stringResource(R.string.external_apk_filter_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    CheckboxRow(
        checked = includePrereleases,
        onCheckedChange = onPrereleasesChange,
        label = stringResource(R.string.external_include_prereleases),
    )
    CheckboxRow(
        checked = muteUpdates,
        onCheckedChange = onMuteChange,
        label = stringResource(R.string.external_mute_updates),
    )
}

@Composable
private fun CheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
