package com.looker.droidify.installer.installers

import android.content.Context
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.extension.PLAY_STORE_PACKAGE_NAME

interface Installer : AutoCloseable {

    suspend fun install(installItem: InstallItem): InstallState

    suspend fun uninstall(packageName: PackageName)
}

/** The package recorded as the installer of an app installed through `pm`: Omnify, or Google Play when
 *  the user asked for it. Read per install so changing the setting never touches one in progress. */
suspend fun Context.installSourcePackage(settingsRepository: SettingsRepository): String =
    if (settingsRepository.getInitial().playStoreInstallSource) PLAY_STORE_PACKAGE_NAME else packageName
