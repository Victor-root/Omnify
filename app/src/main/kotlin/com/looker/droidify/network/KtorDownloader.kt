package com.looker.droidify.network

import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.network.header.HeadersBuilder
import com.looker.droidify.network.header.KtorHeadersBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.etag
import io.ktor.http.isSuccess
import io.ktor.http.lastModified
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

internal class KtorDownloader(
    private val client: HttpClient,
    private val dispatcher: CoroutineDispatcher,
) : Downloader {

    /**
     * Hosts that answered a suffix range (`bytes=-N`) with a refusal. Whether a host takes that form is
     * a property of the host, not of the file, so one refusal answers for every URL it serves. Written
     * and read from whichever coroutines happen to be reading APK tails, hence concurrent.
     */
    private val suffixRangeUnsupportedHosts = ConcurrentHashMap.newKeySet<String>()

    /**
     * Total size per URL, learned from a `Content-Range` and kept so the readers that follow the first
     * one on the same APK can go straight to an explicit range. Only ever grows, which is fine for what
     * it holds: a URL and a long, for each remote APK this session actually looked inside.
     */
    private val totalSizeByUrl = ConcurrentHashMap<String, Long>()

    private companion object {
        const val TAG = "KtorDownloader"

        /** Read and write buffer for a download. Large enough that the per-chunk costs (a channel
         *  round trip, a progress callback, a write syscall) stop mattering next to the transfer
         *  itself, small enough to be nothing on any device that runs this app. */
        const val DOWNLOAD_BUFFER = 64 * 1024
    }

    override suspend fun headCall(
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ): NetworkResponse {
        val headRequest = request(url, headers = headers)
        return client.head(headRequest).asNetworkResponse()
    }

    /**
     * Streams [url] into [target], resuming by Range from whatever is already there, and reports
     * progress as it goes.
     *
     * The body is read and written here, in one loop over a [DOWNLOAD_BUFFER]-sized buffer, rather
     * than handed to Ktor's own copy with an `onDownload` listener attached. That listener is not the
     * cheap callback it reads as: it makes Ktor interpose a whole extra stage between the engine and
     * this code, a dedicated coroutine that re-copies the entire body through a second channel **4096
     * bytes at a time**, suspending on the listener at every one of those steps (verified in Ktor
     * 3.5.2's own bytecode). A 100 MB APK went through 25 600 of those round trips and a full extra
     * copy of itself, and the writes underneath reached the file unbuffered, one syscall per 8 KB
     * segment. Reading straight from the response channel keeps one copy, one coroutine, and roughly
     * a sixteenth of the round trips, and buffering the sink turns those syscalls into one per buffer.
     */
    override suspend fun downloadToFile(
        url: String,
        target: File,
        headers: HeadersBuilder.() -> Unit,
        block: ProgressListener?,
    ): NetworkResponse = withContext(dispatcher) {
        try {
            val fileSize = target.length()
            val request = request(url) {
                if (fileSize > 0) inRange(fileSize)
                headers()
            }
            val startedAt = System.nanoTime()
            client.prepareGet(request).execute { response ->
                val networkResponse = response.asNetworkResponse()
                if (networkResponse !is NetworkResponse.Success) {
                    return@execute networkResponse
                }
                // A Range request that's actually honoured comes back 206 Partial Content; a plain
                // 200 means the host ignored our Range header and is sending the whole file from
                // byte 0 instead of just the missing tail, so start the file over instead of
                // appending that after the stale bytes already on disk from a previous attempt.
                val appending = fileSize > 0 && response.status == HttpStatusCode.PartialContent
                val alreadyOnDisk = if (appending) fileSize else 0L
                // Content-Length is what's still to come, which is the whole file on a 200 and only
                // the missing tail on a 206; callers show a total, so add back what's already there.
                val total = response.headers[HttpHeaders.ContentLength]
                    ?.toLongOrNull()
                    ?.let { DataSize(it + alreadyOnDisk) }
                val transfer = streamToFile(
                    channel = response.bodyAsChannel(),
                    target = target,
                    appending = appending,
                    alreadyOnDisk = alreadyOnDisk,
                    total = total,
                    block = block,
                )
                logTransfer(url, response, startedAt, transfer)
                networkResponse
            }
        } catch (e: SocketTimeoutException) {
            NetworkResponse.Error.SocketTimeout(e)
        } catch (e: ConnectTimeoutException) {
            NetworkResponse.Error.ConnectionTimeout(e)
        } catch (e: IOException) {
            NetworkResponse.Error.IO(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NetworkResponse.Error.Unknown(e)
        }
    }

    /**
     * Reads [channel] to its end into [target], appending when the host honoured our Range, and hands
     * each buffer's worth to [block] as it lands.
     */
    private suspend fun streamToFile(
        channel: ByteReadChannel,
        target: File,
        appending: Boolean,
        alreadyOnDisk: Long,
        total: DataSize?,
        block: ProgressListener?,
    ): Transfer {
        var received = alreadyOnDisk
        var firstByteAt = 0L
        BufferedOutputStream(FileOutputStream(target, appending), DOWNLOAD_BUFFER).use { sink ->
            val buffer = ByteArray(DOWNLOAD_BUFFER)
            block?.invoke(DataSize(received), total)
            // readAvailable answers -1 at the end of the body, and otherwise suspends until it has
            // something; a zero-length read is inert on every line below rather than a case to skip.
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                if (firstByteAt == 0L) firstByteAt = System.nanoTime()
                sink.write(buffer, 0, read)
                received += read
                block?.invoke(DataSize(received), total)
            }
            sink.flush()
        }
        return Transfer(bytes = received - alreadyOnDisk, firstByteAt = firstByteAt)
    }

    /** What one [streamToFile] actually moved, for [logTransfer]. */
    private data class Transfer(val bytes: Long, val firstByteAt: Long)

    /**
     * One line per finished transfer, in debug builds only: what protocol was actually negotiated, how
     * long the host took to answer, and the throughput that came out of it.
     *
     * Here because a download that is sometimes fast and sometimes not, on the same file and the same
     * network, is not something the code can tell you on its own — every candidate (the host, a CDN
     * redirect, HTTP/2 sharing one connection with everything else the app is fetching, a slow first
     * byte) looks identical from the inside once the bytes are in. These four numbers separate them:
     * a slow first byte with fast throughput is the host, slow throughput on HTTP/2 with a fast first
     * byte is contention on the shared connection, and a different host from the one requested is a
     * redirect to a mirror.
     */
    private fun logTransfer(
        url: String,
        response: HttpResponse,
        startedAt: Long,
        transfer: Transfer,
    ) {
        if (!BuildConfig.DEBUG || transfer.bytes <= 0 || transfer.firstByteAt == 0L) return
        val now = System.nanoTime()
        val ttfbMs = (transfer.firstByteAt - startedAt) / 1_000_000
        val transferMs = (now - transfer.firstByteAt) / 1_000_000
        val kbPerSecond = if (transferMs > 0) transfer.bytes / transferMs else -1
        Log.d(
            TAG,
            "${response.call.request.url.host} ${response.version} status=${response.status.value} " +
                "bytes=${transfer.bytes} ttfb=${ttfbMs}ms transfer=${transferMs}ms " +
                "${kbPerSecond}KB/s ($url)",
        )
    }

    /**
     * Fetches a byte range, working around a host that rejects the suffix form and remembering what it
     * learns so the next caller doesn't pay to find out the same thing again.
     *
     * Confirmed on GitHub's own release "download" URLs (a flat 501): a *suffix* Range (`bytes=-N`,
     * "the last N bytes" — the only way to read the tail of a file whose total size isn't known yet,
     * see RemoteApkManifestReader/ApkSigningBlockReader) can be rejected outright by a host that
     * supports Range fine in the explicit bytes=start-end form. Recovering from that takes two more
     * requests: one to learn the size, one to ask again by explicit range.
     *
     * Both answers used to be thrown away the moment they were found. Four separate readers (signing
     * block, manifest, icon, locales) each read the tail of the *same* APK URL, so a single app cost
     * twelve requests where five would do, a third of them sent only to be refused — and every tracked
     * app's every listed version goes through this. That whole storm lands on the same host an APK
     * download comes from, which is what made a download that started while it was still running crawl
     * while the same download a few seconds later ran at full speed.
     *
     * So a host that refuses suffix ranges is remembered and never asked again, and a URL's total size
     * is remembered once learned, leaving one request for every reader after the first.
     */
    override suspend fun getRange(
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ): RangeResult = withContext(dispatcher) {
        val rangeRequest = request(url, headers = headers)
        val suffixLength = rangeRequest.headers[HttpHeaders.Range]?.let(::parseSuffixRangeLength)
        if (suffixLength != null && rangeRequest.url.host in suffixRangeUnsupportedHosts) {
            return@withContext explicitTailRange(url, headers, suffixLength)
                ?: RangeResult.Failed(NetworkResponse.Error.Http(HttpStatusCode.NotImplemented.value))
        }
        val result = executeRange(rangeRequest)
        if (result is RangeResult.Success) {
            result.totalSize?.let { totalSizeByUrl[url] = it }
            return@withContext result
        }
        if (result is RangeResult.Failed && BuildConfig.DEBUG) {
            Log.d(TAG, "$url: range request failed (${result.error})")
        }
        // Only a refusal teaches anything: a host that answers 200 ignores the Range header outright,
        // and would ignore an explicit one just the same.
        if (suffixLength == null || result !is RangeResult.Failed) return@withContext result
        suffixRangeUnsupportedHosts += rangeRequest.url.host
        explicitTailRange(url, headers, suffixLength) ?: result
    }

    /** The last [suffixLength] bytes of [url] asked for as an explicit range, for a host that won't
     *  take the suffix form. Null when the size can't be learned or the range comes back refused —
     *  in which case a remembered size is dropped, since a stale one is the one way this can fail
     *  where a fresh request would have worked. */
    private suspend fun explicitTailRange(
        url: String,
        headers: HeadersBuilder.() -> Unit,
        suffixLength: Long,
    ): RangeResult? {
        val totalSize = totalSizeByUrl[url]
            ?: contentLength(url, headers)?.also { totalSizeByUrl[url] = it }
        if (totalSize == null || totalSize <= 0) return null
        val start = (totalSize - suffixLength).coerceAtLeast(0)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "$url: suffix range unsupported, asking for bytes=$start-${totalSize - 1}")
        }
        val explicitRequest = request(url, headers = headers)
        explicitRequest.headers.remove(HttpHeaders.Range)
        explicitRequest.headers.append(HttpHeaders.Range, "bytes=$start-${totalSize - 1}")
        val explicitResult = executeRange(explicitRequest)
        if (explicitResult !is RangeResult.Success) {
            totalSizeByUrl.remove(url)
            return null
        }
        return explicitResult
    }

    private suspend fun executeRange(rangeRequest: HttpRequestBuilder): RangeResult = try {
        client.prepareGet(rangeRequest).execute { response ->
            when {
                // Only ever read the body once the server has confirmed (206) it sent back just
                // the requested slice — a plain 200 means it ignored our Range header and would
                // send the *whole* file, which response.body<ByteArray>() would then buffer
                // entirely into memory; the whole point of this call is to avoid that.
                response.status == HttpStatusCode.PartialContent -> RangeResult.Success(
                    bytes = response.body(),
                    totalSize = contentRangeTotalSize(response.headers[HttpHeaders.ContentRange]),
                )
                response.status.isSuccess() -> RangeResult.RangeNotSupported
                else -> RangeResult.Failed(NetworkResponse.Error.Http(response.status.value))
            }
        }
    } catch (e: SocketTimeoutException) {
        RangeResult.Failed(NetworkResponse.Error.SocketTimeout(e))
    } catch (e: ConnectTimeoutException) {
        RangeResult.Failed(NetworkResponse.Error.ConnectionTimeout(e))
    } catch (e: IOException) {
        RangeResult.Failed(NetworkResponse.Error.IO(e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RangeResult.Failed(NetworkResponse.Error.Unknown(e))
    }

    /** [url]'s real total size, read off the `Content-Range` of a genuine one-byte range response — the
     *  one thing a suffix range (`bytes=-N`) doesn't need to know but an equivalent explicit one does.
     *  Deliberately not a HEAD request: confirmed on GitHub's release download URLs, HEAD answers with a
     *  small stand-in response (a few hundred bytes) instead of the real file's headers the way GET
     *  does, making its Content-Length meaningless there — a live GET is the only answer this codebase
     *  has confirmed trustworthy on every host tried so far. */
    private suspend fun contentLength(url: String, headers: HeadersBuilder.() -> Unit): Long? {
        val probeRequest = request(url, headers = headers)
        probeRequest.headers.remove(HttpHeaders.Range)
        probeRequest.headers.append(HttpHeaders.Range, "bytes=0-0")
        return (executeRange(probeRequest) as? RangeResult.Success)?.totalSize
    }

    private fun request(
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ) = request {
        url(url)
        headers { KtorHeadersBuilder(this).headers() }
    }
}

private fun HttpResponse.asNetworkResponse(): NetworkResponse =
    if (status.isSuccess() || status == HttpStatusCode.NotModified) {
        NetworkResponse.Success(status.value, lastModified(), etag())
    } else {
        NetworkResponse.Error.Http(status.value)
    }

/** Parses the total-resource-size out of a `Content-Range: bytes {start}-{end}/{total}` response
 *  header. Null if absent or unparseable. */
private fun contentRangeTotalSize(headerValue: String?): Long? {
    if (headerValue == null) return null
    val slashIndex = headerValue.lastIndexOf('/')
    if (slashIndex == -1) return null
    return headerValue.substring(slashIndex + 1).toLongOrNull()
}

private val SUFFIX_RANGE = Regex("""^bytes=-(\d+)$""")

/** The N out of a `Range: bytes=-N` (suffix, "last N bytes") request header value, or null when
 *  [rangeHeaderValue] isn't that form (an explicit bytes=start-end range, or absent). */
private fun parseSuffixRangeLength(rangeHeaderValue: String): Long? =
    SUFFIX_RANGE.matchEntire(rangeHeaderValue)?.groupValues?.get(1)?.toLongOrNull()
