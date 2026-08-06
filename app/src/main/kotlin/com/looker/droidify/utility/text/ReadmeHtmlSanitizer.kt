package com.looker.droidify.utility.text

/**
 * Removes from a rendered README the handful of constructs that can *act* rather than merely
 * *display*, leaving everything else byte-for-byte as it was.
 *
 * A README is written by whoever owns the source repository, and the Markdown renderer passes raw
 * HTML through the way the forges themselves do. That is fine as long as the page can only draw
 * itself, but [com.looker.droidify.compose.externalApps.ReadmeWebView] can be told to run embedded
 * JavaScript (the "JavaScript in READMEs" setting, off by default), and the WebView it draws into
 * sits inside the app's own screen. A page that can run script there can put a convincing form or
 * dialog in front of the user and call out to the network, so the constructs that allow it are
 * taken out here, once, right before the document is handed to the WebView. Sanitizing at that one
 * point rather than where the HTML is produced also covers what was already written to
 * [com.looker.droidify.compose.externalApps.ReadmeCache] by an earlier version.
 *
 * Deliberately a deny list, not an allow list: a README's whole point is to look the way its author
 * laid it out, and READMEs use a wide, unbounded mix of tags and attributes for that
 * (`<picture>`/`<source>` for light/dark screenshots, `<details>`, `<p align>`, `<img width>`, task
 * list `<input type=checkbox>`, inline SVG badges). An allow list would quietly break some of those
 * with every README that uses one this file's author didn't think of. What is taken out is only:
 *
 *  - [DISCARDED_WITH_CONTENT]: elements that execute or embed a foreign document. Their content goes
 *    with them, since it is code or styling, not text meant to be read.
 *  - [UNWRAPPED]: elements that redirect the page or where its parts resolve to. Their content stays
 *    and still renders; only the element itself goes. `<form>` is here rather than above so a README
 *    showing a form still shows its fields, it just has nowhere to submit them.
 *  - event-handler attributes (`onclick`, `onerror`, …) and [DISCARDED_ATTRIBUTES].
 *  - any [URL_ATTRIBUTES] value whose scheme isn't one that can only ever fetch or display
 *    ([isAllowedUrl]): this is what stops `javascript:`, `intent:`, `file:` and `content:` links.
 *
 * Tags that are kept are rebuilt from their parsed name and attributes rather than copied across
 * verbatim, so a malformed tag can't smuggle a second one through in an attribute name (`<scr<script>`
 * and friends): anything that doesn't parse as a plain name is dropped instead of re-emitted.
 */
object ReadmeHtmlSanitizer {

    /** Elements dropped along with everything up to their closing tag: they run code, style the whole
     *  page, or embed a document of their own. */
    private val DISCARDED_WITH_CONTENT = setOf(
        "script", "style", "iframe", "object", "embed", "applet",
        "frame", "frameset", "noembed", "noframes",
    )

    /** Elements dropped on their own, keeping whatever they contain: they steer the page (`<base>`
     *  rewrites what every relative URL resolves against, `<meta http-equiv=refresh>` navigates,
     *  `<link>` pulls in outside stylesheets) or give a place to send the user's input (`<form>`). */
    private val UNWRAPPED = setOf("form", "base", "meta", "link")

    /** Attributes dropped wherever they appear. `srcdoc` is a whole nested document; `formaction`
     *  re-targets a submission past the removed `<form>`; `ping` fires an unseen POST on click. */
    private val DISCARDED_ATTRIBUTES = setOf("srcdoc", "formaction", "ping")

    /** Attributes holding a URL, whose scheme is therefore checked ([isAllowedUrl]). */
    private val URL_ATTRIBUTES = setOf(
        "href", "src", "xlink:href", "poster", "data", "cite", "action", "background", "longdesc",
    )

    /**
     * Schemes a README may point at: the ones GitHub's own sanitizer keeps, plus the two an app's
     * README plainly reaches for (`tel:`, and `matrix:` alongside the `xmpp:` GitHub already allows).
     * Matching what github.com renders means a link that works when the project's README is read
     * there works here too, and one that doesn't was already not a link to begin with.
     *
     * An allow list here rather than the deny list the rest of this file uses, because the set of
     * schemes is not the open-ended thing a README's markup is: every one of them hands the URL to
     * whichever app on the device claims it, chosen by the README's author, and the ones that matter
     * most (`javascript:` running in the page, `file:`/`content:` reaching storage, `intent:` naming
     * a component outright) are exactly the ones nobody would think to deny.
     */
    private val ALLOWED_SCHEMES = setOf("http", "https", "mailto", "xmpp", "matrix", "tel")

    private val TAG_NAME_REGEX = Regex("[A-Za-z][A-Za-z0-9-]*")
    private val ATTRIBUTE_NAME_REGEX = Regex("[A-Za-z_:][A-Za-z0-9_.:-]*")
    private val NUMERIC_REFERENCE_REGEX = Regex("&#(x?)([0-9a-fA-F]+);?", RegexOption.IGNORE_CASE)

