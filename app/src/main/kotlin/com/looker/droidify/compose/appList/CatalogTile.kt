package com.looker.droidify.compose.appList

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.looker.droidify.compose.components.AppTile
import com.looker.droidify.compose.components.TileIconSize
import com.looker.droidify.compose.components.TvTileIconSize
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.utility.common.extension.toSafeBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Square pixel size the system fallback icon is rendered at (generous so it stays crisp at any tile
 *  size, including the focus zoom). */
private const val LauncherIconPx = 256

private const val TAG = "CatalogTile"

/**
 * A catalogue app's icon, in priority order:
 *  1. the repo-served icon, falling back to its generic `/icon.png`;
 *  2. the launcher icon of the installed app, read from the system, when the repo ships none (e.g.
 *     Magisk on F-Droid, whose repo entry has no icon) — this is what the F-Droid client shows;
 *  3. the system default app icon.
 * Shared by every app tile so the icon looks the same everywhere.
 */
@Composable
fun AppMinimalIcon(
    app: AppMinimal,
    isInstalled: Boolean,
    modifier: Modifier = Modifier,
    onIconBitmap: ((Bitmap) -> Unit)? = null,
) {
    val isTelevision = LocalIsTelevision.current
    var repoIcon by remember(app.appId) { mutableStateOf(app.icon?.path) }
    var repoFailed by remember(app.appId) { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    // Hardware bitmaps (Coil's own default) can't be redrawn into the safe bitmap onIconBitmap needs
    // for colour extraction, not even into another software config: some devices (seen on a Pixel 8
    // emulator) refuse to draw a hardware source at all outside GPU-accelerated on-screen rendering,
    // throwing "Software rendering doesn't support hardware bitmaps" instead of just being slow.
    // Requesting a non-hardware decode up front avoids that outright, only paid when a caller actually
    // wants the bitmap back, so ordinary tiles (no onIconBitmap) keep the faster hardware path.
    val context = LocalContext.current
    val repoIconModel = remember(repoIcon, onIconBitmap != null) {
        repoIcon?.let { path ->
            ImageRequest.Builder(context).data(path).allowHardware(onIconBitmap == null).build()
        }
    }
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
        when {
            repoIcon != null && !repoFailed -> AsyncImage(
                model = repoIconModel,
                // Try the repo's generic /icon.png once, then give up so the installed icon / default shows.
                onError = {
                    val fallback = app.fallbackIcon?.path
                    if (fallback != null && fallback != repoIcon) repoIcon = fallback else repoFailed = true
                },
                onSuccess = onIconBitmap?.let { callback ->
                    { state: AsyncImagePainter.State.Success ->
                        state.toSafeBitmap(LauncherIconPx)?.let(callback)
                    }
                },
                contentDescription = null,
                // Fit, not Crop: app icons aren't all square, and cropping sliced the top/bottom off the
                // round ones. Fit shows the whole icon; for a square icon it fills the box just the same.
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )

            // Installed app whose repo ships no usable icon (e.g. Magisk): fall back to its on-device
            // launcher icon. Isolated in its own composable so the off-thread load (produceState) only
            // exists for these few tiles — not one idle coroutine per tile across the whole grid.
            isInstalled -> InstalledLauncherIcon(
                packageName = app.packageName.name,
                imageModifier = imageModifier,
                shape = shape,
                isTelevision = isTelevision,
                onIconBitmap = onIconBitmap,
            )

            else -> DefaultAppIcon(shape = shape, isTelevision = isTelevision)
        }
    }
}

/** The installed app's own launcher icon, read off the main thread; shows [DefaultAppIcon] until it
 *  loads (or if it can't be read). Only composed for the rare tile with no repo icon. */
@Composable
private fun InstalledLauncherIcon(
    packageName: String,
    imageModifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    isTelevision: Boolean,
    onIconBitmap: ((Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val launcher by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Explicit size: toBitmap() with none uses the drawable's intrinsic size, which for
                // adaptive icons renders inconsistently across Android versions (fine on newer phones,
                // cropped/squished on older TV builds), a square output normalises it everywhere.
                // Explicit ARGB_8888 config: toBitmap()'s default (null) returns a BitmapDrawable's own
                // bitmap unconverted whenever the requested size matches its intrinsic size, hardware
                // config included, which then crashes reading pixels back out for colour extraction.
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = LauncherIconPx, height = LauncherIconPx, config = Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }.onFailure { Log.e(TAG, "Unable to read $packageName's launcher icon", it) }.getOrNull()
        }
    }
    val bitmap = launcher
    if (bitmap != null) {
        LaunchedEffect(bitmap) { onIconBitmap?.invoke(bitmap.asAndroidBitmap()) }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = imageModifier,
        )
    } else {
        DefaultAppIcon(shape = shape, isTelevision = isTelevision)
    }
}

/** The neutral placeholder shown when an app has no icon at all. */
@Composable
private fun DefaultAppIcon(shape: androidx.compose.ui.graphics.Shape, isTelevision: Boolean) {
    Box(
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
    isUpdating: Boolean = false,
) {
    val iconSize = if (LocalIsTelevision.current) TvTileIconSize else TileIconSize
    AppTile(
        name = app.name,
        isInstalled = isInstalled,
        onClick = onClick,
        modifier = modifier,
        isUpdating = isUpdating,
    ) {
        AppMinimalIcon(app, isInstalled, Modifier.size(iconSize))
    }
}
