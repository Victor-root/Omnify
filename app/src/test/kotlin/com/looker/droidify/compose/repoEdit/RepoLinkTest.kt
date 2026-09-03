package com.looker.droidify.compose.repoEdit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading a repository link someone was sent, so the add screen opens with the fields already filled
 * in instead of four things to copy across by hand.
 *
 * What a link carries is up to whoever wrote it: the address alone, the fingerprint with it, the
 * username too. Each part is filled in only when it is there, so a link that says less costs typing
 * rather than failing.
 */
class RepoLinkTest {

    private val address = "https://repo.example.org/fdroid/repo"
    private val fingerprint = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"

    @Test
    fun `the scheme F-Droid clients answer to is an https address`() {
        assertEquals(
            RepoLink(address),
            parseRepoLink("fdroidrepos://repo.example.org/fdroid/repo"),
        )
        assertEquals(
            RepoLink(address),
            parseRepoLink("fdroidrepo://repo.example.org/fdroid/repo"),
        )
    }

    @Test
    fun `a plain address is one too`() {
        assertEquals(RepoLink(address), parseRepoLink(address))
    }

    @Test
    fun `the fingerprint rides along in the query`() {
        assertEquals(
            RepoLink(address, fingerprint = fingerprint),
            parseRepoLink("fdroidrepos://repo.example.org/fdroid/repo?fingerprint=$fingerprint"),
        )
        // F-Droid has written it both ways over the years.
        assertEquals(
            RepoLink(address, fingerprint = fingerprint),
            parseRepoLink("$address?FINGERPRINT=$fingerprint"),
        )
    }

    @Test
    fun `a username rides along as the userinfo, and leaves the address`() {
        // The whole point: the login belongs in the screen's own fields, where it can be seen and
        // corrected, not welded into the address that gets saved and probed.
        assertEquals(
            RepoLink(address, fingerprint = fingerprint, username = "someone"),
            parseRepoLink("fdroidrepos://someone@repo.example.org/fdroid/repo?fingerprint=$fingerprint"),
        )
    }

    @Test
    fun `a password may ride along too, for whoever wants that`() {
        assertEquals(
            RepoLink(address, username = "someone", password = "hunter2"),
            parseRepoLink("fdroidrepos://someone:hunter2@repo.example.org/fdroid/repo"),
        )
    }

    @Test
    fun `a password keeps the characters a URL had to escape`() {
        assertEquals(
            RepoLink(address, username = "someone", password = "p@ss:word/1"),
            parseRepoLink("fdroidrepos://someone:p%40ss%3Aword%2F1@repo.example.org/fdroid/repo"),
        )
    }

    @Test
    fun `an fdroid link page is followed to the address it stands for`() {
        // fdroidrepos:// is not a link anywhere text is merely text, so that page exists to be one.
        assertEquals(
            RepoLink(address, fingerprint = fingerprint),
            parseRepoLink(
                "https://fdroid.link/#https%3A%2F%2Frepo.example.org%2Ffdroid%2Frepo%3Ffingerprint%3D$fingerprint",
            ),
        )
    }

    @Test
    fun `an fdroid link page pointing at another one is followed no further`() {
        // Once and no more: a fragment naming another redirector must not send this round in circles.
        // That page is never itself a repository, so this is a link with nothing at the end of it.
        assertNull(parseRepoLink("https://fdroid.link/#https%3A%2F%2Ffdroid.link%2F%23nonsense"))
        assertNull(parseRepoLink("https://fdroid.link/"))
        assertNull(parseRepoLink("https://fdroid.link/#"))
    }

    @Test
    fun `a trailing slash and an invisible character are no obstacle`() {
        assertEquals(RepoLink(address), parseRepoLink("$address/"))
        assertEquals(RepoLink(address), parseRepoLink("​$address\n"))
    }

    @Test
    fun `something that isn't a repository link is nothing`() {
        assertNull(parseRepoLink(""))
        assertNull(parseRepoLink("repo.example.org/fdroid/repo"))
        assertNull(parseRepoLink("not a link at all"))
        assertNull(parseRepoLink("$address\n$address"))
    }

    @Test
    fun `a port and a path survive the trip`() {
        assertEquals(
            RepoLink("https://repo.example.org:8443/other/repo", username = "someone"),
            parseRepoLink("fdroidrepos://someone@repo.example.org:8443/other/repo"),
        )
    }
}
