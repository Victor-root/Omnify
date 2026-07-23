package com.looker.droidify.compose.tv

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.appList.AppListViewModel
import com.looker.droidify.compose.appList.AppMinimalIcon
import com.looker.droidify.compose.appList.AppTab
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvBringIntoViewOnFocus
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.externalApps.ExternalAppIcon
import com.looker.droidify.compose.externalApps.ExternalAppsViewModel
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.external.ExternalApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource

/** Which content the couch/remote home is showing. Driven entirely by the left rail; unlike the phone
 *  build these aren't swipeable tabs but discrete destinations, one D-pad move apart. */
private enum class TvSection { EXPLORE, INSTALLED, UPDATES, EXTERNAL, SEARCH }

/**
 * The Android TV home — a completely separate presentation from the phone [com.looker.droidify.compose
 * .appList.AppListScreen], built for a 10-foot screen and a D-pad: a slim left navigation rail and a
 * content area of Google-Play-TV-style carousels (Explore) and grids (Installed / Updates / External /
 * Search). Reuses the existing [AppListViewModel] / [ExternalAppsViewModel] verbatim — this is pure
 * presentation, no new business logic — and the shared icon loaders so apps look identical to the phone
 * build. Never composed off TV (the navigation layer picks the phone screen there), so it carries no
 * `isTelevision` guards of its own.
 */
