package com.looker.droidify.network

import com.looker.droidify.network.header.HeadersBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DownloadRetryTest {

    private class ScriptedDownloader(private val responses: List<NetworkResponse>) : Downloader {
        var calls = 0

        override suspend fun headCall(url: String, headers: HeadersBuilder.() -> Unit) = TODO()

        override suspend fun downloadToFile(
            url: String,
            target: File,
            headers: HeadersBuilder.() -> Unit,
            block: ProgressListener?,
        ): NetworkResponse = responses[minOf(calls++, responses.lastIndex)]

        override suspend fun getRange(url: String, headers: HeadersBuilder.() -> Unit) = TODO()
    }

    private val target = File("unused")
    private val success = NetworkResponse.Success(206, null, null)
    private val dropped = NetworkResponse.Error.IO(IOException("connection reset"))

    @Test
    fun `retries a dropped connection until it succeeds`() = runTest {
        val downloader = ScriptedDownloader(listOf(dropped, dropped, success))
        assertIs<NetworkResponse.Success>(downloader.downloadToFileWithRetry("https://a", target))
        assertEquals(3, downloader.calls)
    }

    @Test
    fun `does not retry an http error`() = runTest {
        val downloader = ScriptedDownloader(listOf(NetworkResponse.Error.Http(404)))
        assertIs<NetworkResponse.Error.Http>(downloader.downloadToFileWithRetry("https://a", target))
        assertEquals(1, downloader.calls)
    }

    @Test
    fun `gives up after the last attempt`() = runTest {
        val downloader = ScriptedDownloader(listOf(dropped))
        assertIs<NetworkResponse.Error.IO>(downloader.downloadToFileWithRetry("https://a", target))
        assertEquals(5, downloader.calls)
    }
}
