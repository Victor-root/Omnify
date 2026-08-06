package com.looker.droidify.translation

/** A line cut into the pictographic run that opens it, the text worth translating, and the
 *  pictographic run that closes it. */
private data class SymbolSplit(val prefix: String, val core: String, val suffix: String) {
    val stripped get() = prefix.isNotEmpty() || suffix.isNotEmpty()
}

/**
 * Translates [text] through [translate] with every emoji that opens or closes a line kept out of the
 * engine's reach.
 *
 * On-device ML Kit silently drops any emoji its model holds no token for, and inconsistently: on one
 * README's release notes it kept the brain, the no-entry sign and the antenna bars while losing the
 * recycle arrows, the high voltage sign, the fire extinguisher and the check mark, leaving the
 * translated page looking half stripped. README bullets and headings open with one very often, so this
 * is the common case rather than an exotic one. An emoji sitting inside a sentence stays at the
 * engine's mercy, which no amount of surgery around the outside of a line can change.
 *
 * A run only counts when it actually holds a pictograph, never punctuation or a maths sign, so text
 * carrying no emoji reaches the engine byte for byte as it does today. Whatever the engine gives back
 * that can't be lined up again (a line count that no longer matches) is returned as it came, which is
 * the result this whole function would have produced anyway before it existed.
 */
internal suspend fun translatePreservingSymbols(
    text: String,
    translate: suspend (String) -> String,
): String {
    val lines = text.split('\n')
    val splits = lines.map { it.splitSymbolRuns() }
    if (splits.none { it.stripped }) return translate(text)
    // A line stripped down to nothing would go out as a blank line, which an engine is free to swallow.
    // Leave the whole text alone rather than gamble the alignment every other line depends on.
    if (lines.zip(splits).any { (line, split) -> line.isNotBlank() && split.core.isBlank() }) {
        return translate(text)
    }
    val translated = translate(splits.joinToString("\n") { it.core }).split('\n')
    if (translated.size != lines.size) return translated.joinToString("\n")
    return splits
        .mapIndexed { index, split -> split.prefix + translated[index] + split.suffix }
        .joinToString("\n")
}

private fun String.splitSymbolRuns(): SymbolSplit {
    var start = 0
    var openingHasSymbol = false
    while (start < length) {
        val codePoint = codePointAt(start)
        if (!isDecoration(codePoint)) break
        if (isSymbol(codePoint)) openingHasSymbol = true
        start += Character.charCount(codePoint)
    }
    // Leading whitespace on its own is not worth touching: keeping it in the text sent out means a line
    // without any emoji is passed on completely untouched.
    if (!openingHasSymbol) start = 0
    var end = length
    var closingHasSymbol = false
    while (end > start) {
        val codePoint = codePointBefore(end)
        if (!isDecoration(codePoint)) break
        if (isSymbol(codePoint)) closingHasSymbol = true
        end -= Character.charCount(codePoint)
    }
    if (!closingHasSymbol) end = length
    return SymbolSplit(substring(0, start), substring(start, end), substring(end))
}

/** Emoji and the other pictographs Unicode files under OTHER_SYMBOL, which is what a translation model
 *  is liable to have no token for. Deliberately not the maths, currency or modifier categories: a line
 *  opening with `+` or `$` is text, and moving it out of the engine's way would change its reading. */
private fun isSymbol(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()

/** The code points that only ever appear as part of an emoji: the zero-width joiner, both variation
 *  selectors, the enclosing keycap and the five skin tones. They carry no meaning on their own, so a
 *  run made only of these never counts as holding a pictograph. */
private fun isSequencePart(codePoint: Int): Boolean = when (codePoint) {
    0x200D, 0xFE0E, 0xFE0F, 0x20E3 -> true
    else -> codePoint in 0x1F3FB..0x1F3FF
}

private fun isDecoration(codePoint: Int): Boolean =
    isSymbol(codePoint) || isSequencePart(codePoint) || Character.isWhitespace(codePoint)