    fun sanitize(html: String): String {
        val output = StringBuilder(html.length)
        var index = 0
        while (index < html.length) {
            val start = html.indexOf('<', index)
            if (start < 0) {
                output.append(html, index, html.length)
                break
            }
            output.append(html, index, start)
            val skipped = skipNonElement(html, start)
            if (skipped != null) {
                index = skipped
                continue
            }
            val tag = parseTag(html, start)
            if (tag == null) {
                // Not a tag at all: a bare "<" in prose, or one that runs off the end of the
                // document. Escaped rather than copied across, so it is unambiguously text and can't
                // be read back as the start of a tag once this fragment is spliced into the page
                // (which puts characters after it that weren't there when it was parsed here). It
                // renders exactly as the "<" it already did.
                output.append("&lt;")
                index = start + 1
                continue
            }
            index = tag.end
            when {
                tag.lowerName in DISCARDED_WITH_CONTENT -> index = tag.skipContent(html)
                tag.lowerName in UNWRAPPED -> Unit
                tag.isClosing -> output.append("</").append(tag.name).append('>')
                else -> output.append(tag.render())
            }
        }
        return output.toString()
    }

    /**
     * The index just past the comment, doctype or processing instruction at [start], or null when
     * what's there is none of those. Comments carry nothing worth showing, and one left in place is
     * a way to hide an unbalanced tag from anyone reading the source.
     */
    private fun skipNonElement(html: String, start: Int): Int? = when {
        html.startsWith("<!--", start) ->
            html.indexOf("-->", start + 4).let { if (it < 0) html.length else it + 3 }

        html.startsWith("<!", start) || html.startsWith("<?", start) ->
            html.indexOf('>', start).let { if (it < 0) html.length else it + 1 }

        else -> null
    }

    /**
     * Names keep the case they were written in, and only [lowerName] (and the attribute names, where
     * they are checked) is folded: `viewBox`, `preserveAspectRatio` and the rest of inline SVG mean
     * something different in lower case, and READMEs carry inline SVG often enough that normalising
     * it would be a visible break.
     */
    private class Tag(
        val name: String,
        val isClosing: Boolean,
        val isSelfClosing: Boolean,
        val attributes: List<Pair<String, String?>>,
        val end: Int,
    ) {
        val lowerName: String = name.lowercase()

        /** Where to carry on reading from, having dropped this element's content along with it. An
         *  element that opens nothing (a closing or self-closing tag) has no content to drop. */
        fun skipContent(html: String): Int =
            if (isClosing || isSelfClosing) end else skipToClosingTag(html, lowerName, end)

        fun render(): String = buildString {
            append('<').append(name)
            attributes.forEach { (attributeName, value) ->
                append(' ').append(attributeName)
                if (value != null) append("=\"").append(escapeAttributeValue(value)).append('"')
            }
            if (isSelfClosing) append(" /")
            append('>')
        }
    }

    /**
     * Reads the tag starting at [start] (which must be its `<`), or null when what's there isn't one.
     * A tag that never closes counts as not one: treating the rest of the document as its content
     * would silently swallow it.
     */
    private fun parseTag(html: String, start: Int): Tag? {
        var position = start + 1
        val isClosing = position < html.length && html[position] == '/'
        if (isClosing) position++
        val nameEnd = scan(html, position) { it.isLetterOrDigit() || it == '-' }
        val name = html.substring(position, nameEnd)
        if (!TAG_NAME_REGEX.matches(name)) return null

        position = nameEnd
        val attributes = mutableListOf<Pair<String, String?>>()
        while (position < html.length) {
            position = scan(html, position) { it.isWhitespace() }
            if (position >= html.length) return null
            if (html[position] == '>') return Tag(name, isClosing, false, attributes, position + 1)
            if (html.startsWith("/>", position)) {
                return Tag(name, isClosing, true, attributes, position + 2)
            }
            val attribute = parseAttribute(html, position) ?: return null
            position = attribute.end
            if (keepAttribute(attribute.name, attribute.value)) {
                attributes += attribute.name to attribute.value
            }
        }
        return null
    }

    private class ParsedAttribute(val name: String, val value: String?, val end: Int)

    /**
     * Reads one attribute starting at [start], or null when the tag runs off the end of the document
     * part-way through it. A character no attribute name can start with (a stray `/` or `=` inside a
     * tag) comes back as an empty name, which [keepAttribute] refuses like any other malformed one,
     * so the scan steps over it rather than stalling on it.
     */
    private fun parseAttribute(html: String, start: Int): ParsedAttribute? {
        val nameEnd = scan(html, start) {
            !it.isWhitespace() && it != '=' && it != '>' && it != '/'
        }
        if (nameEnd == start) return ParsedAttribute("", null, start + 1)
        val name = html.substring(start, nameEnd)
        val afterEquals = scan(html, nameEnd) { it.isWhitespace() }
        // No "=" means a valueless attribute (`disabled`, `checked`), and the whitespace just skipped
        // belongs to whatever attribute comes next rather than to this one.
        if (afterEquals >= html.length || html[afterEquals] != '=') {
            return ParsedAttribute(name, null, nameEnd)
        }
        val valueStart = scan(html, afterEquals + 1) { it.isWhitespace() }
        if (valueStart >= html.length) return null
        val quote = html[valueStart]
        if (quote != '"' && quote != '\'') {
            val end = scan(html, valueStart) { !it.isWhitespace() && it != '>' }
            return ParsedAttribute(name, html.substring(valueStart, end), end)
        }
        val end = html.indexOf(quote, valueStart + 1)
        if (end < 0) return null
        return ParsedAttribute(name, html.substring(valueStart + 1, end), end + 1)
    }

