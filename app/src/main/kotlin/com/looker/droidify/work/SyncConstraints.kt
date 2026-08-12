package com.looker.droidify.work

import androidx.work.Constraints
import androidx.work.NetworkType
import com.looker.droidify.datastore.model.AutoSync

/**
 * The conditions background work should wait for, from the user's "Sync repositories automatically"
 * choice.
 *
 * Only [AutoSync.NEVER] used to have any effect: every other option fell through to the same "any
 * connection" schedule, so picking Wi-Fi only still synced over mobile data, and a code comment said as
 * much ("simplified to connected for now"). The three remaining options now differ as their labels
 * promise. [AutoSync.NEVER] never reaches here, since it cancels the scheduled work outright.
 *
 * Unmetered rather than "is it literally Wi-Fi": that is Android's own notion of a connection that
 * doesn't cost anything to use, so a metered hotspot the user has marked as such is respected, and a
 * free tethered link isn't refused for the wrong reason.
 *
 * This governs *scheduled* work only. A sync the user asked for by pressing the button runs whatever
 * the connection is, since the setting is about what happens on its own.
 *
 * @param requiresBatteryNotLow for work that downloads and installs rather than just reading an index,
 *  which is worth holding back on a nearly-flat battery.
 */
internal fun AutoSync.workConstraints(requiresBatteryNotLow: Boolean = false): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(
            when (this) {
                AutoSync.WIFI_ONLY, AutoSync.WIFI_PLUGGED_IN -> NetworkType.UNMETERED
                AutoSync.ALWAYS, AutoSync.NEVER -> NetworkType.CONNECTED
            },
        )
        .setRequiresCharging(this == AutoSync.WIFI_PLUGGED_IN)
        .setRequiresBatteryNotLow(requiresBatteryNotLow)
        .build()
