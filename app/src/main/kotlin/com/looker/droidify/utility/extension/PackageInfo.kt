package com.looker.droidify.utility.extension

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.model.InstalledItem
import com.looker.droidify.utility.common.extension.calculateHash
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.singleSignature
import com.looker.droidify.utility.common.extension.versionCodeCompat

fun PackageInfo.toInstalledItem(): InstalledItem {
    val signatureString = singleSignature?.calculateHash().orEmpty()
    return InstalledItem(
        packageName,
        versionName.orEmpty(),
        versionCodeCompat,
        signatureString,
    )
}

/**
 * Brings [packageName]'s stored row in line with what [packageManager] reports right now: written
 * while the package is installed, dropped once it isn't. The installed table is what decides whether
 * an app still counts as updatable (see
 * [com.looker.droidify.compose.appList.AppListViewModel.updatableApps]), so this is the one write that
 * makes an app leave the Updates tab. Returns what it just wrote (or null once removed), so a caller
 * that already needs the fresh [InstalledItem] (see
 * [com.looker.droidify.installer.InstallManager.onInstallSucceeded]) doesn't have to re-read it right
 * back out.
 *
 * Shared by the two things that know a package changed: the system's package broadcast
 * ([com.looker.droidify.receivers.InstalledAppReceiver]) and this app's own installer reporting
 * success. The installer runs it without waiting for the broadcast, which the system delivers on its
 * own schedule and can hold back for seconds, longest of all right after a batch of updates.
 */
suspend fun InstalledRepository.syncInstalled(packageManager: PackageManager, packageName: String): InstalledItem? {
    val installed = packageManager.getPackageInfoCompat(packageName)?.toInstalledItem()
    if (installed != null) put(installed) else delete(packageName)
    return installed
}
