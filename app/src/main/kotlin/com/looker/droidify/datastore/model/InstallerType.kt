package com.looker.droidify.datastore.model

import com.looker.droidify.utility.common.device.Miui

/**
 * The ways Omnify can install an app. The order is the one the lists show: the one that suits almost
 * everyone first, then the ones for people who know why they want them.
 */
enum class InstallerType {
    SESSION,
    LEGACY,
    SHIZUKU,
    ROOT,
    ;

    /** Whether installs through this installer can record another app as their installer. */
    val canSetInstallSource: Boolean
        get() = this == SHIZUKU || this == ROOT

    companion object {
        val Default: InstallerType
            get() = if (Miui.isMiui) {
                if (Miui.isMiuiOptimizationDisabled()) SESSION else LEGACY
            } else {
                SESSION
            }
    }
}
