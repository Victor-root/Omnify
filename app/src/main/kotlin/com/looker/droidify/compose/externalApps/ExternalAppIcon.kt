package com.looker.droidify.compose.externalApps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.looker.droidify.R
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalIconCache
import com.looker.droidify.utility.common.extension.toSafeBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Square pixel size the system fallback icon is rendered at (generous so it stays crisp at any size). */
private const val LauncherIconPx = 256

private const val TAG = "ExternalAppIcon"

/**
 * Icon for an external app, in priority order:
 *  1. the real launcher icon read from the system, once the app is installed (or extracted from the
 *     APK we downloaded);
 *  2. the launcher icon found in the source repo, or one the user picked, before installing;
 *  3. the source account avatar (e.g. the GitHub owner's logo) as a fallback;
 *  4. a neutral box placeholder when none is available.
 *
 * Sized via [size] so the same composable serves the grid cards, the detail header and the
 * management list.
 */
@Composable
fun ExternalAppIcon(
    app: ExternalApp,
    isInstalled: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    onIconBitmap: ((Bitmap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val packageName = app.packageName
    // Hardware bitmaps (Coil's own default) can't be redrawn into the safe bitmap onIconBitmap needs
    // for colour extraction, not even into another software config: some devices (seen on a Pixel 8
    // emulator) refuse to draw a hardware source at all outside GPU-accelerated on-screen rendering,
    // throwing "Software rendering doesn't support hardware bitmaps" instead of just being slow.
    // Requesting a non-hardware decode up front avoids that outright, only paid when a caller actually
    // wants the bitmap back, so ordinary tiles (no onIconBitmap) keep the faster hardware path.
    val repoIconModel = remember(app.repoIconUrl, onIconBitmap != null) {
        app.repoIconUrl?.let { url ->
            ImageRequest.Builder(context).data(url).allowHardware(onIconBitmap == null).build()
        }
    }
    val avatarModel = remember(app.iconUrl, onIconBitmap != null) {
        app.iconUrl?.let { url ->
            ImageRequest.Builder(context).data(url).allowHardware(onIconBitmap == null).build()
        }
    }
    // Loaded off the main thread (produceState + IO): reading the launcher icon inline for every tile
    // made lists of installed apps (Installed/Updates tabs) slow to open. It resolves a frame later; the
    // extracted/repo icon or a placeholder shows meanwhile.
    val launcherIcon by produceState<ImageBitmap?>(null, packageName, isInstalled) {
        value = if (isInstalled && packageName != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Explicit size: toBitmap() with none uses the drawable's intrinsic size, which
                    // renders adaptive icons inconsistently across Android versions (fine on newer
                    // phones, cropped/squished on older TV builds), a square output normalises it
                    // everywhere. Explicit ARGB_8888 config: toBitmap()'s default (null) returns a
                    // BitmapDrawable's own bitmap unconverted whenever the requested size matches its
                    // intrinsic size, hardware config included, which then crashes reading pixels back
                    // out for colour extraction.
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(width = LauncherIconPx, height = LauncherIconPx, config = Bitmap.Config.ARGB_8888)
                        .asImageBitmap()
                }.onFailure { Log.e(TAG, "Unable to read $packageName's launcher icon", it) }.getOrNull()
            }
        } else {
            null
        }
    }
    var repoIconFailed by remember(app.repoIconUrl) { mutableStateOf(false) }
    var avatarFailed by remember(app.iconUrl) { mutableStateOf(false) }
    // The real icon extracted from the app's APK (cached as a PNG), decoded once. Keyed on the file's
    // timestamp so it refreshes if re-extracted. Loaded as a bitmap (not via Coil) since this is a
    // local file.
    val extractedFile = ExternalIconCache.iconFile(context, app.key).takeIf { it.exists() }
    val extractedIcon = remember(app.key, extractedFile?.lastModified()) {
        extractedFile?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap() }.getOrNull()
        }
    }
    // Icons shown in full (Fit, not Crop, so non-square ones aren't sliced). On TV every icon sits on the
    // same rounded card so the grid is uniform (a full-bleed icon covers it, a padded or round one sits
    // centred on it). Off TV there's no box behind real icons — a box behind a circular logo looked like
    // an ugly rounded rectangle; only the placeholder gets its own background there.
    val isTelevision = LocalIsTelevision.current
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (isTelevision) {
                    Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Off TV the image clips itself to the tile shape; on TV the card already clips, so it just fills.
        val imageModifier = Modifier
            .fillMaxSize()
            .then(if (isTelevision) Modifier else Modifier.clip(shape))
        // Local copy so the null-check smart-casts (launcherIcon is a produceState delegate).
        val launcher = launcherIcon
        // The repo icon is very often the small flat PNG a project keeps only for pre-Android-8 devices
        // (its real icon being an adaptive one Android composes from a vector at whatever size it's
        // drawn, with no fixed source resolution to run out of): confirmed on Victor-root/OpenMessages,
        // whose only composed raster is a 192px legacy fallback next to a 108dp vector adaptive icon.
        // Compose's default filter (FilterQuality.Low, a single-sample bilinear) is noticeably softer on
        // that kind of upscale than it needs to be; High costs nothing extra for an icon-sized image and
        // stops the pre-install icon from looking worse than the real one it's standing in for.
        when {
            launcher != null -> {
                LaunchedEffect(launcher) { onIconBitmap?.invoke(launcher.asAndroidBitmap()) }
                Image(
                    bitmap = launcher,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = imageModifier,
                )
            }

            extractedIcon != null -> {
                LaunchedEffect(extractedIcon) { onIconBitmap?.invoke(extractedIcon.asAndroidBitmap()) }
                Image(
                    bitmap = extractedIcon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = imageModifier,
                )
            }

            app.repoIconUrl != null && !repoIconFailed -> AsyncImage(
                model = repoIconModel,
                onError = { repoIconFailed = true },
                onSuccess = onIconBitmap?.let { callback ->
                    { state: AsyncImagePainter.State.Success ->
                        state.toSafeBitmap(LauncherIconPx)?.let(callback)
                    }
                },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = imageModifier,
            )

            app.iconUrl != null && !avatarFailed -> AsyncImage(
                model = avatarModel,
                onError = { avatarFailed = true },
                onSuccess = onIconBitmap?.let { callback ->
                    { state: AsyncImagePainter.State.Success ->
                        state.toSafeBitmap(LauncherIconPx)?.let(callback)
                    }
                },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = imageModifier,
            )

            else -> Box(
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
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tabler_box),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        }
    }
}
