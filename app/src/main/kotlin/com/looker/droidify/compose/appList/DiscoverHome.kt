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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.looker.droidify.R
import com.looker.droidify.data.model.AppMinimal
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
) {
    Column(verticalArrangement = spacedBy(10.dp), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
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
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = spacedBy(16.dp),
        ) {
            items(apps, key = { it.appId }) { app ->
                DiscoverAppItem(
                    app = app,
                    isInstalled = app.packageName.name in installedPackages,
                    onClick = { onAppClick(app.packageName.name) },
                )
            }
        }
    }
}

@Composable
private fun DiscoverAppItem(
    app: AppMinimal,
    isInstalled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = spacedBy(8.dp),
        modifier = Modifier
            .width(80.dp)
            .clickable(onClick = onClick),
    ) {
        Box {
            var icon by remember(app.appId) { mutableStateOf(app.icon?.path) }
            if (icon != null) {
                AsyncImage(
                    model = icon,
                    onError = { icon = app.fallbackIcon?.path },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(MaterialTheme.shapes.large),
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Image(
                        painter = painterResource(android.R.mipmap.sym_def_app_icon),
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            if (isInstalled) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = app.name,
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
        )
    }
}

/**
 * The categories block on the Discover home (F-Droid style): a title and an outlined card listing the
 * categories — each a row with an icon, its name and a chevron. Tapping a row filters the catalogue.
 */
@Composable
fun DiscoverCategories(
    categories: List<DefaultName>,
    onCategoryClick: (DefaultName) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = spacedBy(10.dp), modifier = modifier) {
        Text(
            text = stringResource(R.string.categories),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedCard(modifier = Modifier.padding(horizontal = 16.dp)) {
            categories.forEachIndexed { index, category ->
                CategoryRow(category = category, onClick = { onCategoryClick(category) })
                if (index < categories.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = category,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shown above the grid while one or more categories are active — removable chips so the user can
 * clear the filter and return to the full Discover home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverSelectedCategories(
    selected: Set<DefaultName>,
    onToggle: (DefaultName) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(selected.toList(), key = { it }) { category ->
            FilterChip(
                selected = true,
                onClick = { onToggle(category) },
                label = { Text(category) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

