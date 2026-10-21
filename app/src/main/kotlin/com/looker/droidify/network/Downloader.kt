package com.looker.droidify.network

import com.looker.droidify.network.header.HeadersBuilder
import kotlinx.coroutines.delay
import java.io.File

interface Downloader {

    suspend fun headCall(
        url: String,
        headers: HeadersBuilder.() -> Unit = {},
    ): NetworkResponse

    suspend fun downloadToFile(
        url: String,
        target: File,
        headers: HeadersBuilder.() -> Unit = {},
        block: ProgressListener? = null,
    ): NetworkResponse

    /** Fetches a byte range into memory instead of a file — [headers] must set the range itself
     *  (via [HeadersBuilder.inRange] or [HeadersBuilder.inRangeSuffix]), same as any other header. Only
     *  ever reads the response body when the server actually honoured the range request (HTTP 206);
     *  a server that ignores it and would send the whole file is reported as [RangeResult.RangeNotSupported]
     *  without downloading anything, so a caller peeking at a small slice of a large remote file can't
     *  accidentally pull the whole thing into memory. */
    suspend fun getRange(
        url: String,
        headers: HeadersBuilder.() -> Unit = {},
    ): RangeResult
}

typealias ProgressListener = suspend (bytesReceived: DataSize, contentLength: DataSize?) -> Unit

/**
 * [Downloader.downloadToFile], tried again after a transient, connection-level failure (a stall past
 * the socket timeout, a dropped connection), each attempt resuming by Range from what [target] already
 * holds. An HTTP error or an unrecognised exception would fail the exact same way again, so it is
 * returned at once.
 *
 * Confirmed on GitHub, Codeberg and GitLab release assets: asking the same release URL again
 * later follows a fresh redirect to the file and honours the Range, and the joined parts match the
 * file byte for byte. A host that ignores the Range sends the whole file, which the downloader writes
 * from the start instead of appending.
 */
suspend fun Downloader.downloadToFileWithRetry(
    url: String,
    target: File,
    headers: HeadersBuilder.() -> Unit = {},
    block: ProgressListener? = null,
): NetworkResponse = retryWhileTransient({ it.isTransientFailure() }) {
    downloadToFile(url, target, headers, block)
}

/** [attempt] again while [isTransient] says its outcome is worth another try, waiting longer each time,
 *  up to [MAX_DOWNLOAD_ATTEMPTS] attempts in all. The last outcome is returned whatever it is. */
internal suspend fun <T> retryWhileTransient(isTransient: (T) -> Boolean, attempt: suspend () -> T): T {
    var retryDelay = FIRST_RETRY_DELAY_MS
    repeat(MAX_DOWNLOAD_ATTEMPTS - 1) {
        val outcome = attempt()
        if (!isTransient(outcome)) return outcome
        delay(retryDelay)
        retryDelay *= 2
    }
    return attempt()
}

/** A stall past the socket timeout or a dropped connection: the same request can work a moment later.
 *  An HTTP error or an unrecognised exception would fail the exact same way again. */
internal fun NetworkResponse.isTransientFailure(): Boolean =
    this is NetworkResponse.Error.SocketTimeout ||
        this is NetworkResponse.Error.ConnectionTimeout ||
        this is NetworkResponse.Error.IO

/** Total attempts, the first one included. Each retry resumes rather than restarts, so a few more of
 *  them cost nothing but time, and the doubling delays (2, 4, 8, 16 s) span a Wi-Fi handoff or a
 *  short loss of signal. */
private const val MAX_DOWNLOAD_ATTEMPTS = 5

private const val FIRST_RETRY_DELAY_MS = 2_000L

sealed interface RangeResult {

    /** [bytes] is exactly the requested range. [totalSize] is the remote file's real total size, read
     *  from the response's Content-Range header (present on every well-formed 206 response).
     *  Deliberately not a data class: a [ByteArray] property would get reference-equality
     *  equals()/hashCode() from the generated code, which is misleading to have around at all. */
    class Success(val bytes: ByteArray, val totalSize: Long?) : RangeResult

    /** The server responded with the whole file (status 200) instead of honouring the range request —
     *  its body was deliberately not read. */
    data object RangeNotSupported : RangeResult

    data class Failed(val error: NetworkResponse.Error) : RangeResult
}
