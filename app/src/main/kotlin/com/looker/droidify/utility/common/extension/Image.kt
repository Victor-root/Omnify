package com.looker.droidify.utility.common.extension

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import coil3.compose.AsyncImagePainter
import coil3.toBitmap

private const val TAG = "Image"

/**
 * This successful Coil load as a [size]x[size] bitmap, in a safe (non-hardware) config so it can be
 * read back on the CPU, e.g. for colour extraction. [coil3.Image.toBitmap] reuses the source image's
 * own [Bitmap.Config] when it has to redraw at a different size, and on modern Android that's
 * frequently [Bitmap.Config.HARDWARE] (Coil's own default for a plain display-only load), creating a
 * new *mutable* bitmap in that config throws (hardware bitmaps can't be drawn onto), which crashed
 * every icon this ran against. Asking for [Bitmap.Config.ARGB_8888] explicitly instead of the source's
 * own config avoids that entirely. Null on any failure, so a decorative use like an accent colour never
 * crashes the app over it.
 */
fun AsyncImagePainter.State.Success.toSafeBitmap(size: Int): Bitmap? =
    runCatching { result.image.toBitmap(size, size, Bitmap.Config.ARGB_8888) }
        .onFailure { Log.e(TAG, "Unable to convert a successful Coil load to a safe bitmap", it) }
        .getOrNull()

/**
 * Whether [this] is a round picture filling its square, give or take a small margin: its corners
 * see-through, each edge solid a little way in. A padded icon, see-through well into its edges, is not
 * one. Always false for a bitmap the
 * CPU cannot read (a hardware one).
 */
fun Bitmap.isRoundFullSize(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE) return false
    if (width < MIN_SHAPE_SIZE || height < MIN_SHAPE_SIZE) return false
    val size = minOf(width, height)
    val corner = (size * CORNER_INSET).toInt().coerceAtLeast(1)
    val edge = (size * EDGE_INSET).toInt()
    val corners = listOf(
        corner to corner,
        width - 1 - corner to corner,
        corner to height - 1 - corner,
        width - 1 - corner to height - 1 - corner,
    )
    val edges = listOf(
        width / 2 to edge,
        width / 2 to height - 1 - edge,
        edge to height / 2,
        width - 1 - edge to height / 2,
    )
    fun alphaAt(point: Pair<Int, Int>) = Color.alpha(getPixel(point.first, point.second))
    return corners.all { alphaAt(it) < CLEAR_ALPHA } && edges.all { alphaAt(it) > SOLID_ALPHA }
}

private const val MIN_SHAPE_SIZE = 16
private const val CORNER_INSET = 0.03f
private const val EDGE_INSET = 0.1f
private const val CLEAR_ALPHA = 24
private const val SOLID_ALPHA = 200
