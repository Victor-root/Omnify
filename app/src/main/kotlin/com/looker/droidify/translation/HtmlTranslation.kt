package com.looker.droidify.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Translates [html] (a README, an app description) while preserving its structure: only the visible
 *  text between tags is translated (code blocks are left alone), so the rendered result keeps the
 *  original's images, headings, lists and links. Any segment that can't be mapped keeps its original
 *  text. [source] is the language when the caller already knows it, otherwise worked out here. */
suspend fun TranslationManager.translateHtml(
    html: String,
    target: String,
    source: String? = null,
): String = withContext(Dispatchers.Default) {
    // Tokenize into tags (kept verbatim) and the text runs between them.
    val tokens = mutableListOf<String>()
    val isTag = mutableListOf<Boolean>()
    var last = 0
    for (match in TAG_REGEX.findAll(html)) {
        if (match.range.first > last) {
            tokens += html.substring(last, match.range.first)
            isTag += false
        }
        tokens += match.value
        isTag += true
        last = match.range.last + 1
    }
    if (last < html.length) {
        tokens += html.substring(last)
        isTag += false
    }

    // Choose the text runs to translate: outside code/pre/script/style and containing a letter.
    val indices = mutableListOf<Int>()
    var skipDepth = 0
    for (i in tokens.indices) {
        if (isTag[i]) {
            val name = tagName(tokens[i])
            if (name != null && name in SKIP_TEXT_TAGS) {
                when {
                    tokens[i].startsWith("</") -> if (skipDepth > 0) skipDepth--
                    !tokens[i].endsWith("/>") -> skipDepth++
                }
            }
        } else if (skipDepth == 0 && tokens[i].any(Char::isLetter)) {
            indices += i
        }
    }
    if (indices.isEmpty()) return@withContext html

    val segments = indices.map { tokens[it].trim() }
    // Worked out once for the whole text, not per piece sent. An on-device engine has to settle on
    // a source language before it can translate at all, and a single run ("OK", a version number, a
    // heading of two words) gets identified as almost anything, so detecting per piece both wastes
    // the work and makes the engine swap language model between pieces of the same page.
    val language = source ?: detectLanguage(segments.joinToString(" ").take(DETECT_SAMPLE_CHARS))
        ?.takeIf { it != "und" }

    val translated = translateSegments(segments, target, language)

    // Splice the translations back in, keeping each run's surrounding whitespace.
    val builder = StringBuilder(html.length)
    val translatableSet = indices.toHashSet()
    var t = 0
    for (i in tokens.indices) {
        if (i in translatableSet) {
            val original = tokens[i]
            builder.append(original.takeWhile(Char::isWhitespace))
            builder.append(translated[t])
            builder.append(original.takeLastWhile(Char::isWhitespace))
            t++
        } else {
            builder.append(tokens[i])
        }
    }
    builder.toString()
}

/** Translates [segments] in newline-joined batches (each <= [MAX_TRANSLATE_CHUNK] chars), which the
 *  engines return line-for-line. On the rare batch that doesn't map 1:1, its segments are translated
 *  one by one, and any segment that still fails keeps its original text. */
private suspend fun TranslationManager.translateSegments(
    segments: List<String>,
    target: String,
    source: String?,
): List<String> {
    val out = arrayOfNulls<String>(segments.size)
    var i = 0
    while (i < segments.size) {
        val start = i
        val batch = StringBuilder()
        while (i < segments.size) {
            val seg = segments[i]
            if (batch.isNotEmpty() && batch.length + 1 + seg.length > MAX_TRANSLATE_CHUNK) break
            if (batch.isNotEmpty()) batch.append('\n')
            batch.append(seg)
            i++
            if (seg.length >= MAX_TRANSLATE_CHUNK) break
        }
        val count = i - start
        val parts = translate(batch.toString(), target, source).split('\n')
        if (parts.size == count) {
            for (j in 0 until count) out[start + j] = parts[j]
        } else {
            for (j in 0 until count) {
                out[start + j] = runCatching {
                    translate(segments[start + j], target, source)
                }.getOrDefault(segments[start + j])
            }
        }
    }
    return out.map { it ?: "" }
}

private const val MAX_TRANSLATE_CHUNK = 1500

/** How much of a text is enough to tell what language it is in. Language identification is a
 *  whole-text judgement, so more text past this adds nothing but the cost of scanning it. */
private const val DETECT_SAMPLE_CHARS = 2000

private val TAG_REGEX = Regex("<[^>]+>")

/** HTML tags whose inner text must not be translated, so code stays code. */
private val SKIP_TEXT_TAGS = setOf("code", "pre", "script", "style")

/** The lowercase element name of an HTML tag token (e.g. `<a href=…>` gives "a"), or null if unreadable. */
private fun tagName(tag: String): String? =
    Regex("^</?\\s*([A-Za-z0-9]+)").find(tag)?.groupValues?.get(1)?.lowercase()
