package com.looker.droidify.compose.appList

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.looker.droidify.R

/**
 * The carousels Omnify curates for the Explore tab, in the order it shows them until the user arranges
 * their own (see [com.looker.droidify.datastore.Settings.exploreCarousels]). Any other key in that
 * arrangement is a category's default name, shown as a carousel of that category's apps.
 */
val CURATED_CAROUSELS = listOf(
    SECTION_FAVOURITES,
    SECTION_WHATS_NEW,
    SECTION_RECENTLY_UPDATED,
    SECTION_MOST_DOWNLOADED,
    SECTION_SHIZUKU,
    SECTION_ROOT,
)

/** A curated carousel's title, or null for a category, which goes by its own name. */
@Composable
fun curatedCarouselTitle(key: String): String? = when (key) {
    SECTION_FAVOURITES -> stringResource(R.string.favourites)
    SECTION_WHATS_NEW -> stringResource(R.string.discover_new_apps)
    SECTION_RECENTLY_UPDATED -> stringResource(R.string.discover_recently_updated)
    SECTION_MOST_DOWNLOADED -> stringResource(R.string.discover_most_downloaded)
    SECTION_TV -> stringResource(R.string.discover_tv_apps)
    SECTION_SHIZUKU -> stringResource(R.string.discover_shizuku)
    SECTION_ROOT -> stringResource(R.string.discover_root)
    else -> null
}

/** What a carousel is drawn with where it is listed: a curated one's own, else its category's. */
fun carouselIcon(key: String): ImageVector = when (key) {
    SECTION_FAVOURITES -> Icons.Filled.Favorite
    SECTION_WHATS_NEW -> Icons.Filled.NewReleases
    SECTION_RECENTLY_UPDATED -> Icons.Filled.Update
    SECTION_MOST_DOWNLOADED -> Icons.AutoMirrored.Filled.TrendingUp
    SECTION_SHIZUKU -> Icons.Filled.Bolt
    SECTION_ROOT -> Icons.Filled.AdminPanelSettings
    else -> categoryIcon(key)
}
