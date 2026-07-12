package com.looker.droidify.compose.appList

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.externalApps.ExternalAppTile
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.sync.v2.model.DefaultName

/**
 * A horizontal carousel of apps (F-Droid Discover style): a section title with a round "see all"
 * arrow, and a scrolling row of rounded app icons with their name. Tapping an icon opens the app.
 */
@Composable
fun DiscoverCarousel(
    title: String,
    apps: List<AppMinimal>,
    installedPackages: Set<String>,
    onAppClick: (String) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    // Optional external (GitHub/GitLab) apps shown after the catalogue ones in the same row — used by
    // the "Made for TV" carousel so tracked external TV apps appear alongside the F-Droid ones.
    externalApps: List<ExternalApp> = emptyList(),
    externalInstalledKeys: Set<String> = emptySet(),
    onExternalAppClick: (String) -> Unit = {},
    // TV only: re-targets whichever tile the user last opened from this carousel, so returning from its
    // detail screen lands focus back on it instead of falling through to the grid/tabs. Both default to
    // "never matches" for callers (e.g. none currently) that don't opt in.
    restoreFocusId: String? = null,
    restoreRequester: FocusRequester = remember { FocusRequester() },
) {
    // Wider tiles on TV so the larger icon (and its card) fit; the compact width stays on touch. Read
    // here in the composable body, not inside the LazyRow content (which isn't a composable scope).
    val tileWidth = if (LocalIsTelevision.current) 124.dp else 80.dp
    val rowState = rememberLazyListState()
    // During the first sync the catalogue fills in and re-sorts, so new apps are prepended to a
    // carousel. A LazyRow anchors on its first visible item by key, so a prepend would keep the old
    // first item in view and leave the row scrolled to the right ("stuck in the middle"). Snap back to
    // the start whenever the head of the list changes, so each carousel shows its newest items from the
    // left. Keyed on the first item only, so a user scrolling a settled carousel isn't yanked back.
    val firstKey = apps.firstOrNull()?.appId ?: externalApps.firstOrNull()?.key
    LaunchedEffect(firstKey) { rowState.scrollToItem(0) }
    Column(verticalArrangement = spacedBy(10.dp), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                // TV only: soft fill behind the focused section header (no-op on touch).
                .tvFocusFill(RoundedCornerShape(50))
                .clickable(onClick = onSeeAll)
                .padding(start = 16.dp, end = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                // Collapsed: a "see all" forward arrow. Expanded: an up chevron to collapse again.
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.ExpandLess
                    } else {
                        Icons.AutoMirrored.Filled.ArrowForward
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = spacedBy(16.dp),
        ) {
            items(apps, key = { it.appId }, contentType = { "app-tile" }) { app ->
                CatalogAppTile(
                    app = app,
                    isInstalled = app.packageName.name in installedPackages,
                    onClick = { onAppClick(app.packageName.name) },
                    modifier = Modifier.width(tileWidth).restoreFocusTarget(
                        restoreFocusId == "app:${app.packageName.name}",
                        restoreRequester,
                    ),
                )
            }
            items(externalApps, key = { "ext-${it.key}" }, contentType = { "ext-tile" }) { app ->
                ExternalAppTile(
                    app = app,
                    isInstalled = app.key in externalInstalledKeys,
                    onClick = { onExternalAppClick(app.key) },
                    modifier = Modifier.width(tileWidth).restoreFocusTarget(
                        restoreFocusId == "ext:${app.key}",
                        restoreRequester,
                    ),
                )
            }
        }
    }
}

/** One category row in the accordion: icon + localized name + a chevron (down when collapsed, up when
 *  expanded). The [defaultName] (English key) drives the icon; tapping toggles its inline app list. */
@Composable
fun CategoryRow(
    name: String,
    defaultName: String,
    expanded: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // TV only: soft fill behind the focused category row (no-op on touch).
            .tvFocusFill(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                // The accent colour (red by default) for the icon, and the same colour at a low alpha
                // for the tile — same hue, much softer fill — instead of the heavier secondaryContainer.
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        ) {
            Icon(
                imageVector = categoryIcon(defaultName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

