package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the apps of a repository publishing several in one release stream are told apart. */
class ReleaseFamiliesTest {

    private fun release(tag: String) = Release(tag, null, false, emptyList())

    private fun familiesOf(vararg tags: String) = releaseFamilies(tags.map(::release), unmarkedName = "Repo")

    @Test
    fun `a prefix before the version names the app`() {
        // ente-io/ente, newest first.
        val families = familiesOf(
            "photos-v1.2.3", "auth-v4.4.26-rc", "photos-desktop-v1.7.0", "auth-v4.4.25", "locker-v1.0.9",
        )
        assertEquals(listOf("Photos", "Auth", "Photos Desktop", "Locker"), families.map { it.name })
        assertEquals(listOf("photos-v", "auth-v", "photos-desktop-v", "locker-v"), families.map { it.keyword })
        assertEquals("auth-v4.4.26-rc", families[1].latest.tag)
    }

    @Test
    fun `each keyword keeps its own app and no other`() {
        val tags = listOf("photos-v1.2.3", "photos-desktop-v1.7.0", "photos-offline-v0.0.4", "auth-v4.4.25")
        for (family in familiesOf(*tags.toTypedArray())) {
            assertEquals(
                listOf(family.latest.tag),
                tags.map(::release).filter { it.matchesIncludeFilter(family.keyword) }.map { it.tag },
            )
        }
    }

    @Test
    fun `a suffix after the version names the app when there is no prefix`() {
        // bitwarden/android: Authenticator and Password Manager.
        val families = familiesOf("v2026.9.1-bwa", "v2026.9.1-bwpm", "v2026.9.0-bwa")
        assertEquals(listOf("-bwa", "-bwpm"), families.map { it.keyword })
    }

    @Test
    fun `a regular repository is a single app`() {
        val families = familiesOf(
            "v2.0.0", "2.0.0-rc1", "v1.9.0-beta.2", "release-1.8", "v1.7.0+45", "Version 1.6", "v.1.5.0",
        )
        assertEquals(1, families.size)
        assertEquals(null, families.single().keyword)
        assertEquals("Repo", families.single().name)
    }

    @Test
    fun `a tag without a version is left out`() {
        assertEquals(listOf("v1.0.0"), familiesOf("nightly", "v1.0.0", "latest").map { it.latest.tag })
    }

    @Test
    fun `the unmarked app is reached by excluding the others`() {
        val families = familiesOf("v3.0.0", "wear-v1.0.0", "tv-v2.0.0")
        val main = families.first { it.keyword == null }
        val (include, exclude) = families.keywordFiltersFor(main, include = "", exclude = "nightly")
        assertEquals("", include)
        assertEquals("nightly, wear-v, tv-v", exclude)
        val (wearInclude, wearExclude) = families.keywordFiltersFor(families[1], include = "", exclude = "nightly")
        assertEquals("wear-v", wearInclude)
        assertEquals("nightly", wearExclude)
        assertTrue(release("v3.0.0").isAllowedBy(false, include, exclude))
    }
}
