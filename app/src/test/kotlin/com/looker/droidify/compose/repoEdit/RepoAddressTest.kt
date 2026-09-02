package com.looker.droidify.compose.repoEdit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading what was typed into the address field of the add-a-repository screen.
 *
 * The screen refuses to save while this answers null, and says "invalid address" with nothing to act
 * on, so anything it wrongly rejects is a repository the user simply cannot add. One character that
 * draws nothing was enough, which is a thing nobody can find by looking at the field.
 */
class RepoAddressTest {

    private val address = "https://repo.example.org/fdroid/repo"

    @Test
    fun `an ordinary address comes back as itself`() {
        assertEquals(address, normalizeRepoAddress(address))
        assertEquals("https://f-droid.org/repo", normalizeRepoAddress("https://f-droid.org/repo"))
        assertEquals("http://192.168.1.10:8080/repo", normalizeRepoAddress("http://192.168.1.10:8080/repo"))
    }

    @Test
    fun `whitespace around it is not part of it`() {
        // The reported bug. A keyboard adds a space after a word it completed and a pasted line brings
        // a newline; neither can belong to a URL, and both made this answer "not an address at all".
        assertEquals(address, normalizeRepoAddress("$address "))
        assertEquals(address, normalizeRepoAddress(" $address"))
        assertEquals(address, normalizeRepoAddress("  $address  "))
        assertEquals(address, normalizeRepoAddress("$address\n"))
        assertEquals(address, normalizeRepoAddress("\t$address\t"))
    }

    @Test
    fun `a non-breaking space is whitespace too`() {
        // What a copy from a web page brings along, and what nobody reading the field would ever see.
        assertEquals(address, normalizeRepoAddress("$address "))
    }

    @Test
    fun `a character that draws nothing is not part of it, wherever it sits`() {
        // Every one of these refuses the whole address, and not one of them can be found by looking
        // at the field: a byte order mark out of a file, the bidi marks a web page carries, a soft
        // hyphen, a zero-width space, a line ending left on a paste.
        assertEquals(address, normalizeRepoAddress("﻿$address"))
        assertEquals(address, normalizeRepoAddress("‎$address"))
        assertEquals(address, normalizeRepoAddress("https://repo.example.org‏/fdroid/repo"))
        assertEquals(address, normalizeRepoAddress("https://repo.example.org/fdroid­/repo"))
        assertEquals(address, normalizeRepoAddress("https://repo.​example.org/fdroid/repo"))
        assertEquals(address, normalizeRepoAddress("https://repo.example.org/fdroid/repo\r\n"))
        assertEquals(address, normalizeRepoAddress("\r\n$address\r\n"))
    }

    @Test
    fun `two lines are two things, not one address`() {
        // The reported bug, and the reason it was so hard to see: a paste that carried the address
        // twice, and a paste that carried the field's own label with it. Line breaks used to be
        // dropped like any other character nobody can see, which welded the two halves into one
        // address that was never typed, passed every check, and was then looked for on a server that
        // had of course never heard of it.
        assertNull(normalizeRepoAddress("$address\r\n$address"))
        assertNull(normalizeRepoAddress("Address\r\n$address"))
        assertNull(normalizeRepoAddress("$address\nhttps://f-droid.org/repo"))
        // The same break inside one address is no different: which half was meant is a guess, and
        // guessing wrong about a repository address is guessing wrong about where an APK comes from.
        assertNull(normalizeRepoAddress("https://repo.example.org/\nfdroid/repo"))
    }

    @Test
    fun `one line among blank ones is still that one line`() {
        assertEquals(address, normalizeRepoAddress("\n\n$address"))
        assertEquals(address, normalizeRepoAddress("$address\n   \n"))
    }

    @Test
    fun `the one line is what the screen asks about`() {
        // Handed over as it stands, spacing and all: taking that out is the next step's job.
        assertEquals("  $address  ", singleAddressLine("  $address  "))
        assertEquals(address, singleAddressLine("$address\n"))
        assertNull(singleAddressLine("$address\n$address"))
        assertNull(singleAddressLine(""))
        assertNull(singleAddressLine("\n \n"))
    }

    @Test
    fun `a space inside it is still not an address`() {
        // A space between two visible characters is left alone on purpose: the user can see it and
        // correct it, and a real URL escapes its spaces, so one there is a typo worth reporting
        // rather than something to repair behind their back.
        assertNull(normalizeRepoAddress("https://repo.example.org/fdroid /repo"))
        assertNull(normalizeRepoAddress("https://repo example.org/repo"))
        assertNull(normalizeRepoAddress("https://repo.example.org/fdroid /repo"))
    }

    @Test
    fun `a trailing slash is dropped, so one repository has one spelling`() {
        assertEquals(address, normalizeRepoAddress("$address/"))
        assertEquals(address, normalizeRepoAddress("$address/ "))
    }

    @Test
    fun `a path is tidied rather than refused`() {
        assertEquals("https://repo.example.org/repo", normalizeRepoAddress("https://repo.example.org/fdroid/../repo"))
    }

    @Test
    fun `credentials and a port survive`() {
        assertEquals(
            "https://someone@repo.example.org:8443/fdroid/repo",
            normalizeRepoAddress("https://someone@repo.example.org:8443/fdroid/repo"),
        )
    }

    @Test
    fun `nothing typed is not an address`() {
        assertNull(normalizeRepoAddress(""))
        assertNull(normalizeRepoAddress("   "))
        assertNull(normalizeRepoAddress("\n"))
    }

    @Test
    fun `an address with no scheme is not one either`() {
        // Nothing here can guess whether that was meant to be http or https, and guessing wrong on a
        // repository address is guessing wrong about where an APK comes from.
        assertNull(normalizeRepoAddress("repo.example.org/fdroid/repo"))
        assertNull(normalizeRepoAddress("/fdroid/repo"))
    }

    @Test
    fun `the repository path ending is what the duplicate check ignores`() {
        assertEquals("https://repo.example.org", stripRepoPathSuffix("https://repo.example.org/fdroid/repo"))
        assertEquals("https://repo.example.org", stripRepoPathSuffix("https://repo.example.org/repo"))
        assertEquals("https://repo.example.org", stripRepoPathSuffix("https://repo.example.org/repo/"))
        assertEquals("https://repo.example.org", stripRepoPathSuffix("https://repo.example.org"))
    }

    @Test
    fun `only a whole path segment counts as that ending`() {
        assertEquals("https://repo.example.org/myrepo", stripRepoPathSuffix("https://repo.example.org/myrepo"))
        assertEquals("https://repo.example.org/fdroid", stripRepoPathSuffix("https://repo.example.org/fdroid"))
    }

    @Test
    fun `the two spellings of one repository strip to the same thing`() {
        // What stops the same repository being added twice under two names.
        assertEquals(
            stripRepoPathSuffix(normalizeRepoAddress("https://repo.example.org/fdroid/repo")!!),
            stripRepoPathSuffix(normalizeRepoAddress("https://repo.example.org/")!!),
        )
    }
}
