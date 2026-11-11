package com.looker.droidify.work

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * When Omnify last looked for updates, which tells whether opening the app has a missed check to make up
 * for and whether background checks seem to have stopped running.
 *
 * Apart from [com.looker.droidify.datastore.Settings], which travels in backups and transfers: these
 * times only mean something on the device they were taken on.
 */
@Singleton
class UpdateCheckHistory @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCheckDue(interval: Duration): Boolean =
        isCheckDue(prefs.time(KEY_LAST_CHECK), interval, System.currentTimeMillis())

    /** When the last check finished, as it changes; null while none has. */
    val lastCheck: Flow<Long?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAST_CHECK) trySend(prefs.time(KEY_LAST_CHECK))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(prefs.time(KEY_LAST_CHECK))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun checkFinished() {
        prefs.edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
    }

    /** Whether to warn that background checks seem to have stopped: not once the warning was dismissed,
     *  until a background check has run again and stopped afresh. */
    fun isBackgroundStalled(interval: Duration): Boolean {
        val lastRun = prefs.time(KEY_LAST_BACKGROUND_RUN) ?: return false
        return lastRun != prefs.time(KEY_DISMISSED_FOR) &&
            isBackgroundStalled(lastRun, interval, System.currentTimeMillis())
    }

    fun backgroundRan() {
        prefs.edit { putLong(KEY_LAST_BACKGROUND_RUN, System.currentTimeMillis()) }
    }

    /**
     * Starts the wait for the next background check. Only [afresh] restarts one under way, for a schedule
     * that has just changed: otherwise it is for an install or an update with no run on record yet, so
     * the wait isn't counted from a run that never happened.
     */
    fun expectBackgroundRun(afresh: Boolean) {
        if (afresh || !prefs.contains(KEY_LAST_BACKGROUND_RUN)) backgroundRan()
    }

    fun dismissBackgroundWarning() {
        prefs.time(KEY_LAST_BACKGROUND_RUN)?.let { prefs.edit { putLong(KEY_DISMISSED_FOR, it) } }
    }

    /** Whether to say that background activity isn't [allowed]. A dismissal lasts until it is allowed,
     *  so that losing it again is said afresh. */
    fun isActivityWarningDue(allowed: Boolean): Boolean {
        if (allowed) prefs.edit { remove(KEY_ACTIVITY_WARNING_DISMISSED) }
        return !allowed && !prefs.getBoolean(KEY_ACTIVITY_WARNING_DISMISSED, false)
    }

    fun dismissActivityWarning() {
        prefs.edit { putBoolean(KEY_ACTIVITY_WARNING_DISMISSED, true) }
    }

    private fun SharedPreferences.time(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private companion object {
        const val PREFS_NAME = "update_checks"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_LAST_BACKGROUND_RUN = "last_background_run"
        const val KEY_DISMISSED_FOR = "dismissed_for"
        const val KEY_ACTIVITY_WARNING_DISMISSED = "activity_warning_dismissed"
    }
}

/** How old the last full check may be when the app is opened before opening it checks again: just
 *  enough that leaving for another app and coming straight back doesn't check all over again. */
val OPENING_CHECK_AGE = 1.minutes

/** Whether a check is owed: one has never finished here, or the last is at least [interval] old. */
internal fun isCheckDue(lastCheck: Long?, interval: Duration, now: Long): Boolean =
    lastCheck == null || now - lastCheck >= interval.inWholeMilliseconds

/**
 * Whether background checks seem to have stopped: two have been missed, plus a margin for what can
 * rightly hold one back for a while, like Android's idle mode or waiting for Wi-Fi. Never under
 * [MIN_STALL], as Android can limit an app it sees little of to about one job a day, however often
 * the app asks.
 */
internal fun isBackgroundStalled(lastRun: Long, interval: Duration, now: Long): Boolean =
    now - lastRun >= maxOf(interval * 2 + STALL_MARGIN, MIN_STALL).inWholeMilliseconds

private val STALL_MARGIN = 12.hours
private val MIN_STALL = 24.hours
