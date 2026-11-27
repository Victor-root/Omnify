package com.looker.droidify.compose.categoryApps

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.appList.CatalogAppTile
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.TvInitialFocus
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.aroundScroll
import com.looker.droidify.compose.components.forFloatingBackground
import com.looker.droidify.compose.components.rememberCollapsibleHeader
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.compose.tv.TvAccentBackground
import com.looker.droidify.compose.tv.TvAccentHeader

/** Every app of one category, opened from an app page's category tags: the same tiles, in the same
 *  order, as the category's own "see all" in the Explore tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryAppsScreen(
    viewModel: CategoryAppsViewModel,
    onBackClick: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val installedPackages by viewModel.installedPackages.collectAsStateWithLifecycle()
    val isTelevision = LocalIsTelevision.current
    // TV / D-pad: drop focus from the header into the grid (the top bar won't on its own).
    val contentFocusRequester = remember { FocusRequester() }
    TvInitialFocus(contentFocusRequester)
    val header = rememberCollapsibleHeader()

    Box(modifier = Modifier.fillMaxSize()) {
        if (isTelevision) TvAccentBackground()
        Scaffold(
            modifier = header.scaffoldModifier,
            containerColor = if (isTelevision) Color.Transparent else MaterialTheme.colorScheme.background,
            topBar = {
                if (isTelevision) {
                    TvAccentHeader(title = title)
                } else {
                    TopAppBar(
                        colors = accentTopAppBarColors(),
                        expandedHeight = AccentBarHeight,
                        modifier = header.headerModifier.tvDpadDownTo(contentFocusRequester),
                        title = { Text(text = title) },
                        navigationIcon = { BackButton(onBackClick) },
                    )
                }
            },
        ) { contentPadding ->
            if (!isTelevision) {
                FloatingAppCardsBackground(Modifier.padding(contentPadding.forFloatingBackground()))
            }
            val loaded = apps
            when {
                loaded == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                loaded.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .focusRequester(contentFocusRequester)
                        .focusable(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_applications_available),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (isTelevision) 150.dp else 100.dp),
                    // On TV, inset from the screen edges (overscan safe area) so a focused tile's
                    // scaled-up highlight near an edge isn't clipped, as on a repository's page.
                    // The navigation bar's inset inside the grid, so its tiles run on under the bar.
                    contentPadding = (if (isTelevision) 12.dp + TvOverscan else 12.dp).let { edge ->
                        PaddingValues(
                            start = edge,
                            top = edge,
                            end = edge,
                            bottom = edge + contentPadding.calculateBottomPadding(),
                        )
                    },
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding.aroundScroll())
                        .focusRequester(contentFocusRequester),
                ) {
                    items(loaded, key = { it.appId }, contentType = { "app-tile" }) { app ->
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
