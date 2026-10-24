package com.looker.droidify.external

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrereleaseModeTest {

    private fun release(tag: String, prerelease: Boolean) = Release(tag, null, prerelease, emptyList())

    private val stable = release("v1.9.1", prerelease = false)
    private val beta = release("v2.0-beta1", prerelease = true)

    @Test
    fun `each mode lists and offers what it says`() {
        val cases = mapOf(
            PrereleaseMode.HIDDEN to listOf(false, true, false, true),
            PrereleaseMode.SHOWN to listOf(true, true, false, true),
            PrereleaseMode.BOTH to listOf(true, true, true, true),
            PrereleaseMode.ONLY to listOf(true, true, true, false),
        )
        for ((mode, expected) in cases) {
            assertEquals(
                expected,
                listOf(
                    mode.lists(beta, false),
                    mode.lists(stable, false),
                    mode.offers(beta, false),
                    mode.offers(stable, false),
                ),
                "$mode",
            )
        }
    }

    @Test
    fun `a repository publishing only pre-releases has them listed and offered whatever the mode`() {
        for (mode in PrereleaseMode.entries) {
            assertTrue(mode.lists(beta, onlyPrereleases = true), "$mode")
            assertTrue(mode.offers(beta, onlyPrereleases = true), "$mode")
        }
        assertTrue(listOf(beta, release("v2.0-beta0", true)).onlyPrereleases())
        assertFalse(listOf(beta, stable).onlyPrereleases())
        assertFalse(emptyList<Release>().onlyPrereleases())
    }

    @Test
    fun `only a repository publishing both kinds has a pre-release choice to make`() {
        assertTrue(listOf(beta, stable).mixesPrereleases())
        assertFalse(listOf(stable, release("v1.9.0", false)).mixesPrereleases())
        assertFalse(listOf(beta, release("v2.0-beta0", true)).mixesPrereleases())
        assertFalse(emptyList<Release>().mixesPrereleases())
    }

    @Test
    fun `a stable fix published after a pre-release of the next version does not win over it`() {
        assertEquals(beta, listOf(stable, beta).highestVersion())
    }

    @Test
    fun `the final release wins over its own pre-release, newer by date`() {
        val final = release("v2.0", prerelease = false)
        assertEquals(final, listOf(final, beta).highestVersion())
    }

    @Test
    fun `tags without a version number keep their date order`() {
        val nightly = release("nightly", prerelease = true)
        assertEquals(nightly, listOf(nightly, stable).highestVersion())
        assertEquals(stable, listOf(stable, nightly).highestVersion())
    }

    @Test
    fun `a source saved with the former pre-release switch keeps following them`() {
        val json = Json { ignoreUnknownKeys = true }
        val stored = """[
            {"owner": "a", "repo": "on", "includePrereleases": true},
            {"owner": "a", "repo": "off", "includePrereleases": false},
            {"owner": "a", "repo": "new", "prereleases": "SHOWN"},
            {"owner": "a", "repo": "bare"}
        ]"""
        val apps = json.decodeFromString(ListSerializer(StoredExternalAppSerializer), stored)
        assertEquals(
            listOf(PrereleaseMode.BOTH, PrereleaseMode.HIDDEN, PrereleaseMode.SHOWN, PrereleaseMode.HIDDEN),
            apps.map { it.prereleases },
        )
    }
}
