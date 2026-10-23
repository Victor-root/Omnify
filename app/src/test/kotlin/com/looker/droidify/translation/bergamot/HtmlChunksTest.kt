package com.looker.droidify.translation.bergamot

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlChunksTest {

    @Test
    fun aDescriptionIsCutAfterEachTopLevelBlock() {
        val html = "<p>One <b>bold</b> line.</p><ul><li>First</li><li>Second</li></ul><p>Last<br>line.</p>"
        assertEquals(
            listOf(
                "<p>One <b>bold</b> line.</p>",
                "<ul><li>First</li><li>Second</li></ul>",
                "<p>Last<br>line.</p>",
            ),
            splitIntoBlocks(html),
        )
    }

    @Test
    fun plainTextIsCutAtBlankLines() {
        assertEquals(
            listOf("First paragraph.\n\n", "Second one."),
            splitIntoBlocks("First paragraph.\n\nSecond one."),
        )
    }

    @Test
    fun aPageWrappedWholeStaysInOnePart() {
        val html = "<div><p>One</p><p>Two</p></div>"
        assertEquals(listOf(html), splitIntoBlocks(html))
    }

    @Test
    fun thePartsAlwaysJoinBackIntoTheOriginal() {
        val html = "<h2>Title</h2>\n\n<p>Text <a href=\"x\">link</a>.</p>\n<img src=\"y\">\n<p>End</p>\n"
        assertEquals(html, splitIntoBlocks(html).joinToString(""))
    }

    @Test
    fun runsAreBalancedAndInOrder() {
        val parts = listOf("aaaa", "bbbb", "cccc", "dddd")
        assertEquals(listOf("aaaabbbb", "ccccdddd"), groupInOrder(parts, 2))
        assertEquals(parts, groupInOrder(parts, 4))
    }

    @Test
    fun neverMoreRunsThanParts() {
        assertEquals(listOf("only"), groupInOrder(listOf("only"), 4))
    }
}
