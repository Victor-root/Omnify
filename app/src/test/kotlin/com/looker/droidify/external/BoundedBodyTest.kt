package com.looker.droidify.external

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A self-hosted Gitea or Forgejo source is whatever address the user was handed, so every response
 * read from one is a response from a host this app has no reason to trust the size of. These cover
 * both halves of that: an ordinary answer has to come back exactly as it was sent, and an answer
 * that would not fit in memory has to be refused rather than read.
 */
class BoundedBodyTest {

    private val limit = 1000

    private fun clientReturning(body: ByteArray, declareLength: Boolean = true) = HttpClient(
        MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = if (declareLength) {
                    headersOf(HttpHeaders.ContentLength, body.size.toString())
                } else {
                    headersOf()
                },
            )
        },
    )

    @Test
    fun `an ordinary response comes back byte for byte`() = runTest {
        val body = ByteArray(500) { (it % 251).toByte() }
        assertContentEquals(
            body,
            clientReturning(body).get(URL).bodyBytesAtMost(limit),
            "An ordinary response was altered, which would break every API call in the app",
        )
    }

    @Test
    fun `a response of exactly the limit is still whole`() = runTest {
        val body = ByteArray(limit) { 7 }
        assertContentEquals(body, clientReturning(body).get(URL).bodyBytesAtMost(limit))
    }

    @Test
    fun `an empty response is an answer, not a failure`() = runTest {
        assertContentEquals(ByteArray(0), clientReturning(ByteArray(0)).get(URL).bodyBytesAtMost(limit))
    }

    @Test
    fun `a response that declares itself too large is refused`() = runTest {
        assertNull(clientReturning(ByteArray(limit + 1)).get(URL).bodyBytesAtMost(limit))
    }

    @Test
    fun `a response too large with no Content-Length at all is refused`() = runTest {
        // The case the old code could not stop: it read the whole body first and only then looked at
        // its size, so a host that simply omitted the header could send as much as it liked.
        assertNull(
            clientReturning(ByteArray(limit * 50), declareLength = false).get(URL).bodyBytesAtMost(limit),
            "An unbounded response was read whole",
        )
    }

    @Test
    fun `a host that understates its own length gets nothing through`() = runTest {
        val lying = HttpClient(
            MockEngine { _ ->
                respond(
                    content = ByteArray(limit * 10),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentLength, "10"),
                )
            },
        )
        // Ktor refuses a body that doesn't match the length its host declared, so such a response
        // fails before it reaches the limit at all. Either outcome is fine; getting the body is not.
        val result = runCatching { lying.get(URL).bodyBytesAtMost(limit) }
        assertTrue(result.isFailure || result.getOrNull() == null, "A mismatched body was accepted")
    }

    @Test
    fun `text decodes exactly as it was sent`() = runTest {
        val text = "Omnify: prereleases activees, 100% ok"
        assertEquals(
            text,
            clientReturning(text.toByteArray(Charsets.UTF_8)).get(URL).bodyTextAtMost(limit),
        )
    }

    private companion object {
        const val URL = "https://example.org/api"
    }
}
