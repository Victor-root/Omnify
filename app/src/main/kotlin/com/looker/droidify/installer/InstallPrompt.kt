package com.looker.droidify.installer

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AndroidRuntimeException
import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.R
import com.looker.droidify.utility.common.Constants.NOTIFICATION_CHANNEL_INSTALL_CONFIRM
import com.looker.droidify.utility.common.createNotificationChannel
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.getPackageName
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.common.log
import com.looker.droidify.utility.notifications.createInstallConfirmationNotification
import com.looker.droidify.utility.notifications.installConfirmationNotification
import com.looker.droidify.utility.notifications.removeInstallConfirmationNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts Android's install confirmation in front of the user, whether or not Omnify is what they are
 * currently looking at.
 *
 * PackageInstaller answers a commit it wants confirmed with STATUS_PENDING_USER_ACTION and a screen
 * for us to show, and showing it is an activity start. Since Android 10 a background app is not
 * allowed to start one: the call is dropped without an exception, so the confirmation never appears,
 * the session waits on an answer nobody was ever asked for, and the install reads as running until it
 * times out ten minutes later. Leaving Omnify while an update installed was enough to hit that, and
 * coming back did not help, because the one confirmation Android handed over had already been spent.
 *
 * So the confirmation is started directly only while Omnify is on screen. Otherwise it is held here
 * and offered as a notification, whose tap is an activity start the system performs on our behalf,
 * and re-offered as soon as the user is back in Omnify. Either way the same confirmation is still
 * waiting for them rather than lost.
 */
@Singleton
class InstallPrompt @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Confirmations handed over by PackageInstaller that nobody has answered yet, keyed by package
     * name. Written from the install receiver, read when Omnify comes back to the foreground.
     */
    private val pendingPrompts = ConcurrentHashMap<String, Intent>()

    /**
     * Started (not yet stopped) activities. Anything above zero means Omnify is on screen, which is
     * the condition under which starting the confirmation ourselves actually works.
     */
    private val startedActivities = AtomicInteger(0)

    private val isForeground: Boolean get() = startedActivities.get() > 0

    /** Wires up the foreground tracking. Called once, from the application's onCreate. */
    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(
            @Suppress("EmptyFunctionBlock")
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

                override fun onActivityStarted(activity: Activity) {
                    startedActivities.incrementAndGet()
                }

                // On resume rather than on start: this is the point Omnify is unambiguously the app
                // in front of the user, so the confirmation it starts is one the system will show.
                override fun onActivityResumed(activity: Activity) {
                    showNextPending()
                }

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {
                    startedActivities.decrementAndGet()
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {}
            },
        )
    }

    /**
     * Offers [promptIntent], the confirmation screen PackageInstaller handed back for [packageName].
     *
     * [packageName] is null only when PackageInstaller reports a session it can't name, which leaves
     * nothing to hold the confirmation under and nothing to name in a notification; the confirmation
     * is then only worth the direct attempt.
     */
    fun offer(packageName: String?, promptIntent: Intent) {
        // Required: this is started from outside an activity, so there is no task to start it in.
        promptIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName == null) {
            start(promptIntent)
            return
        }
        if (isForeground && start(promptIntent)) {
            clear(packageName)
            return
        }
        pendingPrompts[packageName] = promptIntent
        notifyPending(packageName, promptIntent)
    }

    /**
     * Drops whatever is held for [packageName]. Called when its install reaches an end, whichever
     * one: a confirmation outlives the session it belongs to by nothing, so a notification still
     * offering it would only lead to a screen the system has already dismissed.
     */
    fun clear(packageName: String) {
        pendingPrompts.remove(packageName)
        context.notificationManager?.removeInstallConfirmationNotification(packageName)
    }

    private fun showNextPending() {
        // One at a time: a confirmation takes the whole screen, so starting a second would replace
        // the first before it could be answered. The rest keep their notifications and their turn.
        val next = pendingPrompts.entries.firstOrNull() ?: return
        if (start(next.value)) clear(next.key)
    }

    private fun start(promptIntent: Intent): Boolean = try {
        context.startActivity(promptIntent)
        true
    } catch (e: ActivityNotFoundException) {
        // A start blocked for being in the background doesn't throw, it is simply dropped, so this
        // only covers a confirmation whose session the system has already taken down.
        onStartFailed(e)
    } catch (e: SecurityException) {
        onStartFailed(e)
    } catch (e: AndroidRuntimeException) {
        onStartFailed(e)
    }

    private fun onStartFailed(error: Exception): Boolean {
        log("Couldn't show install confirmation: ${error.message}", TAG, Log.WARN)
        return false
    }

    private fun notifyPending(packageName: String, promptIntent: Intent) {
        val notificationManager = context.notificationManager ?: return
        context.createNotificationChannel(
            id = NOTIFICATION_CHANNEL_INSTALL_CONFIRM,
            name = context.getString(R.string.confirm_install_channel),
            description = context.getString(R.string.confirm_install_channel_DESC),
            importance = NotificationManager.IMPORTANCE_HIGH,
        )
        val appName = context.packageManager.getPackageName(packageName)?.toString() ?: packageName
        val contentIntent = PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            promptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notificationManager.installConfirmationNotification(
            packageName = packageName,
            notification = context.createInstallConfirmationNotification(
                appName = appName,
                contentIntent = contentIntent,
                // The copy on the device is still the old one at this point, the confirmation being
                // exactly what stands between it and the new one.
                isUpdate = context.packageManager.getPackageInfoCompat(packageName) != null,
            ),
        )
        if (BuildConfig.DEBUG) {
            log("Held install confirmation for $packageName", TAG, Log.INFO)
        }
    }

    private companion object {
        const val TAG = "InstallPrompt"
    }
}
