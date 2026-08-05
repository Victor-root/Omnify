package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An account or repository name ends up inside the address the app then calls, with the user's token
 * attached. These check that a name stays a name: it can't end the segment it's in, start a query, or
 * climb a level, whatever it's made of.
 *
 * The first test is the one that has to keep passing forever. Escaping that also rewrote ordinary
 * names would change every address the app builds, which is a far more expensive way to break things
 * than the problem being fixed here.
 */
class UrlEncodingTest {

    @Test
    fun `ordinary names come out unchanged`() {
        listOf(
            "Victor-root",
            "Omnify",
            "AdAway",
            "my.app",
            "some_project",
            "F-Droid~1",
            "0123456789",
        ).forEach {
            assertEquals(it, it.urlPathSegment(), "An ordinary name was rewritten")
        }
    }

    @Test
    fun `a slash cannot split the name into two segments`() {
        assertEquals("a%2Fb", "a/b".urlPathSegment())
    }

    @Test
    fun `dot-dot cannot climb out of the path`() {
        assertEquals("%2E%2E", "..".urlPathSegment())
        assertEquals("%2E", ".".urlPathSegment())
        // Only the whole segment is the problem: a name that merely contains dots is left alone,
        // since it resolves into itself like any other name.
        assertEquals("a..b", "a..b".urlPathSegment())
    }

    @Test
    fun `a name cannot start a query or a fragment`() {
        assertEquals("a%3Fb", "a?b".urlPathSegment())
        assertEquals("a%23b", "a#b".urlPathSegment())
        assertEquals("a%26b", "a&b".urlPathSegment())
    }

    @Test
    fun `an escape cannot be smuggled in already escaped`() {
        // Left as-is, "%2E%2E" would arrive at the server as ".." — the percent has to go first.
        assertEquals("%252E%252E", "%2E%2E".urlPathSegment())
    }

    @Test
    fun `spaces and non-ascii are escaped as utf-8`() {
        assertEquals("a%20b", "a b".urlPathSegment())
        assertEquals("caf%C3%A9", "café".urlPathSegment())
    }

    @Test
    fun `repo path escapes both halves and keeps the separator`() {
        assertEquals("owner/repo", repoPath("owner", "repo"))
        assertEquals("ow%2Fner/repo", repoPath("ow/ner", "repo"))
    }

    @Test
    fun `gitlab project path escapes the separator too`() {
        // GitLab wants the whole "owner/repo" as one segment, so here the slash is escaped as well.
        assertEquals("owner%2Frepo", gitlabProjectPath("owner", "repo"))
    }
}
