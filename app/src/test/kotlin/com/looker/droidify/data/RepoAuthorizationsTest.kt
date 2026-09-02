package com.looker.droidify.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Deciding which repository's login, if any, an image URL is entitled to.
 *
 * Both directions matter. Without a login, a repository behind a password shows nothing at all: no
 * logo, no app icons, no screenshots. With the wrong one, a password typed for a private repository
 * would be handed to whatever else happens to sit on that server.
 */
class RepoAuthorizationsTest {

    private val private = "Basic c29tZW9uZTpzZWNyZXQ="
    private val other = "Basic b3RoZXI6c2VjcmV0"
    private val credentials = mapOf(
        "https://repo.example.org/fdroid/repo" to private,
        "https://shared.example/second/repo/" to other,
    )

    @Test
    fun `a file under the repository gets that repository's login`() {
        assertEquals(
            private,
            credentials.authorizationFor("https://repo.example.org/fdroid/repo/icons/logo.png"),
        )
        assertEquals(
            private,
            credentials.authorizationFor("https://repo.example.org/fdroid/repo/org.example.app/en-US/icon.png"),
        )
    }

    @Test
    fun `a trailing slash on the stored address changes nothing`() {
        assertEquals(other, credentials.authorizationFor("https://shared.example/second/repo/icons/logo.png"))
    }

    @Test
    fun `nothing is given to another repository on the same server`() {
        assertNull(credentials.authorizationFor("https://shared.example/first/repo/icons/logo.png"))
        assertNull(credentials.authorizationFor("https://shared.example/icons/logo.png"))
    }

    @Test
    fun `a path that merely starts the same is a different repository`() {
        // The separator is what makes this safe: without it, /fdroid/repo would also claim
        // /fdroid/repository-of-someone-else.
        assertNull(credentials.authorizationFor("https://repo.example.org/fdroid/repo2/icons/logo.png"))
        assertNull(credentials.authorizationFor("https://repo.example.org/fdroid/repository/icons/logo.png"))
    }

    @Test
    fun `nothing is given to another host`() {
        assertNull(credentials.authorizationFor("https://f-droid.org/repo/icons/logo.png"))
        assertNull(credentials.authorizationFor("https://repo.example.org.evil.example/fdroid/repo/icons/logo.png"))
    }

    @Test
    fun `the same address over plain http is not the same repository`() {
        assertNull(credentials.authorizationFor("http://repo.example.org/fdroid/repo/icons/logo.png"))
    }

    @Test
    fun `the address on its own is not a file under it`() {
        assertNull(credentials.authorizationFor("https://repo.example.org/fdroid/repo"))
    }

    @Test
    fun `no stored logins means nothing is ever added`() {
        assertNull(emptyMap<String, String>().authorizationFor("https://repo.example.org/fdroid/repo/icons/logo.png"))
    }
}
