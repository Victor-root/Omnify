package com.looker.droidify.network

import android.util.Log
import com.looker.droidify.network.header.HeadersBuilder
import com.looker.droidify.network.header.KtorHeadersBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.onDownload
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
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class KtorDownloader(
    private val client: HttpClient,
    private val dispatcher: CoroutineDispatcher,
) : Downloader {

    private companion object {
        const val TAG = "KtorDownloader"
    }

    override suspend fun headCall(
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ): NetworkResponse {
        val headRequest = request(url, headers = headers)
        return client.head(headRequest).asNetworkResponse()
    }

    override suspend fun downloadToFile(
        url: String,
        target: File,
        headers: HeadersBuilder.() -> Unit,
        block: ProgressListener?,
    ): NetworkResponse = withContext(dispatcher) {
        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(target, true)
            val fileSize = target.length()
            val request = request(
                url = url,
                fileSize = fileSize,
                block = block,
            ) {
                if (fileSize > 0) inRange(fileSize)
                headers()
            }
            client.prepareGet(request).execute { response ->
                val networkResponse = response.asNetworkResponse()
                if (networkResponse !is NetworkResponse.Success) {
                    return@execute networkResponse
                }
                response.bodyAsChannel().copyTo(output)
                output.flush()
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
        } finally {
            withContext(NonCancellable) {
                output?.close()
                output?.flush()
            }
        }
    }

    override suspend fun getRange(
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ): RangeResult = withContext(dispatcher) {
        val rangeRequest = request(url, headers = headers)
        val suffixLength = rangeRequest.headers[HttpHeaders.Range]?.let(::parseSuffixRangeLength)
        val result = executeRange(rangeRequest)
        if (result is RangeResult.Failed) {
            Log.d(TAG, "$url: range request failed (${result.error})")
        }
        if (result is RangeResult.Success || suffixLength == null) return@withContext result
        // Confirmed on GitHub's own release "download" URLs (a flat 501): a *suffix* Range
        // (`bytes=-N`, "the last N bytes" — the only way to read the tail of a file whose total size
        // isn't known yet, see RemoteApkManifestReader/ApkSigningBlockReader) can be rejected outright
        // by a host that genuinely supports Range fine for the explicit bytes=start-end form. Learn the
        // real size and retry as an explicit range instead of giving up on a host that actually
        // supports what we need.
        val totalSize = contentLength(url, headers)
        if (totalSize == null || totalSize <= 0) return@withContext result
        val start = (totalSize - suffixLength).coerceAtLeast(0)
        Log.d(TAG, "$url: suffix range unsupported, retrying as explicit bytes=$start-${totalSize - 1}")
        val explicitRequest = request(url, headers = headers)
        explicitRequest.headers.remove(HttpHeaders.Range)
        explicitRequest.headers.append(HttpHeaders.Range, "bytes=$start-${totalSize - 1}")
        val explicitResult = executeRange(explicitRequest)
        if (explicitResult is RangeResult.Success) explicitResult else result
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
        fileSize: Long = 0L,
        block: ProgressListener? = null,
        headers: HeadersBuilder.() -> Unit,
    ) = request {
        url(url)
        headers { KtorHeadersBuilder(this).headers() }
        if (block != null) {
            onDownload { read, total ->
                block(
                    DataSize(read + fileSize),
                    total?.let { DataSize(total + fileSize) },
                )
            }
        }
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
