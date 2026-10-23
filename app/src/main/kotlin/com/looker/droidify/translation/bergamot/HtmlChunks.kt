package com.looker.droidify.translation.bergamot

/**
 * [html] cut where it can be translated in separate parts: after each block that closes with no element
 * left open around it (a paragraph, a list, a heading…), and at each blank line of plain text. Joined
 * back together, the parts are [html] exactly. An element left open past the end, a wrapper around the
 * whole page say, leaves it in one part.
 */
internal fun splitIntoBlocks(html: String): List<String> {
    val cuts = mutableListOf<Int>()
    var depth = 0
    var index = 0
    while (index < html.length) {
        val char = html[index]
        if (char == '<') {
            val end = html.indexOf('>', index)
            if (end < 0) break
            val tag = html.substring(index + 1, end)
            val name = tag.trimStart('/').takeWhile { it.isLetterOrDigit() }.lowercase()
            when {
                tag.startsWith("!") || tag.startsWith("?") || tag.endsWith("/") || name in VOID_ELEMENTS -> Unit
                tag.startsWith("/") -> {
                    if (depth > 0) depth--
                    if (depth == 0 && name in BLOCK_ELEMENTS) cuts += end + 1
                }
                else -> depth++
            }
            index = end + 1
        } else {
            if (depth == 0 && html.startsWith("\n\n", index)) cuts += index + 2
            index++
        }
    }
    val parts = mutableListOf<String>()
    var start = 0
    for (cut in cuts.distinct()) {
        if (cut > start && cut < html.length) {
            parts += html.substring(start, cut)
            start = cut
        }
    }
    parts += html.substring(start)
    return parts
}

/**
 * [parts] gathered, in order, into at most [count] runs of about the same length, for that many workers
 * to translate side by side. Never more runs than parts, and none empty.
 */
internal fun groupInOrder(parts: List<String>, count: Int): List<String> {
    val runs = count.coerceIn(1, parts.size.coerceAtLeast(1))
    val total = parts.sumOf { it.length }
    val groups = mutableListOf<StringBuilder>()
    var current = StringBuilder()
    parts.forEachIndexed { index, part ->
        current.append(part)
        val left = parts.size - index - 1
        val target = total.toLong() * (groups.size + 1) / runs
        val doneLength = groups.sumOf { it.length } + current.length
        if (groups.size < runs - 1 && left > 0 && doneLength >= target) {
            groups += current
            current = StringBuilder()
        }
    }
    groups += current
    return groups.map { it.toString() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
}

private val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
)

private val BLOCK_ELEMENTS = setOf(
    "p", "ul", "ol", "dl", "li", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "table", "div",
    "section", "article", "details", "figure",
)
