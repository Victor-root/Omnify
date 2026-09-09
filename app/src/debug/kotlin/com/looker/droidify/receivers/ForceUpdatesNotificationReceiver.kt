package com.looker.droidify.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.looker.droidify.data.PendingUpdates
import com.looker.droidify.utility.notifications.showUpdatesAvailableNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only testing aid: posts the real "updates available" notification for whatever is currently
 * pending, on demand, from a plain adb command, rather than waiting for the 12-hour periodic sync that
 * is the only thing that ever posts it for real (and skips doing so when auto-update is on, or the
 * setting for it is off).
 *
 * Not part of any real flow: reuses the same notification-building code the periodic sync calls, so
 * what shows up carries the exact same content and the exact same tap target a real one would.
 *
 * Only in the debug source set, never compiled into beta/canary/release, so the exported receiver this
 * needs (adb's shell process is a different app to Android, and cannot reach an unexported one) never
 * ships. Remove once no longer needed.
 *
 *     adb shell am broadcast -a com.omnify.vroot.debug.FORCE_UPDATES_NOTIFICATION
 */
@AndroidEntryPoint
class ForceUpdatesNotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var pendingUpdates: PendingUpdates

    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                context.showUpdatesAvailableNotification(pendingUpdates.allAsEntries())
            } finally {
                result.finish()
            }
        }
    }
}
