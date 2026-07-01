package com.looker.droidify.compose.appList

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.looker.droidify.compose.components.AppTile
import com.looker.droidify.compose.components.TileIconSize
import com.looker.droidify.compose.components.TvTileIconSize
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.data.model.AppMinimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Square pixel size the system fallback icon is rendered at (generous so it stays crisp at any tile
 *  size, including the focus zoom). */
private const val LauncherIconPx = 256

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
    val isTelevision = LocalIsTelevision.current
    var repoIcon by remember(app.appId) { mutableStateOf(app.icon?.path) }
    var repoFailed by remember(app.appId) { mutableStateOf(false) }
    // The installed app's own launcher icon is only a fallback for the rare app whose repo ships no icon
    // (e.g. Magisk). Load it lazily — only when the repo icon is absent or failed — and OFF the main
    // thread: reading it inline for every installed tile made the Installed/Updates tabs slow to open,
    // since getApplicationIcon + a 256px bitmap ran during composition even when the repo icon was shown.
    val needsLauncherIcon = isInstalled && (repoIcon == null || repoFailed)
    val launcherIcon by produceState<ImageBitmap?>(null, app.packageName, needsLauncherIcon) {
        value = if (needsLauncherIcon) {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Render into an explicit square bitmap. toBitmap() with no size uses the drawable's
                    // intrinsic size, which for adaptive icons renders inconsistently across Android
                    // versions (fine on newer phones, cropped/squished on older TV builds) — a square
                    // output normalises it everywhere.
                    context.packageManager.getApplicationIcon(app.packageName.name)
                        .toBitmap(width = LauncherIconPx, height = LauncherIconPx)
                        .asImageBitmap()
                }.getOrNull()
            }
        } else {
            null
        }
    }
    val shape = MaterialTheme.shapes.large
    // On TV every icon sits on the same rounded card: a full-bleed icon covers it, while a padded or
    // round icon sits centred on it instead of floating at an odd size — so the whole grid reads as
    // uniform. Mobile keeps the icon clipped on its own (no card), which already looks right there.
    Box(
        modifier = if (isTelevision) {
            modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        } else {
            modifier
        },
        contentAlignment = Alignment.Center,
    ) {
        // Off TV the image clips itself to the tile shape; on TV the card already clips, so it just fills.
        val imageModifier = Modifier
            .fillMaxSize()
            .then(if (isTelevision) Modifier else Modifier.clip(shape))
        // Local copy so the null-check smart-casts (launcherIcon is a produceState delegate).
        val launcher = launcherIcon
        when {
            repoIcon != null && !repoFailed -> AsyncImage(
                model = repoIcon,
                // Try the repo's generic /icon.png once, then give up so the installed icon / default shows.
                onError = {
                    val fallback = app.fallbackIcon?.path
                    if (fallback != null && fallback != repoIcon) repoIcon = fallback else repoFailed = true
                },
                contentDescription = null,
                // Fit, not Crop: app icons aren't all square, and cropping sliced the top/bottom off the
                // round ones. Fit shows the whole icon; for a square icon it fills the box just the same.
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )

            launcher != null -> Image(
                bitmap = launcher,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )

            else -> Box(
                contentAlignment = Alignment.Center,
                // The card already supplies the background on TV; off TV draw our own neutral box.
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isTelevision) {
                            Modifier
                        } else {
                            Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        },
                    ),
            ) {
                Image(
                    painter = painterResource(android.R.mipmap.sym_def_app_icon),
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                )
            }
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
    val iconSize = if (LocalIsTelevision.current) TvTileIconSize else TileIconSize
    AppTile(
        name = app.name,
        isInstalled = isInstalled,
        onClick = onClick,
        modifier = modifier,
    ) {
        AppMinimalIcon(app, isInstalled, Modifier.size(iconSize))
    }
}
