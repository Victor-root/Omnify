package com.looker.droidify.utility.common

import android.Manifest
import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.looker.droidify.utility.common.extension.intent
import com.looker.droidify.utility.common.extension.powerManager

private const val TAG = "Permissions"

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
    Log.d(TAG, "dominantAccentColor: called, SDK=${Build.VERSION.SDK_INT}, ${width}x$height, config=$config")
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        Log.d(TAG, "dominantAccentColor: skipped, SDK below S")
        return null
    }
    return runCatching { WallpaperColors.fromBitmap(this).primaryColor.toArgb() }
        .onSuccess { Log.d(TAG, "dominantAccentColor: extracted ${Integer.toHexString(it)}") }
        .onFailure { Log.e(TAG, "Unable to extract a dominant colour from this bitmap", it) }
        .getOrNull()
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
