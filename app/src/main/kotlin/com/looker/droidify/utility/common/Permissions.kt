package com.looker.droidify.utility.common

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.looker.droidify.utility.common.extension.intent
import com.looker.droidify.utility.common.extension.powerManager

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

/** Whether Omnify may post notifications right now: the permission from Android 13, and the app's own
 *  notification switch before it. */
fun Context.areNotificationsEnabled(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()

/** Opens the system page of this app's notifications, where they are switched on once Android will no
 *  longer ask. Falls back to nothing (never throws) when a ROM has no such page. */
fun Context.openNotificationSettings() {
    val intent = intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    }
    runCatching { startActivity(intent) }
}
