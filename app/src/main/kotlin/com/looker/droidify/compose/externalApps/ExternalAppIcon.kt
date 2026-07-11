package com.looker.droidify.compose.externalApps

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.looker.droidify.R
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.external.ExternalApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Square pixel size the system fallback icon is rendered at (generous so it stays crisp at any size). */
private const val LauncherIconPx = 256

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
) {
    val context = LocalContext.current
    val packageName = app.packageName
    // Loaded off the main thread (produceState + IO): reading the launcher icon inline for every tile
    // made lists of installed apps (Installed/Updates tabs) slow to open. It resolves a frame later; the
    // extracted/repo icon or a placeholder shows meanwhile.
    val launcherIcon by produceState<ImageBitmap?>(null, packageName, isInstalled) {
        value = if (isInstalled && packageName != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Explicit square output: toBitmap() with no size uses the drawable's intrinsic size,
                    // which renders adaptive icons inconsistently across Android versions (fine on newer
                    // phones, cropped/squished on older TV builds). A square normalises it everywhere.
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(width = LauncherIconPx, height = LauncherIconPx)
                        .asImageBitmap()
                }.getOrNull()
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
        when {
            launcher != null -> Image(
                bitmap = launcher,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )

            extractedIcon != null -> Image(
                bitmap = extractedIcon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )

            app.repoIconUrl != null && !repoIconFailed -> AsyncImage(
                model = app.repoIconUrl,
                onError = { repoIconFailed = true },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
            )

            app.iconUrl != null && !avatarFailed -> AsyncImage(
                model = app.iconUrl,
                onError = { avatarFailed = true },
                contentDescription = null,
                contentScale = ContentScale.Fit,
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
