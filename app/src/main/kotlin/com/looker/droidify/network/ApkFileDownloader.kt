package com.looker.droidify.network

import android.os.SystemClock
import com.looker.droidify.network.header.HeadersBuilder
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where every APK is downloaded: in parts over several connections ([SegmentedDownloader]), or whole
 * ([Downloader.downloadToFileWithRetry]) when the host or the file can't be downloaded in parts.
 */
@Singleton
class ApkFileDownloader @Inject constructor(
    private val downloader: Downloader,
    private val segmented: SegmentedDownloader,
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
        DiagnosticLog.log("$label: started")
        val outcome = segmented.download(url, target, headers, block, label)
        if (outcome is SegmentedDownloader.Outcome.Finished) return outcome.response
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
