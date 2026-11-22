package com.looker.droidify.installer

import android.content.Context
import androidx.core.content.edit
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import kotlin.time.Duration.Companion.minutes

/**
 * Omnify installing an update of itself: Android stops it to replace it, and nothing starts it again.
 *
 * An installer running its commands as shell or root (Shizuku, root) starts it again itself, with
 * [relaunchCommand]. One going through Android's own installer cannot, as a stopped app may not put
 * itself on screen: it calls [expectNotification] instead, and the new version offers, from a
 * notification, to open it (see [com.looker.droidify.receivers.SelfUpdatedReceiver]).
 */
object SelfUpdate {

    fun isSelf(context: Context, item: InstallItem): Boolean = item.packageName.name == context.packageName

    /** Notes that an update of Omnify is starting through an installer that cannot start it again. */
    fun expectNotification(context: Context) {
        context.prefs().edit { putLong(KEY_EXPECTED_AT, System.currentTimeMillis()) }
        DiagnosticLog.log("self-update: through Android's installer, the new version is to offer opening it")
    }

    /**
     * Whether the update just installed is the one [expectNotification] noted, which is then forgotten.
     * Not for one noted long ago that never went through: a later update from elsewhere (another store,
     * a cable) owes the user nothing.
     */
    fun takeExpectedNotification(context: Context): Boolean {
        val prefs = context.prefs()
        val expectedAt = prefs.getLong(KEY_EXPECTED_AT, 0L)
        prefs.edit { remove(KEY_EXPECTED_AT) }
        return System.currentTimeMillis() - expectedAt < EXPECTED_FOR.inWholeMilliseconds
    }

    /**
     * A shell command starting Omnify again once its update is in place, to run just before the install
     * from the same shell. Detached from that shell, which goes with Omnify: it waits for the package's
     * path to change, as each install puts it in a new folder, then for the old Omnify to be stopped,
     * then opens the app, again while Android refuses. Null when Omnify has no screen to open.
     *
     * The wait for the old Omnify is what made it come back only now and then: Android puts the new
     * version in place before it stops the old one, and opened in between, the old one only came back to
     * the front, to be stopped right after with nothing started in its place.
     */
    fun relaunchCommand(context: Context): String? = buildRelaunchCommand(context).also {
        DiagnosticLog.log("self-update: through a shell, started again once installed: ${it != null}")
    }

    private fun buildRelaunchCommand(context: Context): String? {
        val packageManager = context.packageManager
        val component = (
            packageManager.getLaunchIntentForPackage(context.packageName)
                ?: packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
            )?.component?.flattenToShortString() ?: return null
        val packageName = context.packageName
        // Only plain names go into the script, which is single-quoted as a whole.
        if (!SHELL_SAFE.matches(packageName) || !SHELL_SAFE.matches(component)) return null
        // The script's own progress, in the builds keeping a diagnostic log: it runs outside Omnify, so
        // it writes to a file the next run copies into that log (see DiagnosticLog.scriptTraceFile).
        val traceFile = DiagnosticLog.scriptTraceFile(context)?.absolutePath?.takeIf { SHELL_SAFE.matches(it) }
        val trace = { message: String -> traceFile?.let { "echo \"\$(date +%T) $message\" >> $it; " }.orEmpty() }
        val script = "before=\$(pm path $packageName); old=\$(pidof $packageName); " +
            trace("waiting, running as \$old") + "i=0; " +
            "while [ \$i -lt $WAIT_STEPS ]; do sleep 0.5; now=\$(pm path $packageName); " +
            "if [ -n \"\$now\" ] && [ \"\$now\" != \"\$before\" ]; then " + trace("installed after \$i steps") +
            "for o in \$old; do k=0; " +
            "while [ -d /proc/\$o ] && [ \$k -lt $EXIT_STEPS ]; do sleep 0.25; k=\$((k + 1)); done; done; " +
            trace("old one gone") + "a=0; " +
            "while [ \$a -lt $START_TRIES ]; do started=\$(am start -W -n $component 2>&1); " +
            "case \"\$started\" in *Error*|*Exception*) a=\$((a + 1)); sleep 0.5;; " +
            "*) " + trace("started: \$(echo \$started)") + "exit;; esac; done; " +
            trace("not started: \$(echo \$started)") + "exit; fi; " +
            "i=\$((i + 1)); done; " + trace("gave up")
        return "\$(command -v setsid || echo nohup) sh -c '$script' >/dev/null 2>&1 </dev/null &"
    }

    private fun Context.prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "self_update"
    private const val KEY_EXPECTED_AT = "notification_expected_at"
    private val EXPECTED_FOR = 30.minutes

    /** How many half-second looks at the package before giving up: a failed install changes nothing. */
    private const val WAIT_STEPS = 240

    /** Quarter seconds given the old Omnify to be stopped once the new one is in place. */
    private const val EXIT_STEPS = 40

    /** Times the app is opened, half a second apart, while Android refuses it. */
    private const val START_TRIES = 8
    private val SHELL_SAFE = Regex("[A-Za-z0-9._/]+")
}
