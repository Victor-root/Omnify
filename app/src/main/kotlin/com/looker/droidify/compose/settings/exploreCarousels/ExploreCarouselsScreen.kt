package com.looker.droidify.compose.settings.exploreCarousels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.appList.CURATED_CAROUSELS
import com.looker.droidify.compose.appList.carouselIcon
import com.looker.droidify.compose.appList.curatedCarouselTitle
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.FloatingAppCardsBackground
import com.looker.droidify.compose.components.TvInitialFocus
import com.looker.droidify.compose.components.forFloatingBackground
import com.looker.droidify.compose.components.rememberCollapsibleHeader
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.settings.components.SettingHeader
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import com.looker.droidify.compose.tv.TvAccentBackground
import com.looker.droidify.compose.tv.TvAccentHeader
import com.looker.droidify.data.model.CatalogCategory

/**
 * Lets the user arrange the Explore tab: the carousels it shows, moved up or down or taken away, then
 * Omnify's other carousels and every category, any of which can be added. Reached from Settings and the
 * home's overflow menu, and renders both phone and TV itself, like
 * [com.looker.droidify.compose.settings.hiddenApps.HiddenAppsScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreCarouselsScreen(
    viewModel: ExploreCarouselsViewModel,
    onBackClick: () -> Unit,
) {
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val isDefault by viewModel.isDefault.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val isTelevision = LocalIsTelevision.current
    val contentFocusRequester = remember { FocusRequester() }
    TvInitialFocus(contentFocusRequester)
    val header = rememberCollapsibleHeader()
    val title = stringResource(R.string.explore_carousels_title)

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
                        actions = {
                            if (!isDefault) {
                                TextButton(onClick = viewModel::reset) {
                                    Text(
                                        text = stringResource(R.string.explore_carousels_reset),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        },
                    )
                }
            },
        ) { contentPadding ->
            if (!isTelevision) {
                FloatingAppCardsBackground(Modifier.padding(contentPadding.forFloatingBackground()))
            }
            val names = remember(categories) { categories.associate { it.defaultName to it.name } }
            LazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(contentFocusRequester),
            ) {
                heading("heading-shown", R.string.explore_carousels_shown)
                if (shown.isEmpty()) {
                    item(key = "none") {
                        Text(
                            text = stringResource(R.string.explore_carousels_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateItem(),
                        )
                    }
                }
                items(shown, key = { it }) { key ->
                    CarouselRow(
                        title = carouselTitle(key, names),
                        icon = carouselIcon(key),
                        modifier = Modifier.animateItem(),
                    ) {
                        RowAction(Icons.Filled.KeyboardArrowUp, R.string.explore_carousels_move_up, enabled = key != shown.first()) {
                            viewModel.move(key, -1)
                        }
                        RowAction(Icons.Filled.KeyboardArrowDown, R.string.explore_carousels_move_down, enabled = key != shown.last()) {
                            viewModel.move(key, 1)
                        }
                        RowAction(Icons.Filled.Close, R.string.explore_carousels_remove) { viewModel.remove(key) }
                    }
                }
                // TV has no top bar to hold this.
                if (isTelevision && !isDefault) {
                    item(key = "reset") {
                        TextButton(
                            onClick = viewModel::reset,
                            modifier = Modifier.padding(horizontal = 8.dp).tvFocusScale().animateItem(),
                        ) {
                            Text(stringResource(R.string.explore_carousels_reset))
                        }
                    }
                }
                val curated = CURATED_CAROUSELS.filterNot { it in shown }
                if (curated.isNotEmpty()) heading("heading-curated", R.string.explore_carousels_omnify)
                addable(curated, names, viewModel::add)
                val others = categories.map(CatalogCategory::defaultName).filterNot { it in shown }
                if (others.isNotEmpty()) heading("heading-categories", R.string.categories)
                addable(others, names, viewModel::add)
            }
        }
    }
}

private fun LazyListScope.heading(key: String, title: Int) {
    item(key = key) {
        SettingHeader(title = stringResource(title), modifier = Modifier.animateItem())
    }
}

/** The carousels [keys] that can be added, each with its add button. Keyed like the shown ones, so a
 *  carousel added or taken away moves between the two lists rather than vanishing from one. */
private fun LazyListScope.addable(keys: List<String>, names: Map<String, String>, onAdd: (String) -> Unit) {
    items(keys, key = { it }) { key ->
        CarouselRow(
            title = carouselTitle(key, names),
            icon = carouselIcon(key),
            modifier = Modifier.animateItem(),
        ) {
            RowAction(Icons.Filled.Add, R.string.explore_carousels_add) { onAdd(key) }
        }
    }
}

/** A curated carousel's title, else its category's name in the user's language, else its default
 *  name for a category no repository declares any more. */
@Composable
private fun carouselTitle(key: String, names: Map<String, String>): String =
    curatedCarouselTitle(key) ?: names[key] ?: key

@Composable
private fun CarouselRow(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // TV only: a soft accent fill behind the row whose button has the focus (no-op on touch).
            .tvFocusFill(RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun RowAction(icon: ImageVector, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.tvFocusScale()) {
        Icon(imageVector = icon, contentDescription = stringResource(label))
    }
}
