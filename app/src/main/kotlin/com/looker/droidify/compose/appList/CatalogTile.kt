package com.looker.droidify.compose.appList

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.looker.droidify.compose.components.AppTile
import com.looker.droidify.compose.components.TileIconSize
import com.looker.droidify.data.model.AppMinimal

/**
 * A catalogue app's icon: the repo-served icon, falling back to a secondary URL and then to the
 * system default. Shared by every app tile so the icon looks the same everywhere.
 */
@Composable
fun AppMinimalIcon(app: AppMinimal, modifier: Modifier = Modifier) {
    var icon by remember(app.appId) { mutableStateOf(app.icon?.path) }
    if (icon != null) {
        AsyncImage(
            model = icon,
            onError = { icon = app.fallbackIcon?.path },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(MaterialTheme.shapes.large),
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
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
}

/**
 * A catalogue app as a tile (icon + name) — the single presentation used by the Discover carousels
 * and by every tab's grid, so apps look identical everywhere.
 */
@Composable
fun CatalogAppTile(
    app: AppMinimal,
    isInstalled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppTile(
        name = app.name,
        isInstalled = isInstalled,
        onClick = onClick,
        modifier = modifier,
    ) {
        AppMinimalIcon(app, Modifier.size(TileIconSize))
    }
}
