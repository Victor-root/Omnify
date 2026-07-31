package com.looker.droidify.utility.common

import android.Manifest
import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.looker.droidify.utility.common.extension.intent
import com.looker.droidify.utility.common.extension.powerManager

private const val TAG = "Permissions"

/** A pixel this opaque or more counts as real content in [opaqueContent]; anything less transparent
 *  gets replaced by that content's own average colour instead. */
private const val OPAQUE_ALPHA_THRESHOLD = 128

fun Context.isIgnoreBatteryEnabled() =
    powerManager?.isIgnoringBatteryOptimizations(packageName) == true

/**
 * The primary colour of the current system wallpaper (ARGB), or null if unavailable. Read straight
 * from the wallpaper — NOT the system "dynamic"/Material You accent — so it's correct even on OEM
 * skins (e.g. ColorOS) where that accent doesn't follow the wallpaper. Needs no permission (only the
 * extracted colours are read, not the image). API 27+.
 */
fun Context.wallpaperAccentColor(): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return runCatching {
        WallpaperManager.getInstance(this)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor
            ?.toArgb()
    }.getOrNull()
}

/**
 * This bitmap's dominant colour (ARGB), via the same system colour-quantization
 * [wallpaperAccentColor] reads off the live wallpaper, run here against an app icon instead. API 31+
 * only (the [WallpaperColors.fromBitmap] factory).
 */
fun Bitmap.dominantAccentColor(): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val content = opaqueContent() ?: return null
    return runCatching { WallpaperColors.fromBitmap(content).primaryColor.toArgb() }
        .onFailure { Log.e(TAG, "Unable to extract a dominant colour from this bitmap", it) }
        .getOrNull()
}

/**
 * This bitmap cropped to the bounding box of its opaque content, with any transparency still inside
 * that box (a circular icon's corners, an adaptive icon's own inset) replaced by that content's own
 * average colour. Null if the bitmap has no opaque content at all.
 *
 * [WallpaperColors.fromBitmap] doesn't skip transparent pixels: their RGB (0,0,0 for a premultiplied-
 * transparent pixel) counts as a real sample during quantization same as any other, so an icon with a
 * transparent background around a small coloured glyph extracted as black instead of the glyph's own
 * colour. Flattening onto a fixed colour like white doesn't really fix this, it just replaces "black
 * wins because the background is large" with "white wins because the background is large" for an icon
 * whose visible content doesn't fill its square canvas (a circle, an icon with generous padding).
 * Filling with the content's own average instead can never introduce a new dominant colour that isn't
 * already part of the icon, whatever shape that content takes.
 */
private fun Bitmap.opaqueContent(): Bitmap? {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    var left = width
    var right = -1
    var top = height
    var bottom = -1
    var redSum = 0L
    var greenSum = 0L
    var blueSum = 0L
    var opaqueCount = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixel = pixels[y * width + x]
            if (Color.alpha(pixel) < OPAQUE_ALPHA_THRESHOLD) continue
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
            redSum += Color.red(pixel)
            greenSum += Color.green(pixel)
            blueSum += Color.blue(pixel)
            opaqueCount++
        }
    }
    if (opaqueCount == 0) return null

    val averageColor = Color.rgb(
        (redSum / opaqueCount).toInt(),
        (greenSum / opaqueCount).toInt(),
        (blueSum / opaqueCount).toInt(),
    )
    val contentWidth = right - left + 1
    val contentHeight = bottom - top + 1
    val contentPixels = IntArray(contentWidth * contentHeight) { i ->
        val x = left + i % contentWidth
        val y = top + i / contentWidth
        val pixel = pixels[y * width + x]
        if (Color.alpha(pixel) >= OPAQUE_ALPHA_THRESHOLD) pixel else averageColor
    }
    return Bitmap.createBitmap(contentWidth, contentHeight, Bitmap.Config.ARGB_8888).apply {
        setPixels(contentPixels, 0, contentWidth, 0, 0, contentWidth, contentHeight)
    }
}

/** Whether the app may install APKs from "unknown sources". Always true below Android 8, where it
 *  isn't gated per app; needed by the default/session installer. */
fun Context.canRequestPackageInstalls(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

/** Opens the system page where the user allows this app to install unknown apps. No-op below O. */
fun Context.openUnknownAppSourcesSettings() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val intent = intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) {
        data = "package:$packageName".toUri()
    }
    runCatching { startActivity(intent) }
}

fun Context.requestBatteryFreedom() {
    if (!isIgnoreBatteryEnabled()) {
        val intent = intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            data = "package:$packageName".toUri()
        }
        runCatching {
            startActivity(intent)
        }
    }
}

fun Activity.requestNotificationPermission(
    request: (permission: String) -> Unit,
    onGranted: () -> Unit = {},
) {
    when {
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED -> {
            onGranted()
        }

        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
            sdkAbove(Build.VERSION_CODES.TIRAMISU) {
                request(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        else -> {
            sdkAbove(Build.VERSION_CODES.TIRAMISU) {
                request(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
