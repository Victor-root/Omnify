package com.looker.droidify.installer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.looker.droidify.utility.common.Constants.NOTIFICATION_ID_DOWNLOADING
import com.looker.droidify.utility.common.extension.notificationManager
import com.looker.droidify.utility.common.extension.startForegroundSafe
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import com.looker.droidify.utility.notifications.createApkDownloadNotification
import com.looker.droidify.utility.notifications.createDownloadNotificationChannel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps [ApkDownloads] alive while the app is out of sight: a foreground service is Android's own way of
 * being told work the user asked for is under way, so the process is neither frozen nor killed while
 * an APK is still coming in. It does no work of its own, only shows the downloads' progress, and stops
 * the moment none is left.
 */
@AndroidEntryPoint
class ApkDownloadService : Service() {

    @Inject
    lateinit var downloads: ApkDownloads

    private val scope = MainScope()
    private var watching: Job? = null

    /** The latest start this service was given: stopping with it is refused when a newer start is on
     *  its way, a download begun just as the last one ended. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createDownloadNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Always, even with nothing left to show: a service started for the foreground that does not
        // get there in time takes the whole app down.
        val active = downloads.active.value.values
        if (!startForegroundSafe(NOTIFICATION_ID_DOWNLOADING, createApkDownloadNotification(active))) {
            // Android refused (its daily allowance for this kind of work is spent): the downloads go on,
            // just without the notification keeping them alive.
            DiagnosticLog.log("Download notification refused by Android: downloads run unprotected")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (watching == null) {
            DiagnosticLog.log("Download notification shown, ${active.size} download(s) running")
            watching = scope.launch { followDownloads() }
        }
        return START_NOT_STICKY
    }

    private suspend fun followDownloads() {
        downloads.active.collect { active ->
            if (active.isEmpty()) {
                DiagnosticLog.log("Download notification removed, no download left")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(lastStartId)
            } else {
                notificationManager?.notify(NOTIFICATION_ID_DOWNLOADING, createApkDownloadNotification(active.values))
                // Android drops notification updates sent faster than a few a second.
                delay(NOTIFICATION_INTERVAL_MS)
            }
        }
    }

    /** Android 15 and later: the time allowed to this kind of foreground work is up. Leaving now is
     *  required; the downloads themselves carry on unprotected. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLog.log("Download notification ended by Android's time limit: downloads run unprotected")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ApkDownloadService"
        private const val NOTIFICATION_INTERVAL_MS = 500L

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ApkDownloadService::class.java))
            } catch (e: IllegalStateException) {
                // Started from the background, which Android refuses on 12 and later. Only ever called
                // from a tap on screen, so this is the odd race; the download runs regardless.
                Log.w(TAG, "Could not start: ${e.message}")
                DiagnosticLog.log("Download notification couldn't start: ${e::class.simpleName}")
            }
        }
    }
}
