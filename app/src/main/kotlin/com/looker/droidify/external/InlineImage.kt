package com.looker.droidify.external

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.looker.droidify.utility.common.SdkCheck
import java.io.ByteArrayOutputStream

/** An image of a README, about to be inlined into it (see [ExternalApi.readme]). */
internal class InlineImage(val contentType: String, val bytes: ByteArray) {

    /**
     * This image no wider than [MAX_WIDTH], or itself when it already is.
     *
     * A README's image can be far larger than any page will show it: Ente Auth's screenshots are a single
     * PNG 6551 pixels wide, under a megabyte on disk but 67 MB once decoded, in a WebView process that
     * gets killed for less on a small device, and half a megabyte of Base64 in the middle of the page.
     * Only the formats that can't be animated are touched, since shrinking drops all but the first frame.
     */
    fun shrunkToFit(): InlineImage {
        if (contentType != "image/png" && contentType != "image/jpeg") return this
        return runCatching { shrink() }.getOrNull() ?: this
    }

    private fun shrink(): InlineImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= MAX_WIDTH || height <= 0) return null
        var sample = 1
        while (width / (sample * 2) >= MAX_WIDTH) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scaled = decoded.scale(MAX_WIDTH, (height.toLong() * MAX_WIDTH / width).toInt().coerceAtLeast(1))
        val out = ByteArrayOutputStream()
        scaled.compress(WEBP_FORMAT, WEBP_QUALITY, out)
        return InlineImage("image/webp", out.toByteArray()).takeIf { it.bytes.size in 1 until bytes.size }
    }
}

/** A TV's width: no page shows an image wider than its screen. */
private const val MAX_WIDTH = 1920
private const val WEBP_QUALITY = 85

@Suppress("DEPRECATION")
private val WEBP_FORMAT: Bitmap.CompressFormat
    get() = if (SdkCheck.isR) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
