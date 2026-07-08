package com.looker.droidify.utility.common.extension

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.TaskStackBuilder
import com.looker.droidify.utility.common.SdkCheck

fun intent(action: String, block: Intent.() -> Unit = {}): Intent {
    return Intent(action).apply(block)
}

/** Opens Android's own "App info" system settings page for [packageName] — uninstall, clear cache/data,
 *  permissions, battery/notification settings, all the OS-level management a catalogue or external
 *  source detail screen doesn't reimplement itself. No-op (never throws) if no such screen exists on
 *  this device/ROM. */
fun Context.openAppInfo(packageName: String) {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }
}

inline val intentFlagCompat
    get() = if (SdkCheck.isSnowCake) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

fun Intent.toPendingIntent(context: Context): PendingIntent? =
    TaskStackBuilder
        .create(context)
        .addNextIntentWithParentStack(this)
        .getPendingIntent(0, intentFlagCompat)

operator fun Uri?.get(key: String): String? = this?.getQueryParameter(key)
