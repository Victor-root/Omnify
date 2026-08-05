package com.looker.droidify.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Where a repository's saved login ends up when the server answers with a redirect.
 *
 * A repository password is attached to every request made to that repository. Redirects are ordinary
 * on the web, so following one to a different host and taking the password along would hand it, in a
 * form that decodes back to the original in one step, to a server the user never configured and may
 * not know about. For a repository hosted by an employer, that password is often the account
 * password, so the cost is well beyond the repository itself.
 *
 * The client library gets this right on its own today. These tests exist because nothing in this
 * codebase says so: the behaviour is inherited, it is invisible in review, and it would go unnoticed
 * if a version bump or a change to how the client is built took it away. They also pin the half that
 * has to keep working, which is a redirect that stays on the same host still carrying the login,
 * since dropping it there would break the private repositories this is meant to protect.
 */
class CredentialRedirectTest {

    private val engine = MockEngine { request ->
        when (request.url.host) {
            // A repository that sends downloads off to a CDN on another host.
            "repo.example" -> respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://cdn.example/app.apk"),
            )

            "cdn.example" -> respondOk("apk")

            // A repository that redirects within itself, e.g. to a versioned path.
            "same.example" -> if (request.url.encodedPath == "/app.apk") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://same.example/files/app.apk"),
                )
            } else {
                respondOk("apk")
            }

            else -> respondOk("")
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val downloader = KtorDownloader(HttpClient(engine), dispatcher)

    @Test
    fun `login is not carried to another host`() = runTest(dispatcher) {
        val file = File.createTempFile("redirect", "cross")

        downloader.downloadToFile(
            url = "https://repo.example/app.apk",
            target = file,
            headers = { authentication(USERNAME, PASSWORD) },
        )

        val hops = engine.requestHistory
        assertEquals(2, hops.size, "Expected the redirect to be followed exactly once")
        assertNotNull(
            hops[0].headers[HttpHeaders.Authorization],
            "The repository itself was asked without the login it was given",
        )
        assertNull(
            hops[1].headers[HttpHeaders.Authorization],
            "The login was handed to ${hops[1].url.host}, which the user never configured",
        )
    }

    @Test
    fun `login still reaches a redirect on the same host`() = runTest(dispatcher) {
        val file = File.createTempFile("redirect", "same")

        downloader.downloadToFile(
            url = "https://same.example/app.apk",
            target = file,
            headers = { authentication(USERNAME, PASSWORD) },
        )

        val hops = engine.requestHistory
        assertEquals(2, hops.size, "Expected the redirect to be followed exactly once")
        assertNotNull(
            hops[1].headers[HttpHeaders.Authorization],
            "A private repository redirecting to itself would fail to authenticate",
        )
    }

    private companion object {
        const val USERNAME = "iamlooker"
        const val PASSWORD = "sneakypeaky"
    }
}
