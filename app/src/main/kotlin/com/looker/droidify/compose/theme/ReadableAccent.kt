package com.looker.droidify.compose.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/** The least contrast an accent drawn on the page (text, icons, outlines) keeps against it: the WCAG
 *  minimum for large text and interface components. */
internal const val MIN_ACCENT_CONTRAST = 3f

/** Black or white, whichever reads on [this] as a fill. */
internal fun Color.contentColor(): Color = if (luminance() > 0.5f) Color.Black else Color.White

/**
 * This colour as it can be read on [background]: unchanged when it already contrasts with it by
 * [MIN_ACCENT_CONTRAST], otherwise moved toward white on a dark background or black on a light one, just
 * far enough to. The move is a blend, so the hue a person sees stays the same.
 */
internal fun Color.readableOn(background: Color): Color {
    if (contrast(this, background) >= MIN_ACCENT_CONTRAST) return this
    val target = if (background.luminance() < 0.5f) Color.White else Color.Black
    var low = 0f
    var high = 1f
    repeat(16) {
        val middle = (low + high) / 2f
        if (contrast(lerp(this, target, middle), background) >= MIN_ACCENT_CONTRAST) high = middle else low = middle
    }
    return lerp(this, target, high)
}

/** The WCAG contrast ratio between two colours, from 1 (identical) to 21 (black on white). */
internal fun contrast(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
}
