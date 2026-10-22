package com.looker.droidify.data.backup

import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.model.InstallerType

/**
 * A setting the person chose on the welcome pages. Bringing their data over from those same pages, by
 * a backup file, another device or an Omnify Beta, restores everything else but leaves these as they
 * were chosen: the person picked root as the installer, then restored a backup made with the session
 * installer, and found the session installer back.
 */
enum class WelcomeChoice { INSTALLER, THEME, ACCENT, BACKGROUND }

/** These settings, read from a backup, with the fields of [choices] taken from [current] instead. */
fun Settings.keeping(choices: Set<WelcomeChoice>, current: Settings): Settings =
    choices.fold(this) { settings, choice ->
        when (choice) {
            WelcomeChoice.INSTALLER -> settings.copy(
                installerType = current.installerType,
                // Off with the legacy installer, as choosing it sets it (see InstallerSelection).
                deleteApkOnInstall = settings.deleteApkOnInstall && current.installerType != InstallerType.LEGACY,
            )
            WelcomeChoice.THEME -> settings.copy(theme = current.theme)
            WelcomeChoice.ACCENT -> settings.copy(
                themeColor = current.themeColor,
                dynamicTheme = current.dynamicTheme,
            )
            WelcomeChoice.BACKGROUND -> settings.copy(backgroundStyle = current.backgroundStyle)
        }
    }
