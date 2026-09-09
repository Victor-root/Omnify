package com.looker.droidify.compose

import android.util.Log
import com.looker.droidify.BuildConfig

/** The one tag this trail carries, so Logcat can be filtered on it alone. */
internal const val UPDATES_NAV_TAG = "OmnifyUpdatesNav"

/**
 * Temporary: follows the "updates available" notification from the intent Android delivers, through
 * [MainComposeActivity]'s deeplink handling, to the tab the app list screen actually lands on.
 *
 * Reported: tapping the notification after leaving Omnify backgrounded for a while reopens on
 * whichever screen was on top when it was left, not the Updates tab the notification promised. The
 * handling itself (request the tab, navigate to the app list) looks right reading it, so this follows
 * where an already-quiet failure could be hiding: whether onNewIntent even fires, which branch
 * handleDeeplink takes, whether it throws (its catch block discards the exception outright), and
 * whether the app list screen that ends up on screen ever reads the request at all.
 *
 * Debug builds only, and inline, so nothing it looks up is even computed in a release build. Remove
 * it once the report is understood.
 */
internal inline fun trailUpdatesNav(message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(UPDATES_NAV_TAG, message())
}
