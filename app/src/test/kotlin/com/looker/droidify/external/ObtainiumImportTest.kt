package com.looker.droidify.external

import com.looker.droidify.assets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An Obtainium export read into external sources. The export in the resources is written field for
 * field the way Obtainium's own App.toJson and export schema write one, and holds the very entry the
 * issue's author shared, alongside every kind of source Obtainium can follow.
 */
class ObtainiumImportTest {

    private val import = parseObtainiumExport(assets("obtainium_export.json")!!.readText())

    private fun app(path: String) = import.apps.single { it.path == path }

    @Test
    fun `keeps every GitHub, GitLab and Codeberg app once, and names the others`() {
        assertEquals(
            listOf(
                "gezimos/inkOS",
                "uazo/cromite",
                "gkd-kit/gkd",
                "group/sub/project",
                "owner/forge-app",
                "owner/app",
                "team/apps/tool",
                "owner/no-package-yet",
            ),
            import.apps.map { it.path },
        )
        assertEquals(
            listOf("F-Droid", "Izzy App", "Mirror App", "HTML Tracked", "Direct APK"),
            import.unsupported,
        )
    }

    @Test
    fun `a plain GitHub app keeps its name and package, and is switched on`() {
        val inkOs = app("gezimos/inkOS")
        assertEquals(SourceProvider.GITHUB, inkOs.provider)
        assertEquals("", inkOs.host)
        assertEquals("inkOS", inkOs.label)
        assertFalse(inkOs.nameOverridden)
        assertEquals("app.inkos", inkOs.packageName)
        assertTrue(inkOs.enabled)
        assertEquals(PrereleaseMode.HIDDEN, inkOs.prereleases)
        assertNull(inkOs.apkFilter)
        assertNull(inkOs.versionIncludeFilter)
    }

    @Test
    fun `settings with a counterpart are carried over`() {
        val cromite = app("uazo/cromite")
        assertEquals("My Browser", cromite.label)
        assertTrue(cromite.nameOverridden)
        assertEquals(PrereleaseMode.BOTH, cromite.prereleases)
        assertEquals("arm64", cromite.apkFilter)
        assertEquals("stable", cromite.versionIncludeFilter)
        assertEquals("beta,rc", app("owner/forge-app").versionIncludeFilter)
    }

    @Test
    fun `settings Omnify cannot say are left out rather than turned into something else`() {
        val gkd = app("gkd-kit/gkd")
        // "Everything but x86" would become "only x86".
        assertNull(gkd.apkFilter)
        // A real pattern is not a list of words.
        assertNull(gkd.versionIncludeFilter)
    }

    @Test
    fun `nested GitLab groups stay part of the owner`() {
        val nested = app("group/sub/project")
        assertEquals(SourceProvider.GITLAB, nested.provider)
        assertEquals("", nested.host)
    }

    @Test
    fun `self-hosted Forgejo and GitLab keep their host`() {
        val forgejo = app("owner/app")
        assertEquals(SourceProvider.CODEBERG, forgejo.provider)
        assertEquals("git.example.org", forgejo.host)
        val gitlab = app("team/apps/tool")
        assertEquals(SourceProvider.GITLAB, gitlab.provider)
        assertEquals("gitlab.example.org", gitlab.host)
    }

    @Test
    fun `a placeholder id is not taken for a package`() {
        assertNull(app("owner/no-package-yet").packageName)
    }

    @Test
    fun `reads the older shapes of an export`() {
        val entry = """
            {"id": "app.inkos", "url": "https://github.com/gezimos/inkOS", "author": "gezimos",
             "name": "inkOS", "additionalSettings": "{\"includePrereleases\": true}"}
        """.trimIndent()
        val wrapped = parseObtainiumExport("""{"apps": [$entry]}""")
        val bare = parseObtainiumExport("[$entry]")
        assertEquals(listOf("gezimos/inkOS"), wrapped.apps.map { it.path })
        assertEquals(wrapped, bare)
        assertEquals(PrereleaseMode.BOTH, bare.apps.single().prereleases)
    }

