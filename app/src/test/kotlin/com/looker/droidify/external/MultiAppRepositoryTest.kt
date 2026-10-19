package com.looker.droidify.external

import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A repository publishing several apps, followed one app at a time, against a provider answering with
 * the releases such a repository really publishes: nullcpy/rvb (the "NullStore" of the Obtainium
 * issue), whose every release carries a different handful of apps, each told apart only by its APK name.
 *
 * Run with Robolectric for the device's architectures, which the release lookup reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MultiAppRepositoryTest {

    private val settingsRepository = mockk<SettingsRepository>().also {
        coEvery { it.getInitial() } returns Settings()
    }

    @get:Rule
    val tmpFolder = TemporaryFolder()

    /** nullcpy/rvb, newest first, as GitHub lists it. */
    private val rvb = listOf(
        "260293" to listOf(
            "instagram-hushgram-v451.0.0.53.80-arm64-v8a.apk",
            "instagram-hushgram-clone-v451.0.0.53.80-arm64-v8a.apk",
            "linkedin-michii-v4.1.1261-arm-v7a.apk",
            "linkedin-michii-v4.1.1261-arm64-v8a.apk",
        ),
        "260280" to listOf(
            "discord-venus-v349.2-Stable-arm-v7a.apk",
            "discord-venus-v349.2-Stable-arm64-v8a.apk",
        ),
        "260256" to listOf(
            "discord-venus-v348.10-Stable-arm-v7a.apk",
            "discord-venus-v348.10-Stable-arm64-v8a.apk",
            "google-photos-akashsriram-module-v7.96.0.993165104-arm64-v8a.zip",
            "google-photos-akashsriram-v7.96.0.993165104-arm64-v8a.apk",
            "meesho-debakarr-v29.6-arm-v7a.apk",
            "meesho-debakarr-v29.6-arm64-v8a.apk",
        ),
        "260251" to listOf(
            "offline-games-ggyasin-v3.15.3-arm64-v8a.apk",
            "offline-games-ggyasin-v3.14.1-arm-v7a.apk",
        ),
    )

    private val releasesJson = buildJsonArray {
        rvb.forEachIndexed { index, (tag, assets) ->
            addJsonObject {
                put("tag_name", tag)
                put("name", tag)
                put("prerelease", false)
                put("draft", false)
                putJsonArray("assets") {
                    assets.forEachIndexed { assetIndex, name ->
                        addJsonObject {
                            put("name", name)
                            put("browser_download_url", "https://github.com/nullcpy/rvb/releases/download/$tag/$name")
                            put("updated_at", "2026-10-${19 - index}T08:00:${10 + assetIndex}Z")
                            put("id", 1000L * (index + 1) + assetIndex)
                            put("size", 1_000_000L)
                        }
                    }
                }
            }
        }
    }.toString()

    private val api by lazy {
        ExternalApi(
            httpClient = HttpClient(
                MockEngine { request ->
                    // Every release on the first page, nothing past it.
                    val firstPage = request.url.parameters["page"] == "1"
                    val isReleases = request.url.encodedPath.endsWith("/repos/nullcpy/rvb/releases")
                    respond(
                        content = if (isReleases && firstPage) releasesJson else "[]",
                        status = if (isReleases) HttpStatusCode.OK else HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
            settingsRepository = settingsRepository,
            responseCache = ConditionalGetCache(tmpFolder.newFolder()),
        )
    }

    private val repository = ExternalApp(owner = "nullcpy", repo = "rvb")

    @Test
    fun `the apps of the repository are found, each with its own APK filter`() = runTest {
        val families = api.appFamiliesFor(repository)
        assertEquals(
            listOf(
                "Instagram Hushgram",
                "Instagram Hushgram Clone",
                "Linkedin Michii",
                "Discord Venus",
                "Google Photos Akashsriram",
                "Meesho Debakarr",
                "Offline Games Ggyasin",
            ),
            families.map { it.name },
        )
        assertTrue(families.all { it.apkFilter != null })
    }

    @Test
    fun `each app followed gets its own newest release and APK, never another app's`() = runTest {
        val expected = mapOf(
            "Instagram Hushgram" to ("260293" to "instagram-hushgram-v451.0.0.53.80-arm64-v8a.apk"),
            "Instagram Hushgram Clone" to ("260293" to "instagram-hushgram-clone-v451.0.0.53.80-arm64-v8a.apk"),
            "Discord Venus" to ("260280" to "discord-venus-v349.2-Stable-arm64-v8a.apk"),
            "Meesho Debakarr" to ("260256" to "meesho-debakarr-v29.6-arm64-v8a.apk"),
            "Offline Games Ggyasin" to ("260251" to "offline-games-ggyasin-v3.15.3-arm64-v8a.apk"),
        )
        val families = api.appFamiliesFor(repository).associateBy { it.name }
        for ((name, release) in expected) {
            val family = families.getValue(name)
            val app = repository.copy(variant = family.variant, apkFilter = family.apkFilter)
            val lookup = api.latestReleaseLookup(app)
            assertIs<ReleaseLookup.Found>(lookup, name)
            assertEquals(release.first, lookup.release.tag, name)
            assertEquals(
                release.second,
                lookup.release.apkFileName(deviceAbis = listOf("arm64-v8a"), filter = app.apkFilter),
                name,
            )
            // The version list only shows releases carrying this app.
            val history = api.releaseHistory(app)
            assertTrue(history.isNotEmpty(), name)
            assertTrue(history.all { it.hasApkMatchingFilter(app.apkFilter) }, name)
        }
    }

    @Test
    fun `a filter matching no APK says so instead of offering another app`() = runTest {
        val app = repository.copy(variant = "gone", apkFilter = "^some-app-the-repository-dropped[-_]v?\\d")
        assertEquals(ReleaseLookup.NoneMatchApkFilter, api.latestReleaseLookup(app))
    }

    @Test
    fun `the repository followed as a whole still offers its newest release`() = runTest {
        val lookup = api.latestReleaseLookup(repository)
        assertIs<ReleaseLookup.Found>(lookup)
        assertEquals("260293", lookup.release.tag)
    }
}
