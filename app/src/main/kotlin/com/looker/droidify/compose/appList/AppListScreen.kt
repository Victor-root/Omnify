package com.looker.droidify.compose.appList

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults.IconButtonWidthOption.Companion.Narrow
import androidx.compose.material3.IconButtonDefaults.smallContainerSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.looker.droidify.R
import com.looker.droidify.compose.components.CatalogCard
import com.looker.droidify.compose.externalApps.ExternalGridCard
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.datastore.extension.sortOrderName
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.datastore.model.supportedSortOrders
import com.looker.droidify.sync.v2.model.DefaultName

@Composable
fun AppListScreen(
    viewModel: AppListViewModel,
    onAppClick: (String) -> Unit,
    onExternalAppClick: (String) -> Unit,
    onNavigateToRepos: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val apps by viewModel.displayedApps.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val newApps by viewModel.newApps.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrderFlow.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val updatesCount by viewModel.updatesCount.collectAsStateWithLifecycle()
    val installedVersionNames by viewModel.installedVersionNames.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }

    // The External tab is backed by its own ViewModel (Obtainium-style sources: GitHub/GitLab/
    // Codeberg). It shows the same 2-column card grid as the F-Droid tabs; tapping a card opens the
    // external detail screen, where the install lifecycle lives — exactly like the other tabs.
    val externalViewModel: ExternalAppsViewModel = hiltViewModel()
    val externalApps by externalViewModel.apps.collectAsStateWithLifecycle()
    val externalInstalledKeys by externalViewModel.installedKeys.collectAsStateWithLifecycle()
    // External-repo updates surface in the Updates tab too (no difference from F-Droid repos), so we
    // refresh release tags on screen entry — not only when the External tab is open.
    LaunchedEffect(Unit) {
        externalViewModel.refresh()
        externalViewModel.refreshInstalled()
    }
    // Replace stored repo names with the real installed app names (e.g. "GlassKeep"). Keyed on the
    // count so it runs once apps load and converges (label-only changes don't change the count).
    LaunchedEffect(externalApps.size) {
        externalViewModel.reconcileInstalledLabels()
    }
    // Disabled sources are hidden from the catalogue and updates, exactly like a disabled F-Droid repo.
    val enabledExternalApps = remember(externalApps) { externalApps.filter { it.enabled } }
    val externalUpdates = remember(enabledExternalApps) { enabledExternalApps.filter { it.hasUpdate } }

    Scaffold(
        snackbarHost = { SnackbarHost(externalViewModel.snackbarHostState) },
        topBar = {
            Column {
                AppListTopBar(
                    onSync = viewModel::sync,
                    searchExpanded = searchExpanded,
                    onToggleSearch = {
                        searchExpanded = !searchExpanded
                        if (!searchExpanded) viewModel.searchQuery.clearText()
                    },
                    onNavigateToRepos = onNavigateToRepos,
                    onNavigateToSettings = onNavigateToSettings,
                    currentSort = sortOrder,
                    onSortSelected = viewModel::setSortOrder,
                    title = {
                        Text("Droid-ify")
                    },
                )
                AppTabRow(
                    selectedTab = selectedTab,
                    updatesCount = updatesCount + externalUpdates.size,
                    onSelectTab = viewModel::selectTab,
                )
                if (searchExpanded) {
                    Spacer(Modifier.height(8.dp))
                    SearchBar(state = viewModel.searchQuery)
                    Spacer(Modifier.height(8.dp))
                }
                if (isSyncing) {
                    SyncBanner()
                }
            }
        },
    ) { contentPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            contentPadding = contentPadding,
        ) {
            if (selectedTab == AppTab.EXTERNAL) {
                if (enabledExternalApps.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "external-empty") {
                        ExternalTabEmpty()
                    }
                }
                // Same 2-column grid as the catalogue tabs; install happens on the detail screen.
                items(items = enabledExternalApps, key = { it.key }) { app ->
                    ExternalGridCard(
                        app = app,
                        isInstalled = app.key in externalInstalledKeys,
                        onClick = { onExternalAppClick(app.key) },
                        modifier = Modifier.animateItem(),
                    )
                }
                return@LazyVerticalGrid
            }
            if (selectedTab == AppTab.AVAILABLE && newApps.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "new-apps-showcase") {
                    NewAppsShowcase(apps = newApps, onAppClick = onAppClick)
                }
            }
            val showEmpty = when (selectedTab) {
                AppTab.INSTALLED -> apps.isEmpty()
                AppTab.UPDATES -> apps.isEmpty() && externalUpdates.isEmpty()
                else -> false
            }
            if (showEmpty) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty-tab") {
                    EmptyTabMessage(tab = selectedTab)
                }
            }
            items(
                items = apps,
                key = { it.appId },
            ) { app ->
                AppCard(
                    app = app,
                    versionLabel = appVersionLabel(app, selectedTab, installedVersionNames),
                    onClick = { onAppClick(app.packageName.name) },
                    modifier = Modifier.animateItem(),
                )
            }
            // External-repo updates, shown alongside the F-Droid ones on the Updates tab.
            if (selectedTab == AppTab.UPDATES) {
                items(
                    items = externalUpdates,
                    key = { "ext-${it.key}" },
                ) { app ->
                    ExternalGridCard(
                        app = app,
                        isInstalled = app.key in externalInstalledKeys,
                        version = "${app.installedTag} → ${app.latestTag}",
                        onClick = { onExternalAppClick(app.key) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTabRow(
    selectedTab: AppTab,
    updatesCount: Int,
    onSelectTab: (AppTab) -> Unit,
) {
    TabRow(selectedTabIndex = selectedTab.ordinal) {
        AppTab.entries.forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onSelectTab(tab) },
                text = {
                    val label = when (tab) {
                        AppTab.AVAILABLE -> stringResource(R.string.available)
                        AppTab.INSTALLED -> stringResource(R.string.installed)
                        AppTab.UPDATES -> if (updatesCount > 0) {
                            "${stringResource(R.string.updates)} ($updatesCount)"
                        } else {
                            stringResource(R.string.updates)
                        }
                        AppTab.EXTERNAL -> stringResource(R.string.tab_external)
                    }
                    Text(label)
                },
            )
        }
    }
}

/**
 * Status strip shown under the tabs while a sync runs. A filled container (not a bare line that
 * blended into the tab indicator) with a spinner + label, so it reads as "syncing", not decoration.
 */
@Composable
private fun SyncBanner() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.syncing),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyTabMessage(tab: AppTab) {
    val message = when (tab) {
        AppTab.INSTALLED -> "No installed apps found"
        AppTab.UPDATES -> "Everything is up to date"
        AppTab.AVAILABLE -> ""
        AppTab.EXTERNAL -> ""
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SearchBar(
    state: TextFieldState,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedTransition = updateTransition(isPressed)
    val pressedScale by pressedTransition.animateFloat(label = "pressedScale") { isPressed ->
        if (isPressed) 0.97F else 1F
    }
    val pressedShape by pressedTransition.animateDp(label = "pressedShape") { isPressed ->
        if (isPressed) 20.dp else 32.dp
    }
    BasicTextField(
        state = state,
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = LocalTextStyle.current,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                clip = true
                shape = RoundedCornerShape(pressedShape)
                scaleX = pressedScale
                scaleY = pressedScale
            }
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(modifier),
        decorator = {
            Box(
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val isFocused by interactionSource.collectIsFocusedAsState()
                if (state.text.isEmpty()) {
                    val colors = TextFieldDefaults.colors()
                    val color by animateColorAsState(
                        if (!isFocused) {
                            colors.focusedPlaceholderColor
                        } else {
                            colors.unfocusedPlaceholderColor
                        },
                    )
                    Text(
                        text = "Search",
                        color = color,
                    )
                }
                it()
            }
        },
    )
}

@Composable
fun CategoriesList(
    categories: List<DefaultName>,
    modifier: Modifier = Modifier,
    content: @Composable (DefaultName) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(categories) { category ->
            content(category)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppListTopBar(
    onSync: () -> Unit,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    onNavigateToRepos: () -> Unit,
    onNavigateToSettings: () -> Unit,
    currentSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    title: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    TopAppBar(
        title = title,
        actions = {
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier.size(smallContainerSize(Narrow)),
            ) {
                Icon(
                    painterResource(
                        if (searchExpanded) R.drawable.ic_tabler_x else R.drawable.ic_tabler_search,
                    ),
                    contentDescription = "Search",
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onSync,
                modifier = Modifier.size(smallContainerSize(Narrow)),
            ) {
                Icon(painterResource(R.drawable.ic_tabler_refresh), contentDescription = "Sync")
            }
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(smallContainerSize(Narrow)),
                ) {
                    Icon(painterResource(R.drawable.ic_tabler_sort), contentDescription = "Sort")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    supportedSortOrders().forEach { order ->
                        DropdownMenuItem(
                            text = { Text(context.sortOrderName(order)) },
                            onClick = {
                                onSortSelected(order)
                                expanded = false
                            },
                            trailingIcon = if (order == currentSort) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onNavigateToRepos,
                modifier = Modifier.size(smallContainerSize(Narrow)),
            ) {
                Icon(painterResource(R.drawable.ic_tabler_box), contentDescription = "Repos")
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.size(smallContainerSize(Narrow)),
            ) {
                Icon(painterResource(R.drawable.ic_tabler_settings), contentDescription = "Settings")
            }
            Spacer(Modifier.width(4.dp))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChip(
    category: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(category) },
    )
}

/** Horizontal "What's new" showcase shown at the top of the home. */
@Composable
private fun NewAppsShowcase(
    apps: List<AppMinimal>,
    onAppClick: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.whats_new),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = apps, key = { it.appId }) { app ->
                ShowcaseCard(
                    app = app,
                    onClick = { onAppClick(app.packageName.name) },
                )
            }
        }
    }
}

@Composable
private fun ShowcaseCard(
    app: AppMinimal,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var icon by remember(app.appId) { mutableStateOf(app.icon?.path) }
        if (icon != null) {
            AsyncImage(
                model = icon,
                onError = { icon = app.fallbackIcon?.path },
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                    ),
            ) {
                Image(
                    painter = painterResource(android.R.mipmap.sym_def_app_icon),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = app.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The version string to show on a card for the given [tab]: the real installed version on the
 * Installed tab, "installed → available" on the Updates tab, and the available (catalogue) version
 * elsewhere. The installed version comes from the package manager, so a fork installed over an
 * upstream package shows its actual version (e.g. "6.5.5-c") rather than the catalogue's.
 */
private fun appVersionLabel(
    app: AppMinimal,
    tab: AppTab,
    installedVersionNames: Map<String, String>,
): String {
    val installed = installedVersionNames[app.packageName.name]
    return when (tab) {
        AppTab.INSTALLED -> installed ?: app.suggestedVersion
        AppTab.UPDATES ->
            if (installed != null && installed != app.suggestedVersion) {
                "$installed → ${app.suggestedVersion}"
            } else {
                app.suggestedVersion
            }
        else -> app.suggestedVersion
    }
}

/** A catalogue app as a grid card (shared [CatalogCard] chrome): large icon, name, summary and
 *  version. Tapping opens the detail screen (install happens there). */
@Composable
private fun AppCard(
    app: AppMinimal,
    versionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CatalogCard(
        name = app.name,
        summary = app.summary,
        version = versionLabel,
        onClick = onClick,
        modifier = modifier,
    ) {
        var icon by remember(app.appId) { mutableStateOf(app.icon?.path) }
        if (icon != null) {
            AsyncImage(
                model = icon,
                onError = { icon = app.fallbackIcon?.path },
                contentDescription = null,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                    ),
            ) {
                Image(
                    painter = painterResource(android.R.mipmap.sym_def_app_icon),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ExternalTabEmpty() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.external_empty_tab),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
