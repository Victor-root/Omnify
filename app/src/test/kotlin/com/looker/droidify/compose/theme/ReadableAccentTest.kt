package com.looker.droidify.compose.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadableAccentTest {

    private val darkPage = Color(0xFF121212)
    private val lightPage = Color.White

    @Test
    fun `an accent already readable is left as it is`() {
        val red = Color(0xFFD32F2F)
        assertEquals(red, red.readableOn(darkPage))
        assertEquals(red, red.readableOn(lightPage))
    }

    @Test
    fun `a dark accent on a dark page is lightened just enough, keeping its hue`() {
        val navy = Color(0xFF1E3C78)
        val readable = navy.readableOn(darkPage)
        val reached = contrast(readable, darkPage)
        assertTrue(reached >= MIN_ACCENT_CONTRAST && reached < MIN_ACCENT_CONTRAST + 0.05f, "$reached")
        assertTrue(readable.blue > readable.green && readable.green > readable.red, "$readable")
    }

    @Test
    fun `a pale accent on a light page is darkened`() {
        val yellow = Color(0xFFFFEB3B)
        val readable = yellow.readableOn(lightPage)
        assertTrue(contrast(readable, lightPage) >= MIN_ACCENT_CONTRAST)
        assertTrue(readable.red > readable.blue, "$readable")
    }
}
