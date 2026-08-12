package com.looker.droidify.utility.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import androidx.core.app.NotificationCompat
import com.looker.droidify.R
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.Constants.NOTIFICATION_CHANNEL_INSTALL
import com.looker.droidify.utility.common.Constants.NOTIFICATION_ID_INSTALL

fun NotificationManager.installNotification(
    packageName: String,
    notification: Notification,
) {
    notify(
        installTag(packageName),
        NOTIFICATION_ID_INSTALL,
        notification,
    )
}

fun NotificationManager.removeInstallNotification(
    packageName: String,
) {
    cancel(installTag(packageName), NOTIFICATION_ID_INSTALL)
}

private fun installTag(name: String): String = "install-${name.trim().replace(' ', '_')}"

/** How long a "done" notification (installed, updated, uninstalled) stays before Android clears it on
 *  its own. Long enough to be read by someone who wasn't watching when it appeared, which is the normal
 *  case now that updates install by themselves, without leaving finished work sitting in the shade. */
const val SUCCESS_TIMEOUT = 10_000L

/**
 * @param isUpdate replacing a copy already on the device rather than installing something new. Only
 *  the wording changes: "Updating"/"Updated" instead of "Installing"/"Installed", so a notification
 *  that appears on its own (an automatic update, with nobody having pressed anything) says what
 *  actually happened. Failures stay worded as an installation whichever it was, since a failed update
 *  is a failed install and the message already names the app.
 */
fun Context.createInstallNotification(
    appName: String,
    state: InstallState,
    isUninstall: Boolean = false,
    isUpdate: Boolean = false,
    autoCancel: Boolean = true,
    block: NotificationCompat.Builder.() -> Unit = {},
): Notification {
    return NotificationCompat
        .Builder(this, NOTIFICATION_CHANNEL_INSTALL)
        .apply {
            setAutoCancel(autoCancel)
            setOngoing(false)
            setOnlyAlertOnce(true)
            setColor(Color.GREEN)
            val (title, text) = if (isUninstall) {
                setTimeoutAfter(SUCCESS_TIMEOUT)
                setSmallIcon(R.drawable.ic_delete)
                getString(R.string.uninstalled_application) to
                    getString(R.string.uninstalled_application_DESC, appName)
            } else {
                when (state) {
                    InstallState.Failed -> {
                        setSmallIcon(R.drawable.ic_bug_report)
                        getString(R.string.installation_failed) to
                            getString(R.string.installation_failed_DESC, appName)
                    }

                    InstallState.Pending -> {
                        setSmallIcon(R.drawable.ic_download)
                        getString(R.string.downloaded_FORMAT, appName) to
                            getString(R.string.tap_to_install_DESC)
                    }

                    InstallState.Installing -> {
                        setSmallIcon(R.drawable.ic_download)
                        setProgress(-1, -1, true)
                        getString(if (isUpdate) R.string.updating else R.string.installing) to
                            appName
                    }

                    InstallState.Installed -> {
                        setTimeoutAfter(SUCCESS_TIMEOUT)
                        setSmallIcon(R.drawable.ic_check)
                        getString(if (isUpdate) R.string.updated else R.string.installed) to
                            appName
                    }
                }
            }
            setContentTitle(title)
            setContentText(text)
            block()
        }
        .build()
}
