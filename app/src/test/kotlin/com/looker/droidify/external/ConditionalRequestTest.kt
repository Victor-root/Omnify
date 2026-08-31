package com.looker.droidify.external

import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The request savings, end to end through the real [ExternalApi]: what actually leaves the device,
 * and what it carries. [ConditionalGetCacheTest] covers the store itself; this covers the wiring
 * around it, which is where "we asked again for nothing" and "we never asked again at all" both hide.
 *
 * Driven through [ExternalApi.listAccountRepos] rather than the release lookup: it goes through the
 * very same shared request helper, and unlike the release lookup it has no dependency on the device's
 * CPU architectures, which a unit test has none of.
 */
class ConditionalRequestTest {

    @TempDir
    lateinit var dir: File

    private lateinit var api: ExternalApi

    /** Every request the engine was handed, in order. */
    private val sent = mutableListOf<HttpRequestData>()

    /** Swapped per test to decide what the server answers. */
    private var reply: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { pageOne(it) }

    private var settings = Settings()

    @BeforeEach
    fun setUp() {
        sent.clear()
        settings = Settings()
        val settingsRepository = mockk<SettingsRepository>().also {
            coEvery { it.getInitial() } answers { settings }
        }
        api = ExternalApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    sent += request
                    reply(request)
                },
            ),
            settingsRepository = settingsRepository,
            responseCache = ConditionalGetCache(dir),
        )
    }

    private fun repos(vararg names: String) = names.joinToString(",", "[", "]") {
        """{"name":"$it","fork":false,"archived":false,"owner":{"login":"someone"}}"""
    }

    /** Page one lists two repos and carries an ETag; page two is empty, which ends the walk. */
    private fun MockRequestHandleScope.pageOne(
        request: HttpRequestData,
        body: String = repos("alpha", "beta"),
        etag: String = "\"v1\"",
    ): HttpResponseData = if (isFirstPage(request)) {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ETag, etag))
    } else {
        respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"empty\""))
    }

    private fun isFirstPage(request: HttpRequestData) =
        request.url.parameters["page"] == "1"

    private suspend fun list() = api.listAccountRepos(
        SourceProvider.CODEBERG,
        "codeberg.org",
        "someone",
        includeForks = false,
    ).map { it.repo }

    private suspend fun listOnGithub() = api.listAccountRepos(
        SourceProvider.GITHUB,
        "github.com",
        "someone",
        includeForks = false,
    ).map { it.repo }

    /** Ages every stored entry well past the fresh window, the way waiting would. */
    private fun ageCache() {
        dir.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(1_000L) }
    }

    private fun conditionOf(request: HttpRequestData) = request.headers[HttpHeaders.IfNoneMatch]

    @Test
    fun `the first call asks the server, unconditionally`() = runTest {
        assertEquals(listOf("alpha", "beta"), list())

        assertEquals(2, sent.size, "one request per page walked")
        assertTrue(sent.all { conditionOf(it) == null }, "nothing is held yet, so nothing to condition on")
    }

    @Test
    fun `a repeat within the minute sends nothing at all`() = runTest {
        assertEquals(listOf("alpha", "beta"), list())
        val afterFirst = sent.size

        assertEquals(listOf("alpha", "beta"), list())

        assertEquals(afterFirst, sent.size, "opening a screen twice must not ask the same thing twice")
    }

    @Test
    fun `past the minute it asks again, and says what it already holds`() = runTest {
        list()
        val afterFirst = sent.size
        ageCache()

        list()

        assertEquals(afterFirst * 2, sent.size)
        assertEquals(
            "\"v1\"",
            conditionOf(sent.drop(afterFirst).first(::isFirstPage)),
            "the second round must carry the ETag the first round was given",
        )
    }

    @Test
    fun `an unchanged answer costs a 304 and still returns the same repos`() = runTest {
        assertEquals(listOf("alpha", "beta"), list())
        ageCache()
        reply = { respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, "\"v1\"")) }

        assertEquals(
            listOf("alpha", "beta"),
            list(),
            "a 304 has no body, so the answer has to come from what was stored",
        )
    }

    @Test
    fun `a 304 restarts the window, so the call after it sends nothing`() = runTest {
        list()
        ageCache()
        reply = { respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, "\"v1\"")) }
        list()
        val afterRevalidation = sent.size

        assertEquals(listOf("alpha", "beta"), list())

        assertEquals(afterRevalidation, sent.size)
    }

    @Test
    fun `a changed answer replaces what was stored`() = runTest {
        assertEquals(listOf("alpha", "beta"), list())
        ageCache()
        reply = { pageOne(it, body = repos("alpha", "beta", "gamma"), etag = "\"v2\"") }

        assertEquals(listOf("alpha", "beta", "gamma"), list())

        // And the round after that conditions on the new ETag, not the one it replaced.
        ageCache()
        val before = sent.size
        list()
        assertEquals(
            "\"v2\"",
            conditionOf(sent.drop(before).first { isFirstPage(it) }),
        )
    }

    @Test
    fun `two urls are conditioned on independently`() = runTest {
        list()
        ageCache()
        val before = sent.size

        list()

        val conditions = sent.drop(before).map(::conditionOf)
        assertEquals(
            listOf("\"v1\"", "\"empty\""),
            conditions,
            "each page must be revalidated with its own ETag, never with another page's",
        )
    }

    @Test
    fun `a failed request is not stored, so the next call really asks again`() = runTest {
        reply = { respond("", HttpStatusCode.NotFound) }
        assertTrue(list().isEmpty())
        val afterFailure = sent.size

        reply = { pageOne(it) }
        assertEquals(listOf("alpha", "beta"), list())

        assertTrue(sent.size > afterFailure, "a 404 must never be remembered as an answer")
    }

    @Test
    fun `a rate-limited request is not stored either`() = runTest {
        reply = {
            respond("", HttpStatusCode.Forbidden, headersOf("X-RateLimit-Remaining", "0"))
        }
        assertTrue(listOnGithub().isEmpty())
        assertTrue(api.shouldSuggestGithubToken(), "no token and out of quota is the moment to say so")

        reply = { pageOne(it) }
        assertEquals(listOf("alpha", "beta"), listOnGithub())
    }

    @Test
    fun `the quota gauge is read from a 304 as well as from a 200`() = runTest {
        listOnGithub()
        ageCache()
        reply = { respond("", HttpStatusCode.NotModified, headersOf("X-RateLimit-Remaining", "57")) }

        listOnGithub()

        assertEquals(
            57,
            api.rateLimitRemaining.value,
            "GitHub reports the quota on a 304 too, and it is the number that stops going down",
        )
    }

    @Test
    fun `a rejected token is caught, but not on one rejection alone`() = runTest {
        // The 304 handling sits in the middle of this accounting, so a good token still has to be
        // told apart from a bad one after it.
        settings = Settings(githubToken = "a-token")
        reply = { respond("", HttpStatusCode.Unauthorized) }

        listOnGithub()
        assertFalse(api.githubTokenInvalid.value, "one rejection could be a fluke")

        listOnGithub()
        assertTrue(api.githubTokenInvalid.value)
    }

    @Test
    fun `a 304 confirms the token instead of leaving it under suspicion`() = runTest {
        settings = Settings(githubToken = "a-token")
        // Something has to be held before a 304 can happen at all, so the good round comes first.
        listOnGithub()
        ageCache()

        reply = { respond("", HttpStatusCode.Unauthorized) }
        listOnGithub()
        listOnGithub()
        assertTrue(api.githubTokenInvalid.value, "two rejections in a row is a rejected token")

        reply = { respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, "\"v1\"")) }
        listOnGithub()

        assertFalse(
            api.githubTokenInvalid.value,
            "a 304 is an accepted request, so it clears the alarm exactly as a 200 does",
        )
    }

    @Test
    fun `the token check refuses to answer from the cache`() = runTest {
        reply = { respond("""{"rate":{"remaining":59}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"r\"")) }
        api.verifyGithubToken()
        val afterFirst = sent.size

        api.verifyGithubToken()

        assertTrue(
            sent.size > afterFirst,
            "a token replaced moments ago must be judged on its own answer, not the old one's",
        )
    }

    @Test
    fun `the token check is what pays for that, and nothing else revalidates needlessly`() = runTest {
        api.verifyGithubToken()
        sent.clear()

        list()
        val afterFirst = sent.size
        list()

        assertEquals(afterFirst, sent.size)
    }

    @Test
    fun `a source added twice in a row is looked up once`() = runTest {
        // The add dialog probes a pasted host, and a user correcting a typo lands here twice.
        assertTrue(api.isGiteaInstance("codeberg.org", "someone", "alpha"))
        val afterFirst = sent.size

        assertTrue(api.isGiteaInstance("codeberg.org", "someone", "alpha"))

        assertEquals(afterFirst, sent.size)
    }

    @Test
    fun `a different repo on the same host is its own lookup`() = runTest {
        api.isGiteaInstance("codeberg.org", "someone", "alpha")
        val afterFirst = sent.size

        api.isGiteaInstance("codeberg.org", "someone", "beta")

        assertTrue(sent.size > afterFirst, "one repo's answer says nothing about another's")
    }

    @Test
    fun `a body too large to store is still returned, it is simply asked for again`() = runTest {
        val many = (1..8_000).map { "repo$it" }.toTypedArray()
        reply = { pageOne(it, body = repos(*many)) }

        val first = list()
        assertEquals(8_000, first.size)
        val afterFirst = sent.size

        assertEquals(first, list())
        assertTrue(sent.size > afterFirst, "an entry that was never stored cannot be served back")
    }

    // The conditional header is added to the very block that carries GitHub's own headers, so what
    // that block did before has to go on being asserted.

    @Test
    fun `the GitHub headers are still sent, and only to GitHub`() = runTest {
        listOnGithub()
        val toGithub = sent.first()
        assertEquals("application/vnd.github+json", toGithub.headers[HttpHeaders.Accept])
        assertEquals("2022-11-28", toGithub.headers["X-GitHub-Api-Version"])

        sent.clear()
        list()
        val toCodeberg = sent.first()
        assertNull(toCodeberg.headers["X-GitHub-Api-Version"])
    }

    @Test
    fun `the token goes to GitHub and nowhere else`() = runTest {
        settings = Settings(githubToken = "a-token")

        listOnGithub()
        assertEquals("Bearer a-token", sent.first().headers[HttpHeaders.Authorization])

        sent.clear()
        list()
        assertNull(
            sent.first().headers[HttpHeaders.Authorization],
            "a self-hosted instance is any address at all, and must never be handed a GitHub token",
        )
    }

    @Test
    fun `an empty first page ends the walk there`() = runTest {
        reply = { respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ETag, "\"empty\"")) }

        assertTrue(list().isEmpty())

        assertEquals(1, sent.size, "nothing on page one means there is no page two to ask for")
    }

    @Test
    fun `a full first page is followed by a second`() = runTest {
        assertEquals(listOf("alpha", "beta"), list())

        assertEquals(listOf("1", "2"), sent.map { it.url.parameters["page"] })
    }

    @Test
    fun `a day of unchanged refreshes costs one round, not one per refresh`() = runTest {
        // What the whole change is for: a source that publishes nothing should stop being paid for.
        var bodiesServed = 0
        reply = {
            bodiesServed++
            pageOne(it)
        }
        list()
        val firstRound = bodiesServed

        var conditional = 0
        reply = {
            conditional++
            respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.ETag, "\"v1\""))
        }
        repeat(24) {
            ageCache()
            assertEquals(listOf("alpha", "beta"), list())
        }

        assertEquals(2, firstRound, "the first round is the one that pays")
        assertEquals(
            0,
            bodiesServed - firstRound,
            "not one of the checks after it should have been answered with a body",
        )
        assertTrue(conditional > 0, "and they did all really ask")
    }

    @Test
    fun `nothing is written for a request that never reached a server`() = runTest {
        reply = { throw java.net.UnknownHostException("no route") }

        assertTrue(list().isEmpty())

        assertNull(
            dir.listFiles()?.firstOrNull(),
            "a failure must not leave an entry behind for the next call to trust",
        )
    }
}
