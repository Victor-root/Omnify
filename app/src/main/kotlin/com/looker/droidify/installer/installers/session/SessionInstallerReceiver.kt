package com.looker.droidify.installer.installers.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.looker.droidify.R
import com.looker.droidify.data.model.toPackageName
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.utility.common.Constants.NOTIFICATION_CHANNEL_INSTALL
import com.looker.droidify.utility.common.createNotificationChannel
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.getPackageName
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.common.log
import com.looker.droidify.utility.notifications.createInstallNotification
import com.looker.droidify.utility.notifications.installNotification
import com.looker.droidify.utility.notifications.removeInstallNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SessionInstallerReceiver : BroadcastReceiver() {

    // This is a cyclic dependency injection, I know but this is the best option for now
    @Inject
    lateinit var installManager: InstallManager

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android is asking the user to confirm this install and has handed us the screen to
            // show. Put it in front of them as-is. FLAG_ACTIVITY_NEW_TASK is required: this runs in
            // a receiver, which has no task of its own to start an activity in.
            //
            // Nothing is added to it. This used to attach EXTRA_NOT_UNKNOWN_SOURCE and an
            // EXTRA_INSTALLER_PACKAGE_NAME of "com.android.vending", claiming the install came from
            // the Play Store so the confirmation would drop its unknown-source wording. Both belong
            // to ACTION_INSTALL_PACKAGE, the install flow deprecated in Android 10 and replaced by
            // the PackageInstaller sessions this app uses, so on the confirmation intent for a
            // session they were read by nothing: the system attributes an install to whoever opened
            // the session, which is why a device installing through here says "Omnify" and not the
            // Play Store. Dead either way, and had they worked they would have told the user
            // something untrue about where their apps come from.
            val promptIntent: Intent? =
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)

            promptIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } else {
            notifyStatus(intent, context)
        }
    }

    private fun notifyStatus(intent: Intent, context: Context) {
        val packageManager = context.packageManager
        val notificationManager = context.notificationManager

        context.createNotificationChannel(
            id = NOTIFICATION_CHANNEL_INSTALL,
            name = context.getString(R.string.install),
        )

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val isUninstall = intent.getBooleanExtra(ACTION_UNINSTALL, false)

        // Surface the exact PackageInstaller reason (e.g. INSTALL_FAILED_NO_MATCHING_ABIS,
        // INSTALL_FAILED_VERIFICATION_FAILURE) so failures can be diagnosed from logcat.
        log("Install result: package=$packageName status=$status message=$message", TAG, Log.INFO)

        val appName = packageManager.getPackageName(packageName)

        if (packageName != null) {
            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    notificationManager?.removeInstallNotification(packageName)
                    val notification = context.createInstallNotification(
                        appName = (appName ?: packageName.substringAfterLast('.')).toString(),
                        state = InstallState.Installed,
                        isUninstall = isUninstall,
                        isUpdate = !isUninstall && packageManager.replacedAnExistingCopy(packageName),
                    )
                    notificationManager?.installNotification(
                        packageName = packageName,
                        notification = notification,
                    )
                }

                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    notificationManager?.removeInstallNotification(packageName)
                    installManager.setFailed(packageName.toPackageName())
                }

                else -> {
                    installManager.remove(packageName.toPackageName())
                    // A signature conflict (STATUS_FAILURE_CONFLICT, "signatures do not match") means
                    // the app is already installed from a different source/signer. Android can't
                    // update across signers, so the raw message is useless to the user — tell them to
                    // uninstall the existing copy first, both as a toast (they're usually still in the
                    // app) and in the notification.
                    val isSignatureConflict =
                        status == PackageInstaller.STATUS_FAILURE_CONFLICT &&
                            message?.contains("signature", ignoreCase = true) == true
                    val shownMessage = when {
                        isSignatureConflict -> context.getString(
                            R.string.install_failed_signature_mismatch,
                            (appName ?: packageName).toString(),
                        )
                        // A failed uninstall (e.g. a system app: DELETE_FAILED_INTERNAL_ERROR) would
                        // otherwise fail silently and the user would just retry — say so clearly.
                        isUninstall -> context.getString(
                            R.string.uninstall_failed,
                            (appName ?: packageName).toString(),
                        )
                        else -> message
                    }
                    if (isSignatureConflict || isUninstall) {
                        Toast.makeText(context, shownMessage, Toast.LENGTH_LONG).show()
                    }
                    val notification = context.createInstallNotification(
                        appName = appName.toString(),
                        state = InstallState.Failed,
                        isUninstall = isUninstall,
                    ) {
                        setContentText(shownMessage)
                    }
                    notificationManager?.installNotification(
                        packageName = packageName,
                        notification = notification,
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_UNINSTALL = "action_uninstall"

        private const val TAG = "SessionInstaller"
    }
}

/**
 * Whether the install that just succeeded replaced a copy already on the device, rather than putting
 * a new app there.
 *
 * This runs after the fact, so it can't ask whether the package is installed: it always is by now.
 * Android keeps the answer itself, though. It stamps firstInstallTime once and only once, and moves
 * lastUpdateTime on every install after that, so the two matching means nothing has replaced the
 * original yet. Reading it back beats threading a flag through the install for the same reason: no
 * state to keep in step, and nothing to go stale if a result arrives late.
 */
private fun PackageManager.replacedAnExistingCopy(packageName: String): Boolean {
    val info = getPackageInfoCompat(packageName) ?: return false
    return info.lastUpdateTime > info.firstInstallTime
}
