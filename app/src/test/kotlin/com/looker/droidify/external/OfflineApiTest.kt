package com.looker.droidify.external

import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every request fails the way a device with no route out fails: the host name never resolves.
 *
 * Each function here answers with null/empty rather than throwing, and the detail screen relies on
 * that: it starts these lookups from plain coroutines whose failure nothing catches, so one that
 * throws doesn't fail a section, it kills the process. The project-website lookup did exactly that,
 * being the only caller of the shared request helper that let its exception straight back out.
 */
class OfflineApiTest {

    private val settingsRepository = mockk<SettingsRepository>().also {
        coEvery { it.getInitial() } returns Settings()
    }

    private val api = ExternalApi(
        httpClient = HttpClient(MockEngine { throw UnknownHostException("No address associated with hostname") }),
        settingsRepository = settingsRepository,
    )

    private val app = ExternalApp(owner = "Zverik", repo = "every_door")

    @Test
    fun `the project website lookup answers instead of throwing`() = runTest {
        assertNull(api.fetchWebsiteUrl(app))
    }

    @Test
    fun `the links section's other lookups answer too`() = runTest {
        assertNull(api.fetchIssueTrackerUrl(app))
        assertNull(api.fetchChangelogUrl(app))
        assertNull(api.fetchChangelogHtml(app))
    }

    @Test
    fun `the page's own content lookups answer`() = runTest {
        assertNull(api.readmeHtml(app))
        assertNull(api.fetchPackageId(app))
        assertNull(api.fetchSourceLocales(app))
        assertNull(api.detectBaseLanguage(app))
        assertNull(api.fetchRepoMetadata(app))
        assertTrue(api.fetchIconCandidates(app).isEmpty())
    }

    @Test
    fun `the release lookups answer, and say the fetch is what failed`() = runTest {
        assertNull(api.latestReleaseFor(app))
        assertTrue(api.releaseHistory(app).isEmpty())
        // The one place the difference matters: "couldn't ask" has to stay distinguishable from "asked,
        // and there is nothing", or an offline app looks like a source that stopped publishing.
        assertEquals(ReleaseLookup.FetchFailed, api.latestReleaseLookup(app))
    }

    @Test
    fun `adding a source answers for every provider`() = runTest {
        SourceProvider.entries.forEach { provider ->
            assertTrue(
                api.listAccountRepos(provider, "example.org", "Zverik", includeForks = false).isEmpty(),
                "$provider reported repositories it never managed to ask for",
            )
        }
        assertFalse(api.isGiteaInstance("example.org", "Zverik", "every_door"))
    }

    @Test
    fun `the GitHub token check answers`() = runTest {
        api.verifyGithubToken()
        assertFalse(api.shouldSuggestGithubToken())
    }
}
