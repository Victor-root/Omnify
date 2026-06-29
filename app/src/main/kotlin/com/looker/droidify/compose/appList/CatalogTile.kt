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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.looker.droidify.compose.components.AppTile
import com.looker.droidify.compose.components.TileIconSize
import com.looker.droidify.data.model.AppMinimal

/**
 * A catalogue app's icon, in priority order:
 *  1. the repo-served icon, falling back to its generic `/icon.png`;
 *  2. the launcher icon of the installed app, read from the system, when the repo ships none (e.g.
 *     Magisk on F-Droid, whose repo entry has no icon) — this is what the F-Droid client shows;
 *  3. the system default app icon.
 * Shared by every app tile so the icon looks the same everywhere.
 */
@Composable
fun AppMinimalIcon(app: AppMinimal, isInstalled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The installed app's own launcher icon, read once. Mirrors ExternalAppIcon; cheap enough for a
    // list as the package manager caches it.
    val launcherIcon = remember(app.packageName, isInstalled) {
        if (isInstalled) {
            runCatching {
                context.packageManager.getApplicationIcon(app.packageName.name).toBitmap().asImageBitmap()
            }.getOrNull()
        } else {
            null
        }
    }
    var repoIcon by remember(app.appId) { mutableStateOf(app.icon?.path) }
    var repoFailed by remember(app.appId) { mutableStateOf(false) }
    when {
        repoIcon != null && !repoFailed -> AsyncImage(
            model = repoIcon,
            // Try the repo's generic /icon.png once, then give up so the installed icon / default shows.
            onError = {
                val fallback = app.fallbackIcon?.path
                if (fallback != null && fallback != repoIcon) repoIcon = fallback else repoFailed = true
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(MaterialTheme.shapes.large),
        )

        launcherIcon != null -> Image(
            bitmap = launcherIcon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(MaterialTheme.shapes.large),
        )

        else -> Box(
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
        AppMinimalIcon(app, isInstalled, Modifier.size(TileIconSize))
    }
}
