package com.looker.droidify.installer

import android.content.Context
import androidx.annotation.MainThread
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.di.ApplicationScope
import com.looker.droidify.network.ProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The downloads and installs started from an app's own page, run outside that page.
 *
 * They used to run in the page's view model, so going back from the page cancelled them halfway, and
 * leaving the app left them to whatever Android decided to do with a process in the background. Here
 * they run in the application's scope until they finish or are cancelled, and [ApkDownloadService]
 * holds a notification for as long as any is running, which is what tells Android to let them finish.
 *
 * Each runs on the main thread, as it did in the view model: the work handed to [start] is the page's
 * own, toasts and dialogs included. A page closed in the meantime simply shows nothing of what comes
 * after; the download and the install still go ahead.
 *
 * Keyed by what the pages already key their downloads by: a catalogue app's package name, an external
 * source's key.
 */
@Singleton
class ApkDownloads @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    /** One download in flight: [label] names the app in the notification. */
    data class Download(val label: String, val status: DownloadStatus)

    private val _active = MutableStateFlow<Map<String, Download>>(emptyMap())

    /** Every download in flight, by key, with its progress. */
    val active: StateFlow<Map<String, Download>> = _active.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun isRunning(key: String): Boolean = key in _active.value

    /**
     * Runs [work] for [key], unless one already runs for it (then false). [work] is handed the listener
     * to give the downloader, which is what feeds [active] and the notification.
     */
    @MainThread
    fun start(key: String, label: String, work: suspend (ProgressListener) -> Unit): Boolean {
        if (isRunning(key)) return false
        _active.update { it + (key to Download(label, DownloadStatus(read = 0, total = -1, bytesPerSecond = 0))) }
        val meter = DownloadMeter()
        // Lazy, so the job is on record before any of it runs: work that ends before its first
        // suspension would otherwise clear its entry before there was one.
        val job = scope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            try {
                work { read, total ->
                    val status = meter.sample(read.value, total?.value) ?: return@work
                    _active.update { current ->
                        current[key]?.let { current + (key to it.copy(status = status)) } ?: current
                    }
                }
            } finally {
                jobs.remove(key)
                _active.update { it - key }
            }
        }
        jobs[key] = job
        ApkDownloadService.start(context)
        job.start()
        return true
    }

    /** Stops the download or install running for [key]. False when there is none. */
    @MainThread
    fun cancel(key: String): Boolean {
        val job = jobs[key] ?: return false
        job.cancel()
        return true
    }
}