    @Test
    fun `apps followed in one repository each become a source of their own`() {
        // The export the second person on the issue shared: three apps of nullcpy/rvb, a repository
        // whose every release carries a dozen, each followed with its own APK filter.
        val nullStore = parseObtainiumExport(assets("obtainium_export_nullstore.json")!!.readText())
        assertEquals(listOf("nullcpy/rvb"), nullStore.apps.map { it.path }.distinct())
        assertEquals(
            listOf(
                "GITHUB/nullcpy/rvb#offline-games",
                "GITHUB/nullcpy/rvb#meesho",
                "GITHUB/nullcpy/rvb#instagram-clone",
            ),
            nullStore.apps.map { it.key },
        )
        assertEquals(setOf("GITHUB/nullcpy/rvb"), nullStore.apps.map { it.repoKey }.toSet())
        assertEquals(listOf("Offline Games", "Meesho", "Instagram Clone"), nullStore.apps.map { it.label })
        assertEquals("^meesho-debakarr-v.*\\.apk$", nullStore.apps[1].apkFilter)
        assertEquals("com.meesho.supply", nullStore.apps[1].packageName)
        assertTrue(nullStore.unsupported.isEmpty())
    }

    @Test
    fun `a repository followed alone keeps its plain key`() {
        assertEquals("GITHUB/gezimos/inkOS", app("gezimos/inkOS").key)
        assertEquals("", app("gezimos/inkOS").variant)
    }

    @Test
    fun `a file that is not an export is refused`() {
        assertFailsWith<IllegalArgumentException> { parseObtainiumExport("not json") }
        assertFailsWith<IllegalArgumentException> { parseObtainiumExport("""{"settings": {}}""") }
    }

    /** The share link from the issue, as Obtainium's web page wraps it. */
    private val redirectLink = "https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%7B%22id%22" +
        "%3A%22com.reddit.frontpage%22%2C%22name%22%3A%22Reddit%20(Morphe)%22%2C%22author%22%3A%22nullcpy%22" +
        "%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fnullcpy%2Frvb%22%2C%22additionalSettings%22%3A%22%7B%5C" +
        "%22apkFilterRegEx%5C%22%3A%5C%22%5Ereddit-morphe-v.*%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D"

    @Test
    fun `an Obtainium share link brings its project and its APK filter`() {
        val draft = parseObtainiumLink(redirectLink)
        assertEquals("https://github.com/nullcpy/rvb", draft?.url)
        assertEquals("^reddit-morphe-v.*\\.apk$", draft?.apkFilter)
        assertEquals("", draft?.name)
    }

    @Test
    fun `the link inside the web page reads the same, encoded or not`() {
        val inner = redirectLink.substringAfter("r=")
        val encodedApp = "obtainium://app/" + inner.substringAfter("app%2F")
        assertEquals(parseObtainiumLink(redirectLink), parseObtainiumLink(encodedApp))
        val decoded = """obtainium://app/{"id":"a.b","url":"https://github.com/o/r",""" +
            """"additionalSettings":"{\"appName\":\"Mine\",\"includePrereleases\":true,""" +
            """\"apkFilterRegEx\":\"v.+\\\\.apk\"}"}"""
        val draft = parseObtainiumLink(decoded)
        assertEquals("Mine", draft?.name)
        assertEquals(PrereleaseMode.BOTH, draft?.prereleases)
        assertEquals("v.+\\.apk", draft?.apkFilter)
    }

    @Test
    fun `an add link carries the address alone`() {
        assertEquals(
            SourceDraft(url = "https://github.com/o/r"),
            parseObtainiumLink("obtainium://add/https%3A%2F%2Fgithub.com%2Fo%2Fr"),
        )
    }

    @Test
    fun `links Omnify can't follow are turned away`() {
        assertNull(parseObtainiumLink("https://github.com/o/r"))
        assertNull(parseObtainiumLink("obtainium://add/https://f-droid.org/packages/a.b"))
        assertNull(parseObtainiumLink("""obtainium://app/{"id":"a.b","url":"https://apkmirror.com/x"}"""))
        assertNull(parseObtainiumLink("obtainium://app/not json"))
    }
}
