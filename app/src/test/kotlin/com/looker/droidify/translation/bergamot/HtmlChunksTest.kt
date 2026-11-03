package com.looker.droidify.translation.bergamot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HtmlChunksTest {

    private fun bodies(html: String, count: Int) = splitForWorkers(html, count).map { it.body }

    @Test
    fun aPageIsCutBetweenItsBlocks() {
        assertEquals(
            listOf("<p>One <b>bold</b> line.</p>", "<ul><li>First</li><li>Second</li></ul>"),
            bodies("<p>One <b>bold</b> line.</p><ul><li>First</li><li>Second</li></ul>", 2),
        )
    }

    @Test
    fun aSingleLongListIsCutBetweenItsItems() {
        val pieces = splitForWorkers("<ul><li>Alpha one</li><li>Beta two</li><li>Gamma three</li></ul>", 2)
        assertEquals(2, pieces.size)
        assertEquals("<ul><li>Alpha one</li><li>Beta two</li>", pieces[0].body)
        assertEquals("<ul><li>Alpha one</li><li>Beta two</li></ul>", pieces[0].html)
        assertEquals("<ul><li>Gamma three</li></ul>", pieces[1].html)
    }

    @Test
    fun aSingleLongParagraphIsCutBetweenSentences() {
        val pieces = splitForWorkers("<p>First sentence here. Second sentence there.</p>", 2)
        assertEquals(listOf("<p>First sentence here. ", "Second sentence there.</p>"), pieces.map { it.body })
        assertEquals("<p>Second sentence there.</p>", pieces[1].html)
    }

    @Test
    fun aLessThanSignInTheTextDoesNotStopTheCuts() {
        assertEquals(
            listOf("Needs Android <9 or less. ", "Works offline."),
            bodies("Needs Android <9 or less. Works offline.", 2),
        )
    }

    @Test
    fun codeIsNeverCut() {
        val html = "<pre>line one\nline two\nline three</pre>"
        assertEquals(listOf(html), bodies(html, 2))
    }

    @Test
    fun thePiecesAlwaysJoinBackIntoTheOriginal() {
        val html = "<h2>Title</h2>\n<p>Text <a href=\"x\">link</a>. More text! End?</p>\n<ul><li>a</li><li>b\n</ul>"
        for (count in 1..4) assertEquals(html, bodies(html, count).joinToString(""))
    }

    @Test
    fun unwrappingTakesOffOnlyTheAddedTags() {
        val piece = splitForWorkers("<ul><li>Alpha one</li><li>Beta two</li><li>Gamma three</li></ul>", 2)[1]
        // The list's own end tag is the page's, so it stays.
        assertEquals(" <li>Gamma trois</li></ul>", piece.unwrap("<ul> <li>Gamma trois</li></ul>"))
        assertNull(piece.unwrap("<li>Gamma trois</li>"))
    }
}
