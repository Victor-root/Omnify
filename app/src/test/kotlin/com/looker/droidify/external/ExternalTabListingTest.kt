package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which sources the External tab lists, on the phone and the television alike. */
class ExternalTabListingTest {

    private fun source(repo: String, enabled: Boolean) =
        ExternalApp(owner = "owner", repo = repo, enabled = enabled)

    @Test
    fun `a source that is switched off is not listed`() {
        val on = source("on", enabled = true)
        val off = source("off", enabled = false)
        assertEquals(listOf(on), listOf(on, off).shownOnExternalTab(emptySet()))
    }

    @Test
    fun `an app the user hid is not listed`() {
        val kept = source("kept", enabled = true)
        val hidden = source("hidden", enabled = true)
        assertEquals(listOf(kept), listOf(kept, hidden).shownOnExternalTab(setOf(hidden.key)))
    }

    @Test
    fun `nothing is listed when nothing is switched on`() {
        val all = listOf(source("a", enabled = false), source("b", enabled = false))
        assertEquals(emptyList(), all.shownOnExternalTab(emptySet()))
    }

    @Test
    fun `the order of the sources is kept`() {
        val all = listOf("c", "a", "b").map { source(it, enabled = true) }
        assertEquals(all, all.shownOnExternalTab(emptySet()))
    }
}
