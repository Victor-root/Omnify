package com.looker.droidify.network

import com.looker.droidify.network.header.HeadersBuilder
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
