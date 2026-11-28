package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reading a catalogue app's changelog link as something the app can show itself. */
class LinkedChangelogTest {

    @Test
    fun `a GitHub releases page is read from its release notes`() {
        assertEquals(
            LinkedChangelog.Releases(SourceProvider.GITHUB, "Kunzisoft", "KeePassDX"),
            linkedChangelog("https://github.com/Kunzisoft/KeePassDX/releases"),
        )
        assertEquals(
            LinkedChangelog.Releases(SourceProvider.GITHUB, "owner", "repo"),
            linkedChangelog("https://github.com/owner/repo/releases/latest"),
        )
    }

    @Test
    fun `a GitHub file page is read raw, relative to its folder`() {
        assertEquals(
            LinkedChangelog.File(
                rawUrl = "https://raw.githubusercontent.com/owner/repo/HEAD/docs/CHANGELOG.md",
                baseUrl = "https://raw.githubusercontent.com/owner/repo/HEAD/docs/",
            ),
            linkedChangelog("https://github.com/owner/repo/blob/HEAD/docs/CHANGELOG.md"),
        )
        assertEquals(
            LinkedChangelog.File(
                rawUrl = "https://raw.githubusercontent.com/owner/repo/HEAD/CHANGELOG",
                baseUrl = "https://raw.githubusercontent.com/owner/repo/HEAD/",
            ),
            linkedChangelog("https://raw.githubusercontent.com/owner/repo/HEAD/CHANGELOG"),
        )
    }

    @Test
    fun `GitLab keeps nested groups`() {
        assertEquals(
            LinkedChangelog.Releases(SourceProvider.GITLAB, "group/sub", "repo"),
            linkedChangelog("https://gitlab.com/group/sub/repo/-/releases"),
        )
        assertEquals(
            LinkedChangelog.File(
                rawUrl = "https://gitlab.com/group/repo/-/raw/main/CHANGELOG.md",
                baseUrl = "https://gitlab.com/group/repo/-/raw/main/",
            ),
            linkedChangelog("https://gitlab.com/group/repo/-/blob/main/CHANGELOG.md"),
        )
    }

    @Test
    fun `a Codeberg file comes from its API, its images from the web raw path`() {
        assertEquals(
            LinkedChangelog.File(
                rawUrl = "https://codeberg.org/api/v1/repos/owner/repo/raw/CHANGELOG.md?ref=main",
                baseUrl = "https://codeberg.org/owner/repo/raw/branch/main/",
            ),
            linkedChangelog("https://codeberg.org/owner/repo/src/branch/main/CHANGELOG.md"),
        )
        assertEquals(
            LinkedChangelog.Releases(SourceProvider.CODEBERG, "owner", "repo"),
            linkedChangelog("https://codeberg.org/owner/repo/releases"),
        )
    }

    @Test
    fun `other links stay in the browser`() {
        listOf(
            "https://gitlab.com/souch/SMP/tags",
            "https://github.com/jroal/a2dpvolume/wiki/Log-of-Updates",
            "https://github.com/owner/repo/tree/master/fastlane/metadata/android/en-US/changelogs",
            "https://eddyizm.github.io/tempus/CHANGELOG.html",
            "https://github.com/owner/repo/blob/main/docs/changelog.html",
            "https://framagit.org/owner/repo/-/releases",
            "not a url",
        ).forEach { assertNull(linkedChangelog(it), it) }
    }
}
