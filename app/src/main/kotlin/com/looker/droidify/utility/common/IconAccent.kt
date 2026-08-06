package com.looker.droidify.utility.common

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * What an app's icon offers as the accent colour for its detail page.
 *
 * The distinction matters because a black-and-white icon has no answer of its own: which of black or
 * white can actually be seen depends on the theme it lands in, and only the screen knows that. Keeping
 * [Monochrome] as its own case, rather than resolving it here, is what lets this stay a pure function
 * of the icon's pixels and therefore cacheable across theme changes (see [IconAccentCache]).
 */
sealed interface IconAccent {

    /** A colour genuinely present in the icon, vivid enough to carry a page. */
    data class Colour(val argb: Int) : IconAccent

    /** The icon is black, white and greys only, with no colour to take. Resolved against whatever
     *  theme is actually on screen by [com.looker.droidify.compose.theme.ScopedAccentColor]. */
    data object Monochrome : IconAccent
}

/** A pixel this opaque or more counts as icon content; the rest is the canvas around it. */
private const val OPAQUE_ALPHA_THRESHOLD = 128

/**
 * How much colour a pixel needs before it reads as a colour rather than as a shade of grey. Chosen
 * against the real icons this was built for: the plate behind a logo is neutral (a measured 0.00 on
 * ReVanced Manager's), while the logo itself sat at 0.34, so anything in between separates them.
 */
private const val MIN_SATURATION = 0.15f

/**
 * How light or dark a colour may be and still work as an accent. An accent has to stand out from a
 * white page AND a black one, and near either end it cannot: the app's own light theme is a #FFFFFF
 * surface and its AMOLED theme a #000000 one, so a near-white or near-black accent is invisible in
 * one of them whatever its hue.
 *
 * These also catch the tinted plates pure saturation misses. Saturation is measured relative to how
 * much room a colour has left at its lightness, so a barely-blue white like #F0F2F8 scores an
 * impressive 0.36 while looking exactly like white. Its lightness, 0.96, is what gives it away.
 */
private const val MIN_LIGHTNESS = 0.10f
private const val MAX_LIGHTNESS = 0.90f

/**
 * Colours are counted in buckets this many bits wide per channel, so that a logo drawn as a gradient
 * or with anti-aliased edges is counted as the one colour a person sees rather than as hundreds of
 * near-identical ones, none of which would then be dominant. The colour reported back is the true
 * average of a bucket's real pixels, so the bucketing costs no precision in the answer itself.
 */
private const val BUCKET_SHIFT = 5

/** This icon's accent, or null when the bitmap has no opaque pixels at all (nothing to read). */
fun Bitmap.iconAccent(): IconAccent? {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return iconAccentOf(pixels)
}

/**
 * The accent for an icon made of [pixels] (ARGB, row by row).
 *
 * Every colour in the icon is ranked by how much of it there is, and the first one that can actually
 * serve as an accent wins. Taking the most common colour outright, which is what this used to do, is
 * what made these pages white: on a logo sitting on a plate the plate is simply bigger, measured at
 * 58% white against 42% pink on a real ReVanced Manager icon, so the page took the colour of the
 * canvas instead of the colour of the app. Walking past the unusable ones costs nothing and lands on
 * the colour a person would have named.
 *
 * Transparency is skipped rather than filled in. The previous approach had to flatten it onto
 * something, and whatever it chose (black, white, an average) became a large fake colour competing
 * with the real ones; here a transparent pixel is simply not a vote.
 */
internal fun iconAccentOf(pixels: IntArray): IconAccent? {
    val populations = HashMap<Int, Int>()
    val sums = HashMap<Int, LongArray>()
    var opaque = 0
    for (pixel in pixels) {
        if (pixel ushr 24 < OPAQUE_ALPHA_THRESHOLD) continue
        opaque++
        val red = pixel shr 16 and 0xFF
        val green = pixel shr 8 and 0xFF
        val blue = pixel and 0xFF
        val bucket = (red shr BUCKET_SHIFT shl 16) or
            (green shr BUCKET_SHIFT shl 8) or
            (blue shr BUCKET_SHIFT)
        populations[bucket] = (populations[bucket] ?: 0) + 1
        val sum = sums.getOrPut(bucket) { LongArray(3) }
        sum[0] += red
        sum[1] += green
        sum[2] += blue
    }
    if (opaque == 0) return null

    val accent = populations.entries
        .sortedByDescending { it.value }
        .map { (bucket, count) -> averageColour(sums.getValue(bucket), count) }
        .firstOrNull(::isUsableAccent)
    return if (accent != null) IconAccent.Colour(accent) else IconAccent.Monochrome
}

/** A bucket's real colour: the mean of every pixel counted into it, not the bucket's own coordinates. */
private fun averageColour(sums: LongArray, count: Int): Int {
    val red = (sums[0] / count).toInt()
    val green = (sums[1] / count).toInt()
    val blue = (sums[2] / count).toInt()
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

/** Whether [argb] has a real hue and sits far enough from both white and black to be seen on either. */
private fun isUsableAccent(argb: Int): Boolean {
    val red = (argb shr 16 and 0xFF) / 255f
    val green = (argb shr 8 and 0xFF) / 255f
    val blue = (argb and 0xFF) / 255f
    val high = maxOf(red, green, blue)
    val low = minOf(red, green, blue)
    val lightness = (high + low) / 2f
    if (lightness < MIN_LIGHTNESS || lightness > MAX_LIGHTNESS) return false
    val chroma = high - low
    if (chroma == 0f) return false
    // The lightness bounds above keep this denominator well clear of zero.
    val saturation = chroma / (1f - abs(2f * lightness - 1f))
    return saturation >= MIN_SATURATION
}
