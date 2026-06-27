package com.looker.droidify.compose.externalApps

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import com.looker.droidify.R
import com.looker.droidify.external.ExternalApp

/**
 * Icon for an external app, in priority order:
 *  1. the real launcher icon read from the system, once the app is installed;
 *  2. the source account avatar (e.g. the GitHub owner's logo) before installing;
 *  3. a neutral box placeholder when neither is available.
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
    val launcherIcon = remember(packageName, isInstalled) {
        if (isInstalled && packageName != null) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
            }.getOrNull()
        } else {
            null
        }
    }
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
    // Render exactly like a catalogue icon ([AppMinimalIcon]): the image is clipped to the tile shape
    // and cropped to fill, with NO box behind it — a background only shows for the placeholder. Drawing
    // the surface box behind every real icon is what left the ugly rounded rectangle around icons that
    // aren't full-bleed (e.g. a circular logo).
    val shape = MaterialTheme.shapes.large
    when {
        launcherIcon != null -> Image(
            bitmap = launcherIcon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )

        extractedIcon != null -> Image(
            bitmap = extractedIcon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )

        app.iconUrl != null && !avatarFailed -> AsyncImage(
            model = app.iconUrl,
            onError = { avatarFailed = true },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )

        else -> Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
