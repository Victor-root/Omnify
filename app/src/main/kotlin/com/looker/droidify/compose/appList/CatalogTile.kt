package com.looker.droidify.compose.appList

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
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
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.looker.droidify.compose.components.AsyncImageWithSkeleton
import com.looker.droidify.compose.components.AppTile
import com.looker.droidify.compose.components.TileIconSize
import com.looker.droidify.compose.components.TvTileIconSize
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.toSafeBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Square pixel size the system fallback icon is rendered at (generous so it stays crisp at any tile
 *  size, including the focus zoom). */
internal const val LauncherIconPx = 256

private const val TAG = "CatalogTile"

/** Memory budget of [InstalledLauncherIcons]: 32 icons at [LauncherIconPx]. */
private const val InstalledLauncherIconsBytes = 32 * LauncherIconPx * LauncherIconPx * 4

/**
 * Installed apps' launcher icons as [readInstalledLauncherIcon] renders them, so a tile coming back into
 * view doesn't render the same icon again. Keyed per install (package and lastUpdateTime), and emptied
 * when the configuration changes, which the icon's resources and the system's icon shape depend on.
 */
private object InstalledLauncherIcons {
    private val icons = object : LruCache<String, ImageBitmap>(InstalledLauncherIconsBytes) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }
    private var configuration: Configuration? = null

    @Synchronized
    fun get(configuration: Configuration, key: String): ImageBitmap? {
        if (configuration != this.configuration) {
            icons.evictAll()
            this.configuration = configuration
        }
        return icons[key]
    }

    @Synchronized
    fun put(configuration: Configuration, key: String, icon: ImageBitmap) {
        if (configuration == this.configuration) icons.put(key, icon)
    }
}

/**
 * [packageName]'s launcher icon as installed on the device, rendered off the main thread at
 * [LauncherIconPx] square; a failure (e.g. [PackageManager.NameNotFoundException] when it isn't
 * installed) is left to the caller to report.
 */
internal suspend fun readInstalledLauncherIcon(context: Context, packageName: String): Result<ImageBitmap> =
    withContext(Dispatchers.IO) {
        runCatching {
            val configuration = Configuration(context.resources.configuration)
            // Without it (not installed, or unreadable) there is nothing to key on, and getApplicationIcon
            // below then throws the very error the caller reports.
            val key = context.packageManager.getPackageInfoCompat(packageName, signatureFlag = 0)
                ?.let { "$packageName:${it.lastUpdateTime}" }
            key?.let { InstalledLauncherIcons.get(configuration, it) }
                // Explicit size: toBitmap() with none uses the drawable's intrinsic size, which renders
                // adaptive icons inconsistently across Android versions (fine on newer phones,
                // cropped/squished on older TV builds), a square output normalises it everywhere.
                // Explicit ARGB_8888 config: toBitmap()'s default (null) returns a BitmapDrawable's own
                // bitmap unconverted whenever the requested size matches its intrinsic size, hardware
                // config included, which then crashes reading pixels back out for colour extraction.
                ?: context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = LauncherIconPx, height = LauncherIconPx, config = Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
                    .also { if (key != null) InstalledLauncherIcons.put(configuration, key, it) }
        }
    }

/**
 * A catalogue app's icon, in priority order:
 *  1. the repo-served icon, falling back to its generic `/icon.png`;
 *  2. the launcher icon of the installed app, read from the system, when the repo ships none (e.g.
 *     Magisk on F-Droid, whose repo entry has no icon) — this is what the F-Droid client shows;
 *  3. the system default app icon.
 * Shared by every app tile so the icon looks the same everywhere.
 *
 * [onIconBitmap] receives each picture of the app's icon as it shows, with a key naming where it came
 * from and a way to read it as a bitmap (null if it can't be), slow enough to only be called off the
 * main thread.
 */
@Composable
fun AppMinimalIcon(
    app: AppMinimal,
    isInstalled: Boolean,
    modifier: Modifier = Modifier,
    onIconBitmap: ((source: String, bitmap: () -> Bitmap?) -> Unit)? = null,
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
            repoIcon != null && !repoFailed -> AsyncImageWithSkeleton(
                model = repoIconModel,
                // Try the repo's generic /icon.png once, then give up so the installed icon / default shows.
                onError = {
                    val fallback = app.fallbackIcon?.path
                    if (fallback != null && fallback != repoIcon) repoIcon = fallback else repoFailed = true
                },
                onSuccess = onIconBitmap?.let { callback ->
                    { state: AsyncImagePainter.State.Success ->
                        callback("repo:$repoIcon") { state.toSafeBitmap(LauncherIconPx) }
                    }
                },
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
    onIconBitmap: ((source: String, bitmap: () -> Bitmap?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val launcher by produceState<ImageBitmap?>(null, packageName) {
        value = readInstalledLauncherIcon(context, packageName)
            .onFailure { Log.e(TAG, "Unable to read $packageName's launcher icon", it) }
            .getOrNull()
    }
    val bitmap = launcher
    if (bitmap != null) {
        LaunchedEffect(bitmap) { onIconBitmap?.invoke("launcher:$packageName") { bitmap.asAndroidBitmap() } }
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
    updateFraction: Float? = null,
) {
    val iconSize = if (LocalIsTelevision.current) TvTileIconSize else TileIconSize
    AppTile(
        name = app.name,
        isInstalled = isInstalled,
        onClick = onClick,
        modifier = modifier,
        isUpdating = isUpdating,
        updateFraction = updateFraction,
    ) {
        AppMinimalIcon(app, isInstalled, Modifier.size(iconSize))
    }
}