    private fun keepAttribute(name: String, value: String?): Boolean {
        if (!ATTRIBUTE_NAME_REGEX.matches(name)) return false
        val lowerName = name.lowercase()
        return when {
            lowerName.startsWith("on") -> false
            lowerName in DISCARDED_ATTRIBUTES -> false
            lowerName in URL_ATTRIBUTES && value != null -> isAllowedUrl(value)
            else -> true
        }
    }

    /**
     * Whether [url] is one a link may be followed to: relative, or naming a scheme from
     * [ALLOWED_SCHEMES].
     *
     * Public because the WebView asks exactly this of every navigation before handing it to the
     * system (see `ReadmeWebView`'s `shouldOverrideUrlLoading`), and a navigation can arrive there
     * without ever having been an attribute this sanitized: a redirect, or a script when the
     * setting allows one. Sharing the answer keeps the two from drifting apart.
     *
     * The value is normalised the way a browser reads it before the scheme is looked at: character
     * references are resolved and whitespace and control characters are dropped, so `java&#115;cript:`
     * and `java\tscript:` are recognised for what they are instead of passing as an unknown,
     * harmless-looking scheme.
     */
    fun isFollowableUrl(url: String): Boolean {
        val normalized = normalize(url)
        val colon = normalized.indexOf(':')
        if (colon < 0) return true
        // A colon reached after the path, query or fragment has started belongs to them, not to a
        // scheme: "docs/a:b", "?q=a:b" and "#a:b" are all relative.
        val separator = normalized.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (separator in 0 until colon) return true
        return normalized.substring(0, colon).lowercase() in ALLOWED_SCHEMES
    }

    /**
     * [isFollowableUrl], or an inline image. `data:` is how this app itself inlines a README's
     * relative images (see `ExternalApi.inlineRelativeImages`), so an attribute has to keep taking
     * one, while `data:text/html`, a document with an origin of its own, must not, and neither is
     * ever somewhere a tapped link should go, which is why this stays out of [isFollowableUrl].
     */
    private fun isAllowedUrl(url: String): Boolean =
        isFollowableUrl(url) || normalize(url).startsWith("data:image/", ignoreCase = true)

    private fun normalize(url: String): String = decodeCharacterReferences(url)
        .filterNot { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }

    /** Resolves `&#NN;` / `&#xNN;` references, and the named ones for the characters that matter to
     *  [isAllowedUrl]'s reading of a scheme. Everything else is left as it stands. */
    private fun decodeCharacterReferences(value: String): String {
        if ('&' !in value) return value
        return NUMERIC_REFERENCE_REGEX.replace(value) { match ->
            val radix = if (match.groupValues[1].isEmpty()) 10 else 16
            val code = match.groupValues[2].toIntOrNull(radix)
            if (code != null && code in 1..MAX_CODE_POINT) String(Character.toChars(code)) else match.value
        }.replace("&colon;", ":", ignoreCase = true)
            .replace("&tab;", "\t", ignoreCase = true)
            .replace("&newline;", "\n", ignoreCase = true)
    }

    private const val MAX_CODE_POINT = 0x10FFFF

    /** The index just past `</[name]>` starting the search at [from], or the end of [html] when the
     *  element is never closed: an unclosed `<script>` runs to the end of the document, so dropping
     *  the rest is dropping exactly what a browser would have executed. */
    private fun skipToClosingTag(html: String, name: String, from: Int): Int {
        var position = from
        while (position < html.length) {
            val next = html.indexOf("</", position)
            if (next < 0) return html.length
            position = next + 2
            if (!html.startsWith(name, position, ignoreCase = true)) continue
            val close = html.indexOf('>', position + name.length)
            if (close < 0) return html.length
            if (html.substring(position + name.length, close).isBlank()) return close + 1
        }
        return html.length
    }
}

/** The index of the first character at or after [start] that [predicate] rejects, or the end of
 *  [html]. One place for every "read while this holds" step of the scan above. */
private inline fun scan(html: String, start: Int, predicate: (Char) -> Boolean): Int {
    var position = start
    while (position < html.length && predicate(html[position])) position++
    return position
}

/** The value as it can go back inside double quotes. `&` is left as it stands so a reference the
 *  author already wrote (`&amp;` in a query string) survives instead of being escaped twice. */
private fun escapeAttributeValue(value: String): String =
    value.replace("\"", "&quot;").replace("<", "&lt;")
