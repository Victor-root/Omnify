package com.looker.droidify.utility.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import com.looker.droidify.compose.MainComposeActivity
import com.looker.droidify.R
import com.looker.droidify.utility.common.Constants
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.createNotificationChannel
import com.looker.droidify.utility.common.extension.getColorFromAttr
import com.looker.droidify.utility.common.extension.notificationManager
import android.R as AndroidR

private const val MAX_UPDATE_NOTIFICATION = 5

/** One line of the "updates available" notification: an app's name and the version being offered.
 *  Catalogue apps and external sources both reduce to this, since the notification only ever shows
 *  those two things. */
data class UpdateEntry(val name: String, val version: String)

fun updatesAvailableNotification(
    context: Context,
    updates: List<UpdateEntry>,
) = NotificationCompat
    .Builder(context, Constants.NOTIFICATION_CHANNEL_UPDATES)
    .setSmallIcon(R.drawable.ic_new_releases)
    .setContentTitle(context.getString(R.string.new_updates_available))
    .setAutoCancel(true)
    .setContentText(
        context.resources.getQuantityString(
            R.plurals.new_updates_DESC_FORMAT,
            updates.size,
            updates.size,
        ),
    )
    .setColor(
        ContextThemeWrapper(context, R.style.Theme_Main_Light)
            .getColorFromAttr(AndroidR.attr.colorPrimary).defaultColor,
    )
    .setContentIntent(
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainComposeActivity::class.java).setAction(MainComposeActivity.ACTION_UPDATES),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )
    .setStyle(
        NotificationCompat.InboxStyle().also {
            for (update in updates.take(MAX_UPDATE_NOTIFICATION)) {
                it.addLine("${update.name} ${update.version}")
            }
            if (updates.size > MAX_UPDATE_NOTIFICATION) {
                val summary =
                    context.getString(
                        R.string.plus_more_FORMAT,
                        updates.size - MAX_UPDATE_NOTIFICATION,
                    )
                if (SdkCheck.isNougat) {
                    it.addLine(summary)
                } else {
                    it.setSummaryText(summary)
                }
            }
        },
    )
    .build()

/** Posts the "updates available" notification, or clears it when [updates] is empty (they were
 *  installed, or turned out not to be updates after all). The channel is created here rather than at
 *  startup, so it only appears in the system's notification settings once the app has something to say. */
fun Context.showUpdatesAvailableNotification(updates: List<UpdateEntry>) {
    val manager = notificationManager ?: return
    if (updates.isEmpty()) {
        manager.cancel(Constants.NOTIFICATION_ID_UPDATES)
        return
    }
    createNotificationChannel(
        id = Constants.NOTIFICATION_CHANNEL_UPDATES,
        name = getString(R.string.updates),
        showBadge = true,
    )
    manager.notify(Constants.NOTIFICATION_ID_UPDATES, updatesAvailableNotification(this, updates))
}
