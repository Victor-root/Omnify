package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Turning a repo's bare slug into a name worth showing, for the sources whose repo carries no
 * Android source to read a real one from — brave/brave-browser above all, whose own README says as
 * much: nothing here beyond issues, releases and the wiki, so [ExternalApi.fetchRepoMetadata] never
 * finds a manifest label for it, however often it is rescanned.
 */
class PrettifyRepoNameTest {

    @Test
    fun `hyphens become spaces and each word is capitalised`() {
        assertEquals("Brave Browser", prettifyRepoName("brave-browser"))
    }

    @Test
    fun `underscores become spaces too`() {
        assertEquals("Simple Gallery", prettifyRepoName("simple_gallery"))
    }

    @Test
    fun `a single lowercase word gets its first letter capitalised`() {
        assertEquals("Syncthing", prettifyRepoName("syncthing"))
    }

    @Test
    fun `a word already carrying its own capitals is left untouched`() {
        // NewPipe, K9Mail, microG: already a deliberately chosen style, not a slug to fix.
        assertEquals("NewPipe", prettifyRepoName("NewPipe"))
        assertEquals("K9Mail", prettifyRepoName("K9Mail"))
        assertEquals("microG", prettifyRepoName("microG"))
    }

    @Test
    fun `each hyphenated word keeps whatever casing it already had beyond its first letter`() {
        assertEquals("Qr Code Scanner", prettifyRepoName("qr-code-scanner"))
        assertEquals("NoteBook App", prettifyRepoName("noteBook-app"))
    }

    @Test
    fun `a doubled or leading separator does not leave an empty word`() {
        assertEquals("Double Hyphen", prettifyRepoName("double--hyphen"))
        assertEquals("Leading Hyphen", prettifyRepoName("-leading-hyphen"))
    }
}
