package com.looker.droidify.datastore.model

import com.looker.droidify.utility.common.device.Miui

enum class InstallerType {
    LEGACY,
    SESSION,
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
