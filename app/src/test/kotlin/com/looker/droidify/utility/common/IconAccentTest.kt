package com.looker.droidify.utility.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An app icon is nearly always a logo sitting on a plate, and the plate is nearly always the bigger of
 * the two. Taking the most common colour therefore took the plate: measured on a real ReVanced Manager
 * icon, 58% near-white against 42% pink, so its page came out white. These cover the ranking that
 * fixes that, and the two ways it must refuse to answer rather than answer badly.
 */
class IconAccentTest {

    private fun icon(vararg runs: Pair<Int, Int>): IntArray {
        val pixels = ArrayList<Int>()
        runs.forEach { (colour, count) -> repeat(count) { pixels.add(colour) } }
        return pixels.toIntArray()
    }

    private val white = 0xFFF0F0F0.toInt()
    private val black = 0xFF0B0B0B.toInt()
    private val pink = 0xFFDF80B7.toInt()
    private val transparent = 0x00000000

    private fun colourOf(accent: IconAccent?): Int {
        assertTrue(accent is IconAccent.Colour, "expected a colour, got $accent")
        return accent.argb
    }

    @Test
    fun `the logo wins over the larger plate it sits on`() {
        // ReVanced Manager's real proportions.
        val accent = iconAccentOf(icon(white to 58, pink to 42))

        assertEquals(pink, colourOf(accent))
    }

    @Test
    fun `a black plate loses to its logo just as a white one does`() {
        val accent = iconAccentOf(icon(black to 80, pink to 20))

        assertEquals(pink, colourOf(accent))
    }

    @Test
    fun `an icon that really is one colour keeps that colour`() {
        // Signal's icon is overwhelmingly its own blue. Nothing to correct, and correcting it anyway
        // would be the same bug in reverse.
        val blue = 0xFF2833FD.toInt()
        val accent = iconAccentOf(icon(blue to 90, white to 10))

        assertEquals(blue, colourOf(accent))
    }

    @Test
    fun `a black and white icon reports monochrome rather than picking one`() {
        // Neither black nor white works everywhere: which of them is visible depends on the theme, so
        // the answer is deferred to the screen instead of guessed here.
        assertEquals(IconAccent.Monochrome, iconAccentOf(icon(white to 60, black to 40)))
    }

    @Test
    fun `greys count as monochrome, not as a colour`() {
        // A mid grey is neither black nor white but has no hue either, and a grey page is the same
        // disappointment as a white one.
        val grey = 0xFF808080.toInt()
        assertEquals(IconAccent.Monochrome, iconAccentOf(icon(grey to 70, white to 30)))
    }

    @Test
    fun `a tinted white is still white`() {
        // Saturation alone is fooled here: this barely-blue white scores 0.36, as colourful on paper
        // as a real pastel, because so little room is left at that lightness. Its lightness is what
        // gives it away.
        val tintedWhite = 0xFFF0F2F8.toInt()
        assertEquals(IconAccent.Monochrome, iconAccentOf(icon(tintedWhite to 100)))
    }

    @Test
    fun `transparency is not a colour`() {
        // The canvas around a circular icon must not vote, and must not be flattened onto anything
        // either: whatever it were flattened onto would become a large fake colour of its own.
        val accent = iconAccentOf(icon(transparent to 90, pink to 10))

        assertEquals(pink, colourOf(accent))
    }

    @Test
    fun `a fully transparent bitmap has no answer at all`() {
        assertNull(iconAccentOf(icon(transparent to 100)))
    }

    @Test
    fun `an empty bitmap has no answer at all`() {
        assertNull(iconAccentOf(IntArray(0)))
    }

    @Test
    fun `shades of one colour are counted together, and reported as their real average`() {
        // A logo drawn as a gradient, or just anti-aliased, is hundreds of near-identical colours.
        // Counted separately none of them is dominant and the flat plate behind wins on population
        // alone, which is the whole failure this ranking exists to avoid. Counted together they win,
        // and what comes back is their true average rather than a rounded-off bucket coordinate.
        val darker = 0xFFD278B4.toInt()
        val lighter = 0xFFD67CB8.toInt()

        val accent = colourOf(iconAccentOf(icon(white to 60, darker to 20, lighter to 20)))

        assertEquals(0xFFD47AB6.toInt(), accent)
    }
}
