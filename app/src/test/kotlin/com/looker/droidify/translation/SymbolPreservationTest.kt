package com.looker.droidify.translation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two halves, and the second matters as much as the first: emoji surviving an engine that eats them
 * (the first block), and text without any emoji reaching that engine completely untouched (the
 * second). Holding an emoji back is only worth anything if it never changes what gets translated.
 *
 * [eatsEmoji] stands in for on-device ML Kit, which drops the pictographs it holds no token for; the
 * uppercasing stands in for "the engine returned something different", so a result that still carries
 * its emoji proves the emoji never went through the engine at all.
 */
class SymbolPreservationTest {

    /** Whatever it is handed, uppercased, with every pictograph dropped. */
    private suspend fun eatsEmoji(text: String): String = text
        .filterNot { Character.getType(it.code) == Character.OTHER_SYMBOL.toInt() }
        .uppercase()

    private var seenByEngine: String? = null

    /** Records what the engine was handed, then returns it unchanged. */
    private suspend fun recording(text: String): String {
        seenByEngine = text
        return text
    }

    @Test
    fun `an emoji opening a line survives an engine that drops it`() = runTest {
        val result = translatePreservingSymbols("⚡ reset the throttling;", ::eatsEmoji)
        assertEquals("⚡ RESET THE THROTTLING;", result)
    }

    @Test
    fun `every line keeps its own emoji`() = runTest {
        val input = "🧠 first line\n🔄 second line\n✅ third line"
        val result = translatePreservingSymbols(input, ::eatsEmoji)
        assertEquals("🧠 FIRST LINE\n🔄 SECOND LINE\n✅ THIRD LINE", result)
    }

    @Test
    fun `an emoji closing a line survives too`() = runTest {
        assertEquals("DONE ✅", translatePreservingSymbols("done ✅", ::eatsEmoji))
    }

    @Test
    fun `an emoji at both ends survives`() = runTest {
        assertEquals(
            "⚡ FAST ✅",
            translatePreservingSymbols("⚡ fast ✅", ::eatsEmoji),
        )
    }

    @Test
    fun `a multi code point emoji survives whole`() = runTest {
        // Woman technologist: woman, joiner, laptop, with the variation selector the sequence needs.
        val emoji = "👩‍💻️"
        assertEquals("$emoji BUILDS IT", translatePreservingSymbols("$emoji builds it", ::eatsEmoji))
    }

    @Test
    fun `a flag survives whole`() = runTest {
        val flag = "🇫🇷"
        assertEquals("$flag FRENCH", translatePreservingSymbols("$flag french", ::eatsEmoji))
    }

    @Test
    fun `an emoji inside a sentence is left to the engine`() = runTest {
        // The honest limit of this approach, pinned so it can't quietly change meaning later.
        assertEquals("BUILT WITH  AND SHIPPED", translatePreservingSymbols("built with ❤ and shipped", ::eatsEmoji))
    }

    @Test
    fun `plain text reaches the engine byte for byte`() = runTest {
        val input = "first line\nsecond line"
        translatePreservingSymbols(input, ::recording)
        assertEquals(input, seenByEngine)
    }

    @Test
    fun `leading whitespace alone is not held back`() = runTest {
        val input = "    indented line"
        translatePreservingSymbols(input, ::recording)
        assertEquals(input, seenByEngine)
    }

    @Test
    fun `punctuation and maths signs are not held back`() = runTest {
        val input = "+ a list item\n- another one\n\"quoted\"\n= equals"
        translatePreservingSymbols(input, ::recording)
        assertEquals(input, seenByEngine)
    }

    @Test
    fun `a line that is only an emoji leaves the whole text alone`() = runTest {
        // Its stripped form would be a blank line, which an engine may drop, taking the alignment of
        // every other line with it.
        val input = "🎉\nwe shipped"
        translatePreservingSymbols(input, ::recording)
        assertEquals(input, seenByEngine)
    }

    @Test
    fun `blank lines already in the text are no obstacle`() = runTest {
        val input = "⚡ first\n\nlast"
        assertEquals("⚡ FIRST\n\nLAST", translatePreservingSymbols(input, ::eatsEmoji))
    }

    @Test
    fun `the engine reshaping the text gives its own result back`() = runTest {
        // Nothing can be lined up once the line count changes, so the emoji is lost rather than
        // reattached to the wrong line, which is what happened before any of this existed.
        val joiner: suspend (String) -> String = { it.replace("\n", " ") }
        assertEquals("first second", translatePreservingSymbols("⚡ first\n✅ second", joiner))
    }

    @Test
    fun `the engine is called exactly once`() = runTest {
        var calls = 0
        val counting: suspend (String) -> String = { calls++; it }
        translatePreservingSymbols("⚡ one\ntwo\n🎉", counting)
        assertEquals(1, calls)
    }

    @Test
    fun `empty text is handed over as is`() = runTest {
        translatePreservingSymbols("", ::recording)
        assertEquals("", seenByEngine)
    }
}
