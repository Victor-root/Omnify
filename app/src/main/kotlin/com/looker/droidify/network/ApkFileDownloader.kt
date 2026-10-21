package com.looker.droidify.network

import android.os.SystemClock
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.network.header.HeadersBuilder
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where every APK is downloaded, in parts over several connections when the experimental setting asks
 * for it ([SegmentedDownloader]), whole otherwise ([Downloader.downloadToFileWithRetry]). A host or a
 * file that can't be downloaded in parts is downloaded whole regardless, so the setting only ever
 * chooses which way is tried first.
 */
@Singleton
class ApkFileDownloader @Inject constructor(
    private val downloader: Downloader,
    private val segmented: SegmentedDownloader,
    private val settingsRepository: SettingsRepository,
) {

    /** Numbers each download in the diagnostic log, which names no app or address. */
    private val downloadCount = AtomicInteger()

    suspend fun download(
        url: String,
        target: File,
        headers: HeadersBuilder.() -> Unit = {},
        block: ProgressListener? = null,
    ): NetworkResponse {
        val label = "download #${downloadCount.incrementAndGet()}"
        if (settingsRepository.getInitial().segmentedDownloads) {
            DiagnosticLog.log("$label: started, parallel downloads on")
            val outcome = segmented.download(url, target, headers, block, label)
            if (outcome is SegmentedDownloader.Outcome.Finished) return outcome.response
        } else {
            DiagnosticLog.log("$label: started, parallel downloads off")
            // Left by a download in parts, before the setting was turned off: a file already at its
            // final size, which the whole download would take for nearly finished.
            segmented.discard(target)
        }
        val resumingFrom = target.length()
        val startedAt = SystemClock.elapsedRealtime()
        val response = downloader.downloadToFileWithRetry(url, target, headers, block, label)
        val ms = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
        DiagnosticLog.log(
            "$label: whole download ${response.diagnosticLabel()} after ${ms / 1000} s, " +
                "${(target.length() - resumingFrom).coerceAtLeast(0) / ms} KB/s, resumed from $resumingFrom bytes",
        )
        return response
    }
}
