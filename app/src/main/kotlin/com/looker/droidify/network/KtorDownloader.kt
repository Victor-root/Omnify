package com.looker.droidify.network

import com.looker.droidify.network.header.HeadersBuilder
import com.looker.droidify.network.header.KtorHeadersBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.onDownload
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
        try {
            val rangeRequest = request(url, headers = headers)
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
