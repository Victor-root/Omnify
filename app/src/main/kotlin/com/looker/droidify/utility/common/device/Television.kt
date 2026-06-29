package com.looker.droidify.utility.common.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Whether the app is running on an Android TV (leanback) device. Used to turn on D-pad focused
 * behaviour (visible focus, focus bridges, overscan margins) without changing the touch UI. Checks
 * both the current UI mode and the leanback system feature, so it holds on real TVs and emulators.
 */
fun Context.isTelevision(): Boolean {
    val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
