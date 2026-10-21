package com.looker.droidify.network

import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.network.header.HeadersBuilder
import java.io.File
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

    suspend fun download(
        url: String,
        target: File,
        headers: HeadersBuilder.() -> Unit = {},
        block: ProgressListener? = null,
    ): NetworkResponse {
        if (settingsRepository.getInitial().segmentedDownloads) {
            val outcome = segmented.download(url, target, headers, block)
            if (outcome is SegmentedDownloader.Outcome.Finished) return outcome.response
        } else {
            // Left by a download in parts, before the setting was turned off: a file already at its
            // final size, which the whole download would take for nearly finished.
            segmented.discard(target)
        }
        return downloader.downloadToFileWithRetry(url, target, headers, block)
    }
}
