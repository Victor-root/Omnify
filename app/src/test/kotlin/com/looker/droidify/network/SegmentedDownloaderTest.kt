package com.looker.droidify.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SegmentedDownloaderTest {

    private val content = Random(42).nextBytes(20 * 1024 * 1024)
    private val target = File.createTempFile("segmented", ".apk").apply { deleteOnExit() }
    private val stateFile = File(target.path + ".parts")
    private val partRequests = AtomicInteger()

    /** A host serving [content] under [etag], ranges and If-Range included, with [onPart] free to stand
     *  in for any part request (the probe asks for byte 0 only and is never handed to it). */
    private fun downloader(
        etag: () -> String = { "\"v1\"" },
        acceptRanges: Boolean = true,
        onPart: MockRequestHandleScope.(HttpRequestData, Long) -> HttpResponseData? = { _, _ -> null },
    ) = SegmentedDownloader(
        client = HttpClient(
            MockEngine { request ->
                val range = request.headers[HttpHeaders.Range]
                    ?.removePrefix("bytes=")?.split('-')?.map { it.toLong() }
                if (!acceptRanges || range == null) {
                    return@MockEngine respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, etag()))
                }
                val (from, to) = range
                if (from != 0L || to != 0L) {
                    partRequests.incrementAndGet()
                    onPart(request, from)?.let { return@MockEngine it }
                    val ifRange = request.headers[HttpHeaders.IfRange]
                    if (ifRange != null && ifRange != etag()) {
                        return@MockEngine respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, etag()))
                    }
                }
                respond(
                    content = content.copyOfRange(from.toInt(), to.toInt() + 1),
                    status = HttpStatusCode.PartialContent,
                    headers = headersOf(
                        HttpHeaders.ContentRange to listOf("bytes $from-$to/${content.size}"),
                        HttpHeaders.ETag to listOf(etag()),
                    ),
                )
            },
        ),
        dispatcher = Dispatchers.IO,
    )

    private fun SegmentedDownloader.run() = runBlocking {
        download("https://host.example/app.apk", target, {}, null, label = "test")
    }

    @Test
    fun `downloads a large file in parts that join into the original`() {
        val outcome = downloader().run()
        assertIs<NetworkResponse.Success>(assertIs<SegmentedDownloader.Outcome.Finished>(outcome).response)
        assertContentEquals(content, target.readBytes())
        assertTrue(partRequests.get() > 1)
        assertFalse(stateFile.exists())
    }

    @Test
    fun `a host without ranges is left to the usual download`() {
        assertEquals(SegmentedDownloader.Outcome.NotSegmentable, downloader(acceptRanges = false).run())
        assertFalse(stateFile.exists())
    }

    @Test
    fun `a file replaced on the host mid-download is never stitched together`() {
        val probed = AtomicInteger()
        val outcome = downloader(etag = { if (probed.getAndIncrement() == 0) "\"v1\"" else "\"v2\"" }).run()
        assertEquals(SegmentedDownloader.Outcome.NotSegmentable, outcome)
        assertFalse(target.exists())
        assertFalse(stateFile.exists())
    }

    @Test
    fun `a part whose connection drops is retried and resumed`() {
        val dropped = AtomicInteger()
        val outcome = downloader { _, _ ->
            if (dropped.getAndIncrement() == 0) throw IOException("connection reset") else null
        }.run()
        assertIs<NetworkResponse.Success>(assertIs<SegmentedDownloader.Outcome.Finished>(outcome).response)
        assertContentEquals(content, target.readBytes())
    }

    @Test
    fun `a download that failed resumes from the parts already here`() {
        val failing = downloader { _, from ->
            if (from > 0) respond("", HttpStatusCode.InternalServerError) else null
        }.run()
        assertIs<NetworkResponse.Error.Http>(assertIs<SegmentedDownloader.Outcome.Finished>(failing).response)
        assertTrue(stateFile.exists())

        partRequests.set(0)
        val outcome = downloader().run()
        assertIs<NetworkResponse.Success>(assertIs<SegmentedDownloader.Outcome.Finished>(outcome).response)
        assertContentEquals(content, target.readBytes())
        assertFalse(stateFile.exists())
    }
}
