package com.looker.droidify.utility.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.looker.droidify.R
import com.looker.droidify.compose.MainComposeActivity
import com.looker.droidify.installer.ApkDownloads
import com.looker.droidify.network.DataSize
import com.looker.droidify.network.percentBy
import com.looker.droidify.utility.common.Constants.NOTIFICATION_CHANNEL_DOWNLOADING
import com.looker.droidify.utility.common.Constants.NOTIFICATION_ID_DOWNLOADING
import com.looker.droidify.utility.common.createNotificationChannel

/** The channel of every APK download notification. Created by whoever posts one first, so it only
 *  shows in the system's notification settings once the app has downloaded something. */
fun Context.createDownloadNotificationChannel() {
    createNotificationChannel(
        id = NOTIFICATION_CHANNEL_DOWNLOADING,
        name = getString(R.string.downloading),
        description = getString(R.string.download_channel_DESC),
    )
}

/** What is downloading from the apps' own pages: one app by name with its progress, or how many when
 *  there are several, with their combined progress once every size is known. */
fun Context.createApkDownloadNotification(downloads: Collection<ApkDownloads.Download>): Notification {
    val single = downloads.singleOrNull()
    val title = if (single != null) {
        getString(R.string.downloading_FORMAT, single.label)
    } else {
        resources.getQuantityString(R.plurals.downloading_apps_FORMAT, downloads.size, downloads.size)
    }
    val read = downloads.sumOf { it.status.read }
    val total = downloads.takeIf { all -> all.all { it.status.hasTotal } }?.sumOf { it.status.total }
    return downloadNotification(
        title = title,
        percent = total?.let { read percentBy it } ?: -1,
        text = total?.let { "${DataSize(read)} / ${DataSize(it)}" },
    )
}

/** A batch ("update all", "install all") at [title]'s step, with no byte counts of its own. */
fun Context.createBatchDownloadNotification(title: String): Notification =
    downloadNotification(title = title, percent = -1, text = null)

private fun Context.downloadNotification(title: String, percent: Int, text: String?): Notification =
    NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_DOWNLOADING)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setProgress(100, percent.coerceAtLeast(0), percent < 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                NOTIFICATION_ID_DOWNLOADING,
                Intent(this, MainComposeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()
