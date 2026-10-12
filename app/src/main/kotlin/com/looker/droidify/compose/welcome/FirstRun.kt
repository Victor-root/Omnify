package com.looker.droidify.compose.welcome

import android.content.Context
import android.content.SharedPreferences

/**
 * The "first_run" preferences: one-off flags written once per install, so that start-up work which must
 * not repeat (seeding, backfills) doesn't, and the flag below that says whether this install still has
 * to go through the welcome page.
 */
object FirstRun {
    private const val PREFS_NAME = "first_run"
    private const val KEY_WELCOME_PENDING = "welcome_pending"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Marks a brand-new install as owing the welcome page. Meant to run first thing at start-up, before
     * any one-off work writes its own flag here: an install that has ever run left at least one, so
     * empty preferences can only be a fresh install, and an app that is merely being updated never gets
     * the page. Running it again does nothing, since the flag itself makes the preferences non-empty, so
     * the answer survives the app being closed halfway through the page.
     */
    fun markIfFreshInstall(context: Context) {
        val prefs = prefs(context)
        if (prefs.all.isEmpty()) prefs.edit().putBoolean(KEY_WELCOME_PENDING, true).apply()
    }

    fun isWelcomePending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WELCOME_PENDING, false)

    fun finishWelcome(context: Context) {
        prefs(context).edit().putBoolean(KEY_WELCOME_PENDING, false).apply()
    }
}
