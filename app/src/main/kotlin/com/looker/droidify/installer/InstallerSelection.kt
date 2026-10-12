package com.looker.droidify.installer

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.looker.droidify.R
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.installer.installers.initSui
import com.looker.droidify.installer.installers.isMagiskGranted
import com.looker.droidify.installer.installers.isShizukuAlive
import com.looker.droidify.installer.installers.isShizukuGranted
import com.looker.droidify.installer.installers.isShizukuInstalled
import com.looker.droidify.installer.installers.requestPermissionListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Switching to another installer. Some of them need something from the device first (Shizuku running
 * and allowed, root granted), so the choice is only recorded once that is true, and a toast says why
 * when it is not. Shared by Settings and the welcome page, so the two can never disagree about when a
 * switch is allowed.
 */
class InstallerSelection @Inject constructor(
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) {

    suspend fun select(installerType: InstallerType) {
        when (installerType) {
            InstallerType.SHIZUKU -> selectShizuku(installerType)
            InstallerType.ROOT -> selectRoot(installerType)
            InstallerType.LEGACY -> {
                settingsRepository.setDeleteApkOnInstall(false)
                settingsRepository.setInstallerType(installerType)
            }
            else -> settingsRepository.setInstallerType(installerType)
        }
    }

    private suspend fun selectShizuku(installerType: InstallerType) {
        if (isShizukuInstalled(context) || initSui(context)) {
            when {
                !isShizukuAlive() -> showToast(R.string.shizuku_not_alive)
                isShizukuGranted() -> settingsRepository.setInstallerType(installerType)
                else -> {
                    if (requestPermissionListener()) {
                        settingsRepository.setInstallerType(installerType)
                    }
                }
            }
        } else {
            showToast(R.string.shizuku_not_installed)
        }
    }

    private suspend fun selectRoot(installerType: InstallerType) {
        // isMagiskGranted() blocks synchronously on the root shell actually starting up — the first
        // time, that includes waiting on the user to answer Magisk's own grant prompt. Run off the main
        // thread (the callers' scopes default to Main), or the whole app hangs and looks like an ANR
        // ("Omnify ne répond pas") until root either grants or times out.
        val granted = withContext(Dispatchers.IO) { isMagiskGranted() }
        if (granted) {
            settingsRepository.setInstallerType(installerType)
        } else {
            // Previously silent: nothing told the user why the switch didn't happen — it just looked
            // like tapping "Root" did nothing, with only a logcat line (see isMagiskGranted) to go on.
            showToast(R.string.root_not_granted)
        }
    }

    private fun showToast(@StringRes messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }
}
