package com.looker.droidify.utility.common

object Constants {
    const val NOTIFICATION_CHANNEL_SYNCING = "syncing"
    const val NOTIFICATION_CHANNEL_UPDATES = "updates"
    const val NOTIFICATION_CHANNEL_DOWNLOADING = "downloading"
    const val NOTIFICATION_CHANNEL_INSTALL = "install"

    /** Kept apart from [NOTIFICATION_CHANNEL_INSTALL], which is deliberately quiet: this one carries
     *  the install confirmations that go nowhere until the user answers them, so it has to be seen. */
    const val NOTIFICATION_CHANNEL_INSTALL_CONFIRM = "install_confirm"

    const val NOTIFICATION_ID_SYNCING = 1
    const val NOTIFICATION_ID_UPDATES = 2
    const val NOTIFICATION_ID_DOWNLOADING = 3
    const val NOTIFICATION_ID_INSTALL = 4

    // New
    const val NOTIFICATION_ID_RB_DOWNLOAD = 5
    const val NOTIFICATION_ID_STATS_DOWNLOAD = 6
    const val NOTIFICATION_ID_INDEX_DOWNLOAD = 7
    const val NOTIFICATION_ID_INSTALL_CONFIRM = 8

    const val JOB_ID_SYNC = 1
}
