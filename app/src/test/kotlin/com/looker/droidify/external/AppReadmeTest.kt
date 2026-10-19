package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Finding the README of one app among several in a repository, and reading it from its own folder. */
class AppReadmeTest {

    /** Part of ente-io/ente's tree: one README per app and per platform, plus the repository's own. */
    private val ente = listOf(
        "README.md",
        "android/apps/ensu/README.md",
        "apple/apps/ensu/README.md",
        "desktop/README.md",
        "mobile/apps/auth/README.md",
        "mobile/apps/auth/lib/main.dart",
        "mobile/apps/photos/README.md",
        "rust/apps/ensu/README.md",
        "web/apps/accounts/README.md",
    )

    @Test
    fun `an app's README is the one in the folder named after it`() {
        assertEquals("mobile/apps/auth/README.md", appReadmePath(ente, "auth"))
        assertEquals("mobile/apps/photos/README.md", appReadmePath(ente, "photos"))
    }

    @Test
    fun `the Android side wins when the app has a folder per platform`() {
        assertEquals("android/apps/ensu/README.md", appReadmePath(ente, "ensu"))
    }

    @Test
    fun `an app with no folder of its own has no README of its own`() {
        // nullcpy/rvb's apps exist only as APKs in its releases.
        assertNull(appReadmePath(listOf("README.md", "docs/README.md"), "google-maps-bearinmindcat"))
        // The repository's own README is never taken for an app's.
        assertNull(appReadmePath(ente, ""))
    }

    @Test
    fun `relative links and images are made relative to the repository root`() {
        val html = """<img src="assets/logo.png"><a href="../../README.md">up</a>""" +
            """<a href="./docs/">docs</a><img src="../shared/badge.svg">"""
        assertEquals(
            """<img src="mobile/apps/auth/assets/logo.png"><a href="mobile/README.md">up</a>""" +
                """<a href="mobile/apps/auth/docs/">docs</a><img src="mobile/apps/shared/badge.svg">""",
            rebaseRelativeUrls(html, "mobile/apps/auth"),
        )
    }

    @Test
    fun `anchors, root links and other sites are left alone`() {
        val html = """<a href="#install">i</a><a href="/LICENSE">l</a>""" +
            """<a href="https://ente.io">e</a><a href="mailto:a@b.c">m</a><img src="data:image/png;base64,AA">""" +
            """<img src="//cdn.example.com/x.png">"""
        assertEquals(html, rebaseRelativeUrls(html, "mobile/apps/auth"))
    }
}
