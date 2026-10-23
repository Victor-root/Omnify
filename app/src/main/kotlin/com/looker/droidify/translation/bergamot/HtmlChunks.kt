package com.looker.droidify.translation.bergamot

import kotlin.math.abs

/**
 * One of the parts a page is translated in, side by side with the others: [body], a slice of the page,
 * with the elements open around it closed after it and opened again before it, so that each part reads
 * as whole HTML on its own. [unwrap] takes those added tags off again once translated.
 */
internal class HtmlPiece(
    private val opened: List<String>,
    val body: String,
    private val closed: List<String>,
) {
    val html: String
        get() = opened.joinToString("") { "<$it>" } + body + closed.asReversed().joinToString("") { "</$it>" }

    /** [translated], this piece's [html] through the engine, without the tags added around [body], or null
     *  when the engine didn't leave them where they were put. */
    fun unwrap(translated: String): String? {
        val start = Regex("^" + opened.joinToString("") { "\\s*<$it\\b[^>]*>" }).find(translated) ?: return null
        val rest = translated.substring(start.range.last + 1)
        val end = Regex(closed.asReversed().joinToString("") { "</$it>\\s*" } + "$").find(rest) ?: return null
        return rest.substring(0, end.range.first)
    }
}

/**
 * [html] cut into at most [count] pieces of about the same length, joined back by their [HtmlPiece.body]
 * into [html] exactly. A cut falls at the end of a block, a list item, a line or a sentence, wherever the
 * nearest one to an even share is, inside a list or a long paragraph too: cutting only between top-level
 * blocks left one part for a description that was a single long list, a single paragraph, or held a "<"
 * that was text, and every part of it then waited on one core. Never inside code.
 */
internal fun splitForWorkers(html: String, count: Int): List<HtmlPiece> {
    val candidates = cutPoints(html).filter { it.index > 0 && it.index < html.length }
    val chosen = mutableListOf<CutPoint>()
    for (share in 1 until count) {
        val target = html.length.toLong() * share / count
        val after = chosen.lastOrNull()?.index ?: 0
        chosen += candidates.filter { it.index > after }.minByOrNull { abs(it.index - target) } ?: break
    }
    val bounds = listOf(CutPoint(0, emptyList())) + chosen + CutPoint(html.length, emptyList())
    return bounds.zipWithNext { start, end ->
        HtmlPiece(opened = start.open, body = html.substring(start.index, end.index), closed = end.open)
    }
}

/** Where a piece may start, and the elements open there, outermost first. */
private class CutPoint(val index: Int, val open: List<String>)

private fun cutPoints(html: String): List<CutPoint> {
    val points = mutableListOf<CutPoint>()
    val open = ArrayDeque<String>()
    fun mark(index: Int) {
        if (open.none { it in VERBATIM_ELEMENTS }) points += CutPoint(index, open.toList())
    }
    var index = 0
    while (index < html.length) {
        val char = html[index]
        if (char == '<') {
            val end = html.indexOf('>', index)
            val tag = if (end < 0) null else TAG_NAME.find(html.substring(index + 1, end))
            val name = tag?.groupValues?.get(2)?.lowercase()
            if (name == null || name !in KNOWN_ELEMENTS) {
                // Not an element, a "<" in the text: read on as text.
                index++
                continue
            }
            val closing = tag.groupValues[1] == "/"
            when {
                closing -> if (name in open) {
                    while (open.removeLast() != name) Unit
                }
                name in VOID_ELEMENTS || html[end - 1] == '/' -> Unit
                else -> {
                    // A new item or paragraph ends the one before it, whose end tag HTML lets go unwritten.
                    if (name in OPTIONAL_END_ELEMENTS && open.lastOrNull() == name) open.removeLast()
                    open.addLast(name)
                }
            }
            index = end + 1
            if (name in BREAK_ELEMENTS && (closing || name in VOID_ELEMENTS)) mark(index)
        } else {
            index++
            when {
                char == '\n' -> mark(index)
                char in SENTENCE_ENDS && html.getOrNull(index) == ' ' -> mark(index + 1)
            }
        }
    }
    return points
}

private val TAG_NAME = Regex("^(/?)([A-Za-z][A-Za-z0-9]*)")

private val SENTENCE_ENDS = setOf('.', '!', '?')

private val KNOWN_ELEMENTS = setOf(
    "a", "abbr", "article", "aside", "audio", "b", "bdi", "bdo", "big", "blockquote", "br", "button",
    "caption", "center", "cite", "code", "dd", "del", "details", "dfn", "div", "dl", "dt", "em", "figcaption",
    "figure", "font", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "i", "iframe",
    "img", "input", "ins", "kbd", "label", "li", "main", "mark", "nav", "ol", "p", "picture", "pre", "q", "s",
    "samp", "section", "small", "source", "span", "strike", "strong", "sub", "summary", "sup", "table",
    "tbody", "td", "tfoot", "th", "thead", "tr", "tt", "u", "ul", "var", "video", "wbr",
)

private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
)

private val OPTIONAL_END_ELEMENTS = setOf("p", "li", "dt", "dd", "tr", "td", "th")

/** Elements a piece may end after. */
private val BREAK_ELEMENTS = setOf(
    "p", "ul", "ol", "dl", "li", "dt", "dd", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote",
    "table", "tr", "div", "section", "article", "details", "figure", "br", "hr",
)

/** Kept whole, their text never cut. */
private val VERBATIM_ELEMENTS = setOf("pre", "code")
