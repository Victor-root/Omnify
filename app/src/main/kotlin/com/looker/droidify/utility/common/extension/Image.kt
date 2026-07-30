package com.looker.droidify.utility.common.extension

import android.graphics.Bitmap
import coil3.compose.AsyncImagePainter
import coil3.toBitmap

/**
 * This successful Coil load as a [size]x[size] bitmap, in a safe (non-hardware) config so it can be
 * read back on the CPU, e.g. for colour extraction. [coil3.Image.toBitmap] reuses the source image's
 * own [Bitmap.Config] when it has to redraw at a different size, and on modern Android that's
 * frequently [Bitmap.Config.HARDWARE] (Coil's own default for a plain display-only load) — creating a
 * new *mutable* bitmap in that config throws (hardware bitmaps can't be drawn onto), which crashed
 * every icon this ran against. Asking for [Bitmap.Config.ARGB_8888] explicitly instead of the source's
 * own config avoids that entirely. Null on any failure, so a decorative use like an accent colour never
 * crashes the app over it.
 */
fun AsyncImagePainter.State.Success.toSafeBitmap(size: Int): Bitmap? =
    runCatching { result.image.toBitmap(size, size, Bitmap.Config.ARGB_8888) }.getOrNull()
