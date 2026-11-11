package com.looker.droidify.receivers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.looker.droidify.R
import com.looker.droidify.installer.SelfUpdate
import com.looker.droidify.utility.common.Constants.NOTIFICATION_CHANNEL_SELF_UPDATED
import com.looker.droidify.utility.common.Constants.NOTIFICATION_ID_SELF_UPDATED
import com.looker.droidify.utility.common.createNotificationChannel
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.notifications.createSelfUpdatedNotification

/**
 * Run by the new version once Omnify has been updated. When Omnify updated itself through Android's own
 * installer, which stopped it and may not start it again (see [SelfUpdate]), offers to open it.
 */
class SelfUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!SelfUpdate.takeExpectedNotification(context)) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            ?: return
        val notificationManager = context.notificationManager ?: return
        // Its own channel, shown at once: the install one is quiet, and this lands in the silent ones
        // there while the user is waiting on Omnify to come back.
        context.createNotificationChannel(
            id = NOTIFICATION_CHANNEL_SELF_UPDATED,
            name = context.getString(R.string.self_updated_channel),
            importance = NotificationManager.IMPORTANCE_HIGH,
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notificationManager.notify(
            NOTIFICATION_ID_SELF_UPDATED,
            context.createSelfUpdatedNotification(contentIntent),
        )
    }
}
