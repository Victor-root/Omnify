package com.looker.droidify.data

import android.util.Log
import com.looker.droidify.BuildConfig

/** The one tag this trail carries, so Logcat can be filtered on it alone. */
internal const val REPO_ICON_TAG = "OmnifyIcon"

/**
 * Temporary: follows a repository's logo from the index that declares it, through the row it is
 * stored in, to the URL a screen ends up showing.
 *
 * A repository showing a logo its owner doesn't recognise has several explanations and the screen
 * tells them apart from none of them: the index declares that logo (fdroidserver's default is a QR
 * code of the repository address), the database still holds what an earlier sync put there, the
 * address and the file name join into a URL that leads nowhere, or the screen put the icon of the
 * repository's single app in front of the logo. Each of those is one line here.
 *
 * Debug builds only, and inline, so nothing it looks up is even computed in a release build. Remove
 * it once the reports stop.
 */
internal inline fun trailRepoIcon(message: () -> String) {
    if (BuildConfig.DEBUG) Log.d(REPO_ICON_TAG, message())
}
