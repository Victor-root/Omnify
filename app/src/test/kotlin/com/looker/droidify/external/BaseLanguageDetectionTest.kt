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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A Flutter or web app keeps its text out of Android's resources, with an Android shell around it. */
class BaseLanguageDetectionTest {

    @TempDir
    lateinit var cacheDir: File

    private val app = ExternalApp(owner = "owner", repo = "repo")
    private val settingsRepository: SettingsRepository = mockk {
        coEvery { getInitial() } answers { Settings() }
    }

    private fun api(files: Map<String, String>) = ExternalApi(
        httpClient = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val body = when {
                    path.endsWith("/git/trees/HEAD") -> """{"tree":[${
                        files.keys.joinToString(",") { """{"path":"$it","type":"blob","sha":"0"}""" }
                    }]}"""
                    else -> files.entries.firstOrNull { path.endsWith(it.key) }?.value
                }
                respond(
                    content = body.orEmpty(),
                    status = if (body != null) HttpStatusCode.OK else HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                )
            },
        ),
        settingsRepository = settingsRepository,
        responseCache = ConditionalGetCache(cacheDir),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private val germanDart = """
        Text('Für diese Linie gibt es im Moment keine Abfahrten');
        Text('Fügen Sie eine Haltestelle hinzu und wählen Sie die Richtung');
        Text("Der Fahrplan ist im Moment nicht verfügbar und das ist nicht gut");
        Text('Sie können die Richtung in der Liste auswählen und auf die Linie gehen');
    """.trimIndent()

    @Test
    fun `a Flutter app's language is read from its Dart source`() = runTest {
        val files = mapOf(
            "android/app/src/main/res/values/styles.xml" to "<resources/>",
            "lib/main.dart" to germanDart,
        )
        assertEquals("de", api(files).detectBaseLanguage(app))
    }

    @Test
    fun `a web app's language is read from its templates`() = runTest {
        val vue = germanDart.lines().joinToString("\n") { line ->
            "<p>${line.substringAfter('(').trim('\'', '"', ')', ';')}</p>"
        }
        assertEquals("de", api(mapOf("src/pages/Home.vue" to vue)).detectBaseLanguage(app))
    }

    @Test
    fun `a literal the size of a dictionary is read like any other`() = runTest {
        val words = "'" + List(200_000) { "wort" }.joinToString("\\n") + "';\n"
        assertEquals("de", api(mapOf("lib/main.dart" to words + germanDart)).detectBaseLanguage(app))
    }

    @Test
    fun `generated Dart says nothing of the UI`() = runTest {
        assertNull(api(mapOf("lib/app_state.g.dart" to germanDart)).detectBaseLanguage(app))
    }

    @Test
    fun `a project's tests say nothing of its UI`() = runTest {
        assertNull(api(mapOf("test/main_test.dart" to germanDart)).detectBaseLanguage(app))
    }

    @Test
    fun `an Android shell's styles are not English strings`() = runTest {
        val files = mapOf("android/app/src/main/res/values/styles.xml" to "<resources/>", "lib/main.dart" to germanDart)
        assertEquals(emptyList(), api(files).fetchSourceLocales(app))
    }

    @Test
    fun `a project's strings file still counts as its default`() = runTest {
        val files = mapOf("app/src/main/res/values/strings.xml" to "<resources/>")
        assertEquals(listOf("en"), api(files).fetchSourceLocales(app))
    }
}
