package com.looker.droidify.installer.installers.shizuku

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.looker.droidify.R
import com.looker.droidify.datastore.model.InstallerType
import rikka.shizuku.Shizuku

/**
 * Shizuku readiness checks, shared by the Shizuku installer and by the install flows so they can bail
 * out (with a clear reason) *before* downloading an APK that could never be installed.
 */
object ShizukuState {

    const val PERMISSION_REQUEST_CODE = 87263
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class Readiness { READY, NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION }

    fun readiness(context: Context): Readiness {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) {
            return if (isShizukuAppInstalled(context)) Readiness.NOT_RUNNING else Readiness.NOT_INSTALLED
        }
        val granted = runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) Readiness.READY else Readiness.NO_PERMISSION
    }

    /** Opens Shizuku's permission dialog so the user can grant it, then retry. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    private fun isShizukuAppInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
    }.isSuccess

    /**
     * When [installerType] is Shizuku and it isn't usable, returns a short message explaining why (and
     * opens Shizuku's permission prompt when that's the issue), so the caller can warn the user and skip
     * the download. Null means it's fine to proceed.
     */
    @StringRes
    fun installBlockReason(context: Context, installerType: InstallerType): Int? {
        if (installerType != InstallerType.SHIZUKU) return null
        return when (readiness(context)) {
            Readiness.READY -> null
            Readiness.NOT_INSTALLED -> R.string.shizuku_not_installed
            Readiness.NOT_RUNNING -> R.string.shizuku_not_running
            Readiness.NO_PERMISSION -> {
                requestPermission()
                R.string.shizuku_permission_needed
            }
        }
    }
}
