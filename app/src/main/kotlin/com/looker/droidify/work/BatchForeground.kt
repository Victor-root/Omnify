package com.looker.droidify.work

import android.util.Log
import androidx.annotation.StringRes
import androidx.work.CoroutineWorker
import com.looker.droidify.utility.common.Constants.NOTIFICATION_ID_BATCH_DOWNLOAD
import com.looker.droidify.utility.common.extension.exceptCancellation
import com.looker.droidify.utility.common.toForegroundInfo
import com.looker.droidify.utility.notifications.createBatchDownloadNotification
import com.looker.droidify.utility.notifications.createDownloadNotificationChannel

/**
 * Shows a batch's [position] of [count] in a notification and puts the worker in the foreground with
 * it, which is what lets the batch finish once the app is out of sight instead of being held back or
 * stopped by Android to save battery. Called again at every step to move the count along.
 *
 * Refused when the batch only starts after the app has gone to the background, or when Android's
 * daily allowance for this kind of work is spent: the batch then runs on unprotected, as it used to.
 */
internal suspend fun CoroutineWorker.showBatchStep(@StringRes title: Int, position: Int, count: Int) {
    try {
        applicationContext.createDownloadNotificationChannel()
        val notification = applicationContext.createBatchDownloadNotification(
            applicationContext.getString(title, position, count),
        )
        setForeground(notification.toForegroundInfo(NOTIFICATION_ID_BATCH_DOWNLOAD))
    } catch (e: Exception) {
        e.exceptCancellation()
        Log.w("BatchForeground", "Not in the foreground: ${e.message}")
    }
}
