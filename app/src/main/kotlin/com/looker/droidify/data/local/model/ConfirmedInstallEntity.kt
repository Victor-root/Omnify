package com.looker.droidify.data.local.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * packageName -> the versionCode Omnify itself confirmed installing (see
 * [com.looker.droidify.installer.InstallManager.onInstallSucceeded]).
 *
 * Kept entirely separate from [InstalledEntity]: that table mirrors the whole device's installed
 * packages and is fully wiped and rebuilt from a raw PackageManager scan every time Omnify starts (see
 * [com.looker.droidify.Droidify.listenApplications]), which would erase this fact just as often if it
 * lived there instead. This table is untouched by that scan, so it survives closing and reopening
 * Omnify, and Android itself can lose track of who installed a package once the app that installed it
 * (Omnify) is uninstalled, even though the package in question was never touched. See
 * [com.looker.droidify.utility.common.extension.installerSourceLabel]'s `knownInstalledByOmnify`
 * parameter, which this table's whole purpose is to feed.
 *
 * Lives in Omnify's own database, so it does not survive a full uninstall of Omnify itself (this app
 * disables Android's automatic backup entirely). It self-heals the moment Omnify installs or updates
 * the app again, exactly like Android's own record would if it hadn't been lost.
 */
@Entity("confirmed_install")
data class ConfirmedInstallEntity(
    @PrimaryKey
    val packageName: String,
    val versionCode: Long,
)