@Composable
fun TvHomeScreen(
    viewModel: AppListViewModel,
    onAppClick: (String) -> Unit,
    onExternalAppClick: (String) -> Unit,
    onNavigateToRepos: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    val externalViewModel: ExternalAppsViewModel = hiltViewModel()

    val newApps by viewModel.newApps.collectAsStateWithLifecycle()
    val recentlyUpdatedApps by viewModel.recentlyUpdatedApps.collectAsStateWithLifecycle()
    val mostDownloadedApps by viewModel.mostDownloadedApps.collectAsStateWithLifecycle()
    val tvApps by viewModel.tvApps.collectAsStateWithLifecycle()
    val shizukuApps by viewModel.shizukuApps.collectAsStateWithLifecycle()
    val rootApps by viewModel.rootApps.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val updatableApps by viewModel.updatableApps.collectAsStateWithLifecycle()
    val updatesCount by viewModel.updatesCount.collectAsStateWithLifecycle()
    val isUpdatingAll by viewModel.isUpdatingAll.collectAsStateWithLifecycle()
    val displayedApps by viewModel.displayedApps.collectAsStateWithLifecycle()
    val installedVersionNames by viewModel.installedVersionNames.collectAsStateWithLifecycle()

    val externalApps by externalViewModel.apps.collectAsStateWithLifecycle()
    val externalInstalledKeys by externalViewModel.installedKeys.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        externalViewModel.refresh()
        externalViewModel.refreshInstalled()
    }

    val installedPackages = remember(installedVersionNames) { installedVersionNames.keys }

    var section by remember { mutableStateOf(TvSection.EXPLORE) }
    val contentFocus = remember { FocusRequester() }

    // Entering a section hands focus to its content (the rail keeps the section highlighted), so the
    // remote lands on a card instead of nowhere.
    LaunchedEffect(section) {
        runCatching { contentFocus.requestFocus() }
    }

    // Back handling on the TV home. Whenever focus sits in the content area (a card, a grid, the search
    // field), Back returns it to the sidebar rail instead of leaving the app — so the remote never falls
    // out of the app in one press. Only once focus is already on the rail does Back start the
    // double-press-to-exit flow: the first press toasts a hint, a second within the window closes the app.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val railFocus = remember { FocusRequester() }
    var railFocused by remember { mutableStateOf(false) }
    var armedToExit by remember { mutableStateOf(false) }
    val exitHint = stringResource(R.string.tv_press_back_again)
    BackHandler {
        if (!railFocused) {
            armedToExit = false
            runCatching { railFocus.requestFocus() }
        } else if (armedToExit) {
            (context as? Activity)?.finish()
        } else {
            armedToExit = true
            Toast.makeText(context, exitHint, Toast.LENGTH_SHORT).show()
            scope.launch {
                delay(2500)
                armedToExit = false
            }
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvNavRail(
            section = section,
            updatesCount = updatesCount,
            modifier = Modifier
                .focusRequester(railFocus)
                .onFocusChanged { railFocused = it.hasFocus },
            onSelect = { section = it },
            onSearch = {
                viewModel.selectTab(AppTab.AVAILABLE)
                section = TvSection.SEARCH
            },
            onRepos = onNavigateToRepos,
            onSettings = onNavigateToSettings,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(contentFocus)
                .focusGroup(),
        ) {
            when (section) {
                TvSection.EXPLORE -> TvExplore(
                    newApps = newApps,
                    recentlyUpdatedApps = recentlyUpdatedApps,
                    mostDownloadedApps = mostDownloadedApps,
                    tvApps = tvApps,
                    shizukuApps = shizukuApps,
                    rootApps = rootApps,
                    installedPackages = installedPackages,
                    onAppClick = onAppClick,
                )

                TvSection.INSTALLED -> TvAppGrid(
                    title = stringResource(R.string.installed),
                    apps = installedApps,
                    installedPackages = installedPackages,
                    onAppClick = onAppClick,
                )

                TvSection.UPDATES -> TvUpdates(
                    apps = updatableApps,
                    installedPackages = installedPackages,
                    isUpdatingAll = isUpdatingAll,
                    onUpdateAll = viewModel::updateAll,
                    onAppClick = onAppClick,
                )

                TvSection.EXTERNAL -> TvExternalGrid(
                    apps = externalApps,
                    installedKeys = externalInstalledKeys,
                    onAppClick = onExternalAppClick,
                )

                TvSection.SEARCH -> TvSearch(
                    query = viewModel.searchQuery,
                    results = displayedApps,
                    installedPackages = installedPackages,
                    onAppClick = onAppClick,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Navigation rail
// ---------------------------------------------------------------------------------------------------

private val RailWidth = 180.dp

@Composable
private fun TvNavRail(
    section: TvSection,
    updatesCount: Int,
    modifier: Modifier = Modifier,
    onSelect: (TvSection) -> Unit,
    onSearch: () -> Unit,
    onRepos: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(RailWidth)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(modifier)
            .focusGroup()
            .padding(horizontal = 12.dp, vertical = TvOverscan),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(4.dp),
    ) {
        // The Omnify brand mark + wordmark anchor the rail. The launcher art keeps its own colours, so
        // it's an untinted Image, not a tinted Icon. The launcher PNG carries a chunk of transparent
        // safe-zone padding at the bottom, so the wordmark is pulled up (negative spacing) to sit snug
        // under the visible mark instead of a frame-height away.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = spacedBy((-42).dp),
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(156.dp),
            )
            Text(
                text = stringResource(R.string.application_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // A single, consistent Tabler icon set (tabler.io) for the whole rail.
        TvRailButton(painterResource(R.drawable.ic_tv_search), stringResource(R.string.search), false) { onSearch() }
        TvRailButton(painterResource(R.drawable.ic_tv_explore), stringResource(R.string.available), section == TvSection.EXPLORE) { onSelect(TvSection.EXPLORE) }
        TvRailButton(painterResource(R.drawable.ic_tv_installed), stringResource(R.string.installed), section == TvSection.INSTALLED) { onSelect(TvSection.INSTALLED) }
        TvRailButton(
            icon = painterResource(R.drawable.ic_tv_updates),
            label = stringResource(R.string.updates),
            selected = section == TvSection.UPDATES,
            badge = updatesCount.takeIf { it > 0 },
        ) { onSelect(TvSection.UPDATES) }
        // External sources are GitHub/GitLab repos: the orange Git mark from the Omnify logo (rendered
        // untinted so it keeps its fixed brand orange) — a small visual rhyme with the app's own icon.
        TvRailButton(
            painterResource(R.drawable.ic_tv_external_git),
            stringResource(R.string.tab_external),
            selected = section == TvSection.EXTERNAL,
            preserveIconColor = true,
        ) { onSelect(TvSection.EXTERNAL) }
        Spacer(Modifier.weight(1f))
        // Repositories uses the same box glyph as the phone's Repositories entry.
        TvRailButton(painterResource(R.drawable.ic_tabler_box), stringResource(R.string.repositories), false) { onRepos() }
        TvRailButton(painterResource(R.drawable.ic_tv_settings), stringResource(R.string.settings), false) { onSettings() }
    }
}

@Composable
private fun TvRailButton(
    icon: Painter,
    label: String,
    selected: Boolean,
    badge: Int? = null,
    // When true the glyph keeps its own colours (used for the orange Git brand mark) instead of being
    // tinted by the focus/selected state like every other rail icon.
    preserveIconColor: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFill(RoundedCornerShape(16.dp))
            .tvBringIntoViewOnFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (preserveIconColor) {
                androidx.compose.foundation.Image(
                    painter = icon,
                    contentDescription = label,
                    modifier = Modifier.size(26.dp),
                )
            } else {
                Icon(painter = icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
            }
            if (badge != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        text = if (badge > 9) "9+" else badge.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------------------------------------------------------------------------------------------------
// Explore: carousels
// ---------------------------------------------------------------------------------------------------

@Composable
private fun TvExplore(
    newApps: List<AppMinimal>,
    recentlyUpdatedApps: List<AppMinimal>,
    mostDownloadedApps: List<AppMinimal>,
    tvApps: List<AppMinimal>,
    shizukuApps: List<AppMinimal>,
    rootApps: List<AppMinimal>,
    installedPackages: Set<String>,
    onAppClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = TvOverscan + 12.dp, bottom = TvOverscan),
        verticalArrangement = spacedBy(28.dp),
    ) {
        if (tvApps.isNotEmpty()) {
            TvCarousel(stringResource(R.string.discover_tv_apps), tvApps, installedPackages, onAppClick)
        }
        if (newApps.isNotEmpty()) {
            TvCarousel(stringResource(R.string.discover_new_apps), newApps, installedPackages, onAppClick)
        }
        if (recentlyUpdatedApps.isNotEmpty()) {
            TvCarousel(stringResource(R.string.discover_recently_updated), recentlyUpdatedApps, installedPackages, onAppClick)
        }
        if (mostDownloadedApps.isNotEmpty()) {
            TvCarousel(stringResource(R.string.discover_most_downloaded), mostDownloadedApps, installedPackages, onAppClick)
        }
        if (shizukuApps.isNotEmpty()) {
            TvCarousel(stringResource(R.string.discover_shizuku), shizukuApps, installedPackages, onAppClick)
        }
        if (rootApps.isNotEmpty()) {
            TvCarousel(stringResource(R.string.discover_root), rootApps, installedPackages, onAppClick)
        }
    }
}

@Composable
private fun TvCarousel(
    title: String,
    apps: List<AppMinimal>,
    installedPackages: Set<String>,
    onAppClick: (String) -> Unit,
) {
    val rowState = rememberLazyListState()
    val firstKey = apps.firstOrNull()?.appId
    LaunchedEffect(firstKey) { rowState.scrollToItem(0) }
    Column(verticalArrangement = spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier.padding(start = TvOverscan + 8.dp),
        )
        LazyRow(
            state = rowState,
            // A little vertical padding so a focused card's scale-up isn't clipped at the row edges.
            contentPadding = PaddingValues(horizontal = TvOverscan + 8.dp, vertical = 6.dp),
            horizontalArrangement = spacedBy(18.dp),
        ) {
            items(apps, key = { it.appId }, contentType = { "tv-app" }) { app ->
                TvAppCard(
                    name = app.name,
                    onClick = { onAppClick(app.packageName.name) },
                ) {
                    AppMinimalIcon(
                        app = app,
                        isInstalled = app.packageName.name in installedPackages,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Grids (Installed / Updates / External / Search results)
// ---------------------------------------------------------------------------------------------------

private val CardWidth = 132.dp
private val TileSize = 116.dp

@Composable
private fun TvAppGrid(
    title: String,
    apps: List<AppMinimal>,
    installedPackages: Set<String>,
    onAppClick: (String) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = TvOverscan + 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = TvOverscan + 8.dp, end = TvOverscan, bottom = 12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            header?.invoke()
        }
        if (apps.isEmpty()) {
            TvEmpty(stringResource(R.string.no_applications_available))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CardWidth + 18.dp),
                contentPadding = PaddingValues(start = TvOverscan + 8.dp, end = TvOverscan, top = 24.dp, bottom = TvOverscan),
                horizontalArrangement = spacedBy(18.dp),
                verticalArrangement = spacedBy(18.dp),
            ) {
                gridItems(apps, key = { it.appId }, contentType = { "tv-app" }) { app ->
                    TvAppCard(
                        name = app.name,
                        onClick = { onAppClick(app.packageName.name) },
                    ) {
                        AppMinimalIcon(
                            app = app,
                            isInstalled = app.packageName.name in installedPackages,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvUpdates(
    apps: List<AppMinimal>,
    installedPackages: Set<String>,
    isUpdatingAll: Boolean,
    onUpdateAll: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    TvAppGrid(
        title = stringResource(R.string.updates),
        apps = apps,
        installedPackages = installedPackages,
        onAppClick = onAppClick,
        header = {
            if (apps.isNotEmpty()) {
                Button(
                    onClick = onUpdateAll,
                    enabled = !isUpdatingAll,
                    modifier = Modifier.tvBringIntoViewOnFocus(),
                ) {
                    Text(stringResource(R.string.update_all))
                }
            }
        },
    )
}

@Composable
private fun TvExternalGrid(
    apps: List<ExternalApp>,
    installedKeys: Set<String>,
    onAppClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = TvOverscan + 12.dp)) {
        Text(
            text = stringResource(R.string.tab_external),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = TvOverscan + 8.dp, bottom = 12.dp),
        )
        if (apps.isEmpty()) {
            TvEmpty(stringResource(R.string.no_applications_available))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CardWidth + 18.dp),
                contentPadding = PaddingValues(start = TvOverscan + 8.dp, end = TvOverscan, top = 24.dp, bottom = TvOverscan),
                horizontalArrangement = spacedBy(18.dp),
                verticalArrangement = spacedBy(18.dp),
            ) {
                gridItems(apps, key = { it.key }, contentType = { "tv-ext" }) { app ->
                    TvAppCard(
                        name = app.label,
                        onClick = { onAppClick(app.key) },
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            ExternalAppIcon(app = app, isInstalled = app.key in installedKeys, size = TileSize - 20.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSearch(
    query: TextFieldState,
    results: List<AppMinimal>,
    installedPackages: Set<String>,
    onAppClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = TvOverscan)) {
        TvSearchField(query)
        Spacer(Modifier.height(16.dp))
        if (query.text.isEmpty()) {
            TvEmpty(stringResource(R.string.search))
        } else if (results.isEmpty()) {
            TvEmpty(stringResource(R.string.no_applications_available))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(CardWidth + 18.dp),
                contentPadding = PaddingValues(start = TvOverscan + 8.dp, end = TvOverscan, top = 24.dp, bottom = TvOverscan),
                horizontalArrangement = spacedBy(18.dp),
                verticalArrangement = spacedBy(18.dp),
            ) {
                gridItems(results, key = { it.appId }, contentType = { "tv-app" }) { app ->
                    TvAppCard(
                        name = app.name,
                        onClick = { onAppClick(app.packageName.name) },
                    ) {
                        AppMinimalIcon(
                            app = app,
                            isInstalled = app.packageName.name in installedPackages,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSearchField(query: TextFieldState) {
    var focused by remember { mutableStateOf(false) }
    val border = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = spacedBy(12.dp),
        modifier = Modifier
            .padding(start = TvOverscan + 8.dp, end = TvOverscan)
            .fillMaxWidth(0.6f)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(2.dp, border, RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(modifier = Modifier.weight(1f)) {
            if (query.text.isEmpty()) {
                Text(
                    text = stringResource(R.string.search),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                state = query,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvBringIntoViewOnFocus()
                    .onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Card + shared bits
// ---------------------------------------------------------------------------------------------------

/**
 * A Google-Play-TV-style app card: a rounded icon tile with the name beneath. On D-pad focus it lifts
 * and scales, the tile gains an accent ring, the name brightens, and it scrolls itself into view. The
 * whole card is the single focus target so the remote lands once per app, not once per element.
 */
@Composable
internal fun TvAppCard(
    name: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "tvCardScale")
    val shape = MaterialTheme.shapes.large
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = spacedBy(8.dp),
        modifier = Modifier
            .width(CardWidth)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .zIndex(if (focused) 1f else 0f)
            .tvBringIntoViewOnFocus()
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(TileSize)
                .clip(shape)
                .border(
                    width = if (focused) 3.dp else 0.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    shape = shape,
                ),
        ) {
            icon()
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TvEmpty(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
