package com.looker.droidify.utility.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.looker.droidify.BuildConfig
import com.looker.droidify.data.model.Repo
import com.looker.droidify.model.Repository
import com.looker.droidify.utility.common.SdkCheck
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log kept in the app's own storage, which a person can open, copy and share from the home
 * header (see DiagnosticLogDialog). Only in the builds where [BuildConfig.DIAGNOSTICS] is on, the test
 * build handed to someone who reported a bug and the debug build: everywhere else every call returns at
 * once, and the constant lets R8 drop the code behind it.
 *
 * Every line is written to the file as it comes, so what led up to a crash is still there after one,
 * native ones included, which no in-memory log survives.
 *
 * It is shared by people who are not developers, so nothing personal goes in: no app names, no address
 * of a repository the person added themselves ([repoLabel]), no account, token or file path, and of an
 * error only its type, since a message can quote a URL.
 */
object DiagnosticLog {

    private const val TAG = "DiagnosticLog"
    private const val FILE_NAME = "diagnostics.log"
    private const val PREFS_NAME = "diagnostics"
    private const val KEY_LAST_EXIT = "last_reported_exit"

    /** Beyond this the oldest half goes, so the file never grows without bound. */
    private const val MAX_BYTES = 256 * 1024

    /** How much of a crash is written: the causes followed, and the frames kept of each. */
    private const val MAX_CAUSES = 4
    private const val MAX_FRAMES = 30

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    /** Opens the log for this run of the app, and records how the previous runs ended. */
    fun init(context: Context) {
        if (!BuildConfig.DIAGNOSTICS) return
        file = File(context.filesDir, FILE_NAME)
        log(
            "--- start pid=${Process.myPid()} ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}), " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        if (SdkCheck.isR) logPreviousExits(context)
        logCrashes()
    }

    /**
     * Writes a crash that is about to end the app into the log, then lets it end the app as before.
     * Android only keeps the trace of a crash somewhere a person needs a computer to read; written here,
     * it is in the log that gets shared. Only a Java or Kotlin crash can be caught this way: a native one
     * kills the app without running any code, and is still reported at the next start (see
     * [logPreviousExits]).
     */
    private fun logCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { log("CRASH in thread ${thread.name}\n${describe(error)}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * An error as its types and the places in the code it passed through, never its message: a message
     * can quote an address, a file name or what someone typed. The chain of causes is followed, since
     * the one that matters is often the last.
     */
    private fun describe(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            append(if (depth == 0) "" else "caused by ").append(current.javaClass.name).append('\n')
            current.stackTrace.take(MAX_FRAMES).forEach { frame ->
                append("    at ").append(frame.className).append('.').append(frame.methodName)
                    .append('(').append(frame.fileName).append(':').append(frame.lineNumber).append(")\n")
            }
            current = current.cause?.takeIf { it !== current }
            depth++
        }
    }.trimEnd()

    fun log(message: String) {
        if (!BuildConfig.DIAGNOSTICS) return
        if (BuildConfig.TRACES) Log.d(TAG, message)
        val target = file ?: return
        synchronized(lock) {
            runCatching {
                if (target.length() > MAX_BYTES) dropOldestHalf(target)
                target.appendText("${formatTime(System.currentTimeMillis())} [${Thread.currentThread().name}] $message\n")
            }
        }
    }

    /** The whole log, oldest line first; empty when there is none. */
    fun read(): String {
        if (!BuildConfig.DIAGNOSTICS) return ""
        return synchronized(lock) {
            runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
        }
    }

    /** Empties the log, so what follows is only what happens while reproducing a bug. */
    fun clear() {
        if (!BuildConfig.DIAGNOSTICS) return
        synchronized(lock) { runCatching { file?.writeText("") } }
        log("--- cleared")
    }

    /** How [repo] appears in the log: its number, and its address only when it is one Omnify ships
     *  with, public by nature. One the person added could be private, so it is never named. */
    fun repoLabel(repo: Repo): String {
        val address = repo.address.trimEnd('/')
        val shown = if (address in Repository.defaultAddresses) address else "own repository"
        return "repo #${repo.id} ($shown)"
    }

    /** SimpleDateFormat is not safe across threads, hence the lock. */
    private fun formatTime(millis: Long): String = synchronized(lock) { timeFormat.format(Date(millis)) }

    private fun dropOldestHalf(target: File) {
        val text = target.readText()
        val cut = text.indexOf('\n', text.length / 2)
        target.writeText(if (cut < 0) "" else text.substring(cut + 1))
    }

    /** Android keeps how each of the last runs ended (a crash, a native crash, the system reclaiming
     *  memory...). Each one is reported once, at the first start after it. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun logPreviousExits(context: Context) {
        val activityManager = context.getSystemService<ActivityManager>() ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastReported = prefs.getLong(KEY_LAST_EXIT, 0L)
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        }.getOrNull().orEmpty().filter { it.timestamp > lastReported }
        exits.sortedBy { it.timestamp }.forEach { exit ->
            log(
                "previous run pid=${exit.pid} ended ${formatTime(exit.timestamp)}: " +
                    "${exitReason(exit.reason)}, status=${exit.status}",
            )
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit { putLong(KEY_LAST_EXIT, it) } }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited by itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        else -> "reason $reason"
    }
}
