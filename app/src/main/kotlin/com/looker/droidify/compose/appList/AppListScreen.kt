package com.looker.droidify.compose.appList

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.looker.droidify.R
import com.looker.droidify.compose.externalApps.ExternalAppTile
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.datastore.extension.sortOrderName
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.datastore.model.supportedSortOrders
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalAccentBarColor
import com.looker.droidify.compose.theme.LocalEdgeToEdge
import com.looker.droidify.compose.theme.LocalOnAccentBarColor
import com.looker.droidify.compose.theme.LocalStatusBarScrimAlpha
import com.looker.droidify.compose.theme.accentTopAppBarColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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
    val recentlyUpdatedApps by viewModel.recentlyUpdatedApps.collectAsStateWithLifecycle()
    val mostDownloadedApps by viewModel.mostDownloadedApps.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val favouritesOnly by viewModel.favouritesOnly.collectAsStateWithLifecycle()
    val expandedSections by viewModel.expandedSections.collectAsStateWithLifecycle()
    val expandedSectionApps by viewModel.expandedSectionApps.collectAsStateWithLifecycle()
    val openedSection by viewModel.openedSection.collectAsStateWithLifecycle()
    val openedSectionApps by viewModel.openedSectionApps.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrderFlow.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val updatesCount by viewModel.updatesCount.collectAsStateWithLifecycle()
    val installedVersionNames by viewModel.installedVersionNames.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val edgeToEdge = LocalEdgeToEdge.current
    // In edge-to-edge mode the whole header (toolbar + tabs + banner) collapses off the top on
    // scroll-down and returns on the slightest scroll-up (Material 3 "enter always"); when off it
    // stays pinned. Created unconditionally so the call site is stable across recompositions, and only
    // wired up (nested scroll + collapsing layout) below when edge-to-edge is on.
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    // The Explore tab shows the Discover home (3 curated carousels + the categories list) by default;
    // once the user is searching or has opened a category, it shows a flat list of apps instead.
    val isSearching = viewModel.searchQuery.text.isNotEmpty()
    // A curated carousel's "see all" opens it as its own page (a flat list of the whole section);
    // null means we're on the Discover home. Categories, by contrast, expand inline in the accordion.
    val sectionView = openedSection != null
    // Collapse the in-header search on system back (folds it back into the magnifier).
    BackHandler(enabled = searchExpanded) {
        searchExpanded = false
        viewModel.searchQuery.clearText()
    }
    // System back leaves a carousel "see all" page and returns to the Discover home.
    BackHandler(enabled = sectionView && !searchExpanded) {
        viewModel.closeSection()
    }
    // System back leaves the favourites filter and returns to the full Discover home.
    BackHandler(enabled = favouritesOnly && !searchExpanded && !sectionView) {
        viewModel.toggleFavouritesOnly()
    }
    // Entering or leaving a section page swaps the whole list, so start it at the top.
    LaunchedEffect(openedSection) {
        gridState.scrollToItem(0)
        scrollBehavior.state.heightOffset = 0f
    }
    // Switching tab or opening search must reveal the collapsed header again — otherwise a short
    // tab (e.g. a near-empty Installed list) could leave it stuck hidden with no room to scroll up.
    LaunchedEffect(selectedTab, searchExpanded, favouritesOnly) {
        scrollBehavior.state.heightOffset = 0f
    }

    // Status-bar icons: white while the red header sits behind the status bar, but once the header has
    // collapsed enough that the app content shows behind the status bar, match that content instead —
    // otherwise white icons would land on a white background in light mode (dark mode is fine, white on
    // black). Driven off the scroll state through a snapshotFlow so the screen doesn't recompose each
    // frame; the white icons are handed back to the red header when we leave or turn edge-to-edge off.
    val view = LocalView.current
    val statusBarPx = WindowInsets.statusBars.getTop(LocalDensity.current)
    val backgroundIsLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val statusBarScrimAlpha = LocalStatusBarScrimAlpha.current
    if (edgeToEdge && !view.isInEditMode) {
        LaunchedEffect(view, statusBarPx, backgroundIsLight, statusBarScrimAlpha) {
            val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
                ?.window ?: return@LaunchedEffect
            val controller = WindowCompat.getInsetsController(window, view)
            try {
                snapshotFlow {
                    val headerHeightPx = -scrollBehavior.state.heightOffsetLimit
                    val headerBottomPx = scrollBehavior.state.heightOffset + headerHeightPx
                    // How much of the status bar now shows app content instead of the red header (0..1).
                    if (headerHeightPx <= 0f || statusBarPx <= 0) {
                        0f
                    } else {
                        ((statusBarPx - headerBottomPx) / statusBarPx).coerceIn(0f, 1f)
                    }
                }.distinctUntilChanged().collect { contentFraction ->
                    // Fade the faint scrim in with the content, and once content dominates the bar flip
                    // the icons to match it (only matters in light mode; dark content suits white icons).
                    statusBarScrimAlpha.floatValue = contentFraction
                    controller.isAppearanceLightStatusBars = contentFraction > 0.5f && backgroundIsLight
                }
            } finally {
                statusBarScrimAlpha.floatValue = 0f
                controller.isAppearanceLightStatusBars = false
            }
        }
    }

    // Cold/warm start: the Discover carousels are fed by independent flows that emit in a race, and
    // LazyGrid anchors on its first visible item — so a carousel that finishes loading *above* the
    // current anchor (e.g. "New apps" arriving after "Most downloaded") shoves the top off-screen and
    // the Explore tab opens already scrolled down. Pin it to the top while the sections stream in,
    // and stop the moment the user actually scrolls (a real drag/fling sets isScrollInProgress; the
    // programmatic scrollToItem below does not, so this never fights the user).
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.isScrollInProgress }
            .collect { scrolling -> if (scrolling) userScrolled = true }
    }
    LaunchedEffect(
        newApps.size,
        recentlyUpdatedApps.size,
        mostDownloadedApps.size,
        categories.size,
    ) {
        if (selectedTab == AppTab.AVAILABLE && !isSearching && !sectionView && !userScrolled) {
            gridState.scrollToItem(0)
        }
    }

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

    // First launch: the catalogue is still empty and a sync is running. Show a full-screen fetching
    // state (like F-Droid) instead of an empty grid + thin banner. `newApps` is empty exactly when the
    // catalogue has no apps, so it doubles as the "nothing loaded yet" signal. The External tab has
    // its own content, so it's excluded.
    val catalogLoading = isSyncing && newApps.isEmpty() && selectedTab != AppTab.EXTERNAL

    Scaffold(
        // Edge-to-edge: let the header collapse as the grid scrolls. Pinned otherwise.
        modifier = if (edgeToEdge) {
            Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        } else {
            Modifier
        },
        snackbarHost = { SnackbarHost(externalViewModel.snackbarHostState) },
        topBar = {
            Column(
                modifier = if (edgeToEdge) Modifier.collapsingHeader(scrollBehavior) else Modifier,
            ) {
                // A carousel "see all" page takes over the whole header: a back arrow + the section
                // title, with no tabs, so it reads as its own screen.
                if (sectionView) {
                    SectionTopBar(
                        title = sectionTitle(openedSection),
                        onBack = { viewModel.closeSection() },
                    )
                } else {
                    AppListTopBar(
                        onSync = viewModel::sync,
                        searchExpanded = searchExpanded,
                        onToggleSearch = {
                            searchExpanded = !searchExpanded
                            if (!searchExpanded) viewModel.searchQuery.clearText()
                        },
                        searchState = viewModel.searchQuery,
                        onNavigateToRepos = onNavigateToRepos,
                        onNavigateToSettings = onNavigateToSettings,
                        currentSort = sortOrder,
                        onSortSelected = viewModel::setSortOrder,
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_launcher_monochrome),
                                    contentDescription = null,
                                    tint = LocalOnAccentBarColor.current,
                                    modifier = Modifier.size(28.dp),
                                )
                                Text("Droid-ify")
                            }
                        },
                        favouritesOnly = favouritesOnly,
                        onToggleFavourites = viewModel::toggleFavouritesOnly,
                    )
                    AppTabRow(
                        selectedTab = selectedTab,
                        updatesCount = updatesCount + externalUpdates.size,
                        onSelectTab = viewModel::selectTab,
                    )
                    // While the full-screen fetching state is up, the thin banner is redundant.
                    if (isSyncing && !catalogLoading) {
                        SyncBanner()
                    }
                }
            }
        },
    ) { contentPadding ->
        if (catalogLoading) {
            RepoFetchingState(modifier = Modifier.padding(contentPadding))
            return@Scaffold
        }
        LazyVerticalGrid(
            // A tile grid (icon + name), the same density as the Discover carousels, shared by every
            // tab so the apps look identical everywhere.
            columns = GridCells.Adaptive(minSize = 100.dp),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Installed package names, used to badge every tile that's already installed.
            val installedPackages = installedVersionNames.keys
            if (selectedTab == AppTab.EXTERNAL) {
                if (enabledExternalApps.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "external-empty") {
                        ExternalTabEmpty()
                    }
                }
                // Install happens on the detail screen; the grid mirrors the catalogue tabs exactly.
                items(items = enabledExternalApps, key = { it.key }) { app ->
                    ExternalAppTile(
                        app = app,
                        isInstalled = app.key in externalInstalledKeys,
                        onClick = { onExternalAppClick(app.key) },
                    )
                }
                return@LazyVerticalGrid
            }
            // Discover home (Explore tab, not searching, not on a "see all" page): the 3 curated
            // carousels then the categories accordion. A carousel arrow opens that section as its own
            // page; a category chevron expands its apps inline. When searching or on a section page,
            // this is skipped and the apps render as a flat list below.
            if (selectedTab == AppTab.AVAILABLE && !isSearching && !sectionView && !favouritesOnly) {
                // Breathing room below the header: the first carousel's round "see all" button
                // otherwise sits glued to the tabs.
                item(span = { GridItemSpan(maxLineSpan) }, key = "discover-top-gap") {
                    Spacer(Modifier.height(12.dp))
                }
                if (newApps.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "carousel-new") {
                        DiscoverCarousel(
                            title = stringResource(R.string.discover_new_apps),
                            apps = newApps,
                            installedPackages = installedPackages,
                            onAppClick = onAppClick,
                            onSeeAll = { viewModel.openSection(SECTION_WHATS_NEW) },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                if (recentlyUpdatedApps.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "carousel-updated") {
                        DiscoverCarousel(
                            title = stringResource(R.string.discover_recently_updated),
                            apps = recentlyUpdatedApps,
                            installedPackages = installedPackages,
                            onAppClick = onAppClick,
                            onSeeAll = { viewModel.openSection(SECTION_RECENTLY_UPDATED) },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                // "Most downloaded" — F-Droid v2's third curated carousel. Hidden until the download-
                // stats worker has fetched data, so it simply appears once stats land.
                if (mostDownloadedApps.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "carousel-downloaded") {
                        DiscoverCarousel(
                            title = stringResource(R.string.discover_most_downloaded),
                            apps = mostDownloadedApps,
                            installedPackages = installedPackages,
                            onAppClick = onAppClick,
                            onSeeAll = { viewModel.openSection(SECTION_MOST_DOWNLOADED) },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                // The categories accordion. The chevron expands a category's apps inline; tapping
                // again collapses it.
                if (categories.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "categories-title") {
                        CategoriesTitle()
                    }
                    categories.forEach { category ->
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = "category-${category.defaultName}",
                        ) {
                            CategoryRow(
                                name = category.name,
                                defaultName = category.defaultName,
                                expanded = category.defaultName in expandedSections,
                                onClick = { viewModel.toggleSection(category.defaultName) },
                            )
                        }
                        expandedAppItems(
                            category.defaultName,
                            expandedSections,
                            expandedSectionApps,
                            installedPackages,
                            onAppClick,
                        )
                    }
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
            // The Explore tab ends at the categories accordion — no flat grid under the Discover home.
            // A flat list of app tiles appears when searching (search results) or on a carousel "see
            // all" page (the whole section). The Installed/Updates tabs use the same tiles.
            if (selectedTab == AppTab.AVAILABLE) {
                // The favourites filter (toggled from the overflow menu) takes over the Explore tab:
                // a "Favourites" heading then the favourite apps as the same tiles, or a hint if empty.
                if (favouritesOnly) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "favourites-title") {
                        Text(
                            text = stringResource(R.string.favourites),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (apps.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "favourites-empty") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.no_favourites),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                val flatList = when {
                    favouritesOnly -> apps
                    sectionView -> openedSectionApps
                    isSearching -> apps
                    else -> emptyList()
                }
                items(
                    items = flatList,
                    key = { it.appId },
                ) { app ->
                    CatalogAppTile(
                        app = app,
                        isInstalled = app.packageName.name in installedPackages,
                        onClick = { onAppClick(app.packageName.name) },
                    )
                }
            } else {
                items(
                    items = apps,
                    key = { it.appId },
                ) { app ->
                    CatalogAppTile(
                        app = app,
                        isInstalled = app.packageName.name in installedPackages,
                        onClick = { onAppClick(app.packageName.name) },
                    )
                }
            }
            // External-repo updates, shown alongside the F-Droid ones on the Updates tab.
            if (selectedTab == AppTab.UPDATES) {
                items(
                    items = externalUpdates,
                    key = { "ext-${it.key}" },
                ) { app ->
                    ExternalAppTile(
                        app = app,
                        isInstalled = app.key in externalInstalledKeys,
                        onClick = { onExternalAppClick(app.key) },
                    )
                }
            }
        }
    }
}

/**
 * Collapses the element this modifies (the whole header) off the top of the screen as the body
 * scrolls, driven by [scrollBehavior]'s enter-always logic. It reports a height that shrinks with the
 * scroll offset — so the Scaffold slides the body up into the freed space — and translates the header
 * by the same amount, so the header leaves and the content slides behind the status bar together.
 */
@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.collapsingHeader(scrollBehavior: TopAppBarScrollBehavior): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        // The header can collapse by its full height; tell the scroll behaviour so it clamps there.
        scrollBehavior.state.heightOffsetLimit = -placeable.height.toFloat()
        val offsetY = scrollBehavior.state.heightOffset.roundToInt() // 0 (shown) .. -height (hidden)
        val measuredHeight = (placeable.height + offsetY).coerceAtLeast(0)
        layout(placeable.width, measuredHeight) {
            placeable.place(0, offsetY)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTabRow(
    selectedTab: AppTab,
    updatesCount: Int,
    onSelectTab: (AppTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        containerColor = LocalAccentBarColor.current,
        contentColor = LocalOnAccentBarColor.current,
    ) {
        AppTab.entries.forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onSelectTab(tab) },
                text = {
                    val label = when (tab) {
                        AppTab.AVAILABLE -> stringResource(R.string.available)
                        AppTab.INSTALLED -> stringResource(R.string.installed)
                        // Short label ("MàJ") so the count fits on one line — a wrapping label would
                        // otherwise make the whole tab bar taller.
                        AppTab.UPDATES -> if (updatesCount > 0) {
                            "${stringResource(R.string.tab_updates_short)} ($updatesCount)"
                        } else {
                            stringResource(R.string.tab_updates_short)
                        }
                        AppTab.EXTERNAL -> stringResource(R.string.tab_external)
                    }
                    // Never wrap: one line keeps every tab — and the bar — the same height.
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

/**
 * Full-screen state shown on first launch while the catalogue is being fetched — mirrors F-Droid: a
 * centred label above the Material 3 expressive wavy loading indicator (themed with the app's accent),
 * instead of an empty grid.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RepoFetchingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.fetching_repositories),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        CircularWavyProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
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
        AppTab.INSTALLED -> stringResource(R.string.no_installed_apps)
        AppTab.UPDATES -> stringResource(R.string.everything_up_to_date)
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
                        text = stringResource(R.string.search),
                        color = color,
                    )
                }
                it()
            }
        },
    )
}

/**
 * The header turned into a search field: a back arrow (folds it away) + a full-width input that
 * auto-focuses so the keyboard opens immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    state: TextFieldState,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        colors = accentTopAppBarColors(),
        expandedHeight = AccentBarHeight,
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cancel),
                )
            }
        },
        title = {
            BasicTextField(
                state = state,
                lineLimits = TextFieldLineLimits.SingleLine,
                // On the accent-coloured bar the text/cursor must contrast with it, not use on-surface.
                textStyle = LocalTextStyle.current.copy(color = LocalOnAccentBarColor.current),
                cursorBrush = SolidColor(LocalOnAccentBarColor.current),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorator = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search),
                                color = LocalOnAccentBarColor.current.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppListTopBar(
    onSync: () -> Unit,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    searchState: TextFieldState,
    onNavigateToRepos: () -> Unit,
    onNavigateToSettings: () -> Unit,
    currentSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    favouritesOnly: Boolean,
    onToggleFavourites: () -> Unit,
    title: @Composable () -> Unit,
) {
    // Tapping the magnifier unfolds the search field into the whole header. Fade between the two so it
    // doesn't pop in abruptly.
    AnimatedContent(targetState = searchExpanded, label = "search-bar") { isSearch ->
        if (isSearch) {
            SearchTopBar(state = searchState, onClose = onToggleSearch)
        } else {
            AppListMainTopBar(
                onSync = onSync,
                onToggleSearch = onToggleSearch,
                onNavigateToRepos = onNavigateToRepos,
                onNavigateToSettings = onNavigateToSettings,
                currentSort = currentSort,
                onSortSelected = onSortSelected,
                favouritesOnly = favouritesOnly,
                onToggleFavourites = onToggleFavourites,
                title = title,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppListMainTopBar(
    onSync: () -> Unit,
    onToggleSearch: () -> Unit,
    onNavigateToRepos: () -> Unit,
    onNavigateToSettings: () -> Unit,
    currentSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    favouritesOnly: Boolean,
    onToggleFavourites: () -> Unit,
    title: @Composable () -> Unit,
) {
    var sortExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    TopAppBar(
        colors = accentTopAppBarColors(),
        expandedHeight = AccentBarHeight,
        title = title,
        actions = {
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier.size(smallContainerSize(Narrow)),
            ) {
                Icon(
                    painterResource(R.drawable.ic_tabler_search),
                    contentDescription = stringResource(R.string.search),
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onSync,
                modifier = Modifier.size(smallContainerSize(Narrow)),
            ) {
                Icon(
                    painterResource(R.drawable.ic_tabler_refresh),
                    contentDescription = stringResource(R.string.sync),
                )
            }
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(
                    onClick = { sortExpanded = true },
                    modifier = Modifier.size(smallContainerSize(Narrow)),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.sort),
                    )
                }
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                ) {
                    supportedSortOrders().forEach { order ->
                        DropdownMenuItem(
                            text = { Text(context.sortOrderName(order)) },
                            onClick = {
                                onSortSelected(order)
                                sortExpanded = false
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
            // Overflow: the less-used destinations (favourites filter, repositories, settings) live
            // here so the header stays uncluttered.
            Box {
                IconButton(
                    onClick = { overflowExpanded = true },
                    modifier = Modifier.size(smallContainerSize(Narrow)),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.more_options),
                    )
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.favourites)) },
                        onClick = {
                            onToggleFavourites()
                            overflowExpanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Favorite, contentDescription = null)
                        },
                        trailingIcon = if (favouritesOnly) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.repositories)) },
                        onClick = {
                            onNavigateToRepos()
                            overflowExpanded = false
                        },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.ic_tabler_box), contentDescription = null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings)) },
                        onClick = {
                            onNavigateToSettings()
                            overflowExpanded = false
                        },
                        leadingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_tabler_settings),
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
        },
    )
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

/** Emits the inline-expanded app tiles for a Discover category, lazily, when it's expanded — the same
 *  tiles as everywhere else, flowing into the grid below the category header. */
private fun LazyGridScope.expandedAppItems(
    key: String,
    expandedSections: Set<String>,
    expandedSectionApps: Map<String, List<AppMinimal>>,
    installedPackages: Set<String>,
    onAppClick: (String) -> Unit,
) {
    if (key !in expandedSections) return
    items(
        items = expandedSectionApps[key].orEmpty(),
        key = { "exp-$key-${it.appId}" },
    ) { app ->
        CatalogAppTile(
            app = app,
            isInstalled = app.packageName.name in installedPackages,
            onClick = { onAppClick(app.packageName.name) },
        )
    }
}

/** "Categories" heading above the categories list. */
@Composable
private fun CategoriesTitle() {
    Text(
        text = stringResource(R.string.categories),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The localized title for a curated-carousel "see all" page. */
@Composable
private fun sectionTitle(key: String?): String = when (key) {
    SECTION_WHATS_NEW -> stringResource(R.string.discover_new_apps)
    SECTION_RECENTLY_UPDATED -> stringResource(R.string.discover_recently_updated)
    SECTION_MOST_DOWNLOADED -> stringResource(R.string.discover_most_downloaded)
    else -> ""
}

/** Header for a carousel "see all" page: a back arrow that returns to the Discover home + the title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        colors = accentTopAppBarColors(),
        expandedHeight = AccentBarHeight,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cancel),
                )
            }
        },
        title = { Text(title) },
    )
}

