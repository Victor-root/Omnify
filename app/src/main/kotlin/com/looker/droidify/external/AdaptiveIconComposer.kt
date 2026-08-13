package com.looker.droidify.external

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.graphics.PathParser
import androidx.core.graphics.drawable.toBitmap
import com.looker.droidify.BuildConfig
import com.looker.droidify.utility.common.SdkCheck

/**
 * Builds the icon Android will actually draw for a source, from the adaptive icon in its repository,
 * so a source's page shows the same thing before installing as after.
 *
 * The problem this solves: since Android 8 an app's real icon is an `<adaptive-icon>`, two layers the
 * system composes and masks itself. The flat `ic_launcher.png` a repository also ships in its mipmap folders is
 * only the fallback for Android 7 and below, it is never what a modern device draws, and because
 * nothing renders it any more it is routinely left behind at an older version of the artwork.
 * Confirmed on Victor-root/OpenMessages, whose legacy raster is a smaller, thinner, three-line bubble
 * while its adaptive icon draws a bold two-line one: picking the raster did not give a *rougher* copy
 * of the icon, it gave a different picture altogether.
 *
 * Only attempted on Android 8+, where [AdaptiveIconDrawable] both exists and is what the device would
 * really use. Below that the legacy raster is the correct answer, and is left to the existing path.
 */
internal class AdaptiveIconComposer(
    private val readFile: suspend (path: String) -> String?,
    private val readBytes: suspend (path: String) -> ByteArray?,
) {

    /**
     * Composes [treePaths]' adaptive launcher icon at [sizePx] square, or null when the repository has
     * none, when a layer can't be read, or when the result would be pointless (an empty or fully
     * transparent image). Never throws.
     */
    suspend fun compose(treePaths: List<String>, sizePx: Int = ICON_SIZE_PX): Bitmap? {
        if (!SdkCheck.isOreo) return null
        return try {
            composeInternal(treePaths, sizePx)
        } catch (t: Throwable) {
            Log.w(TAG, "Adaptive icon composition failed", t)
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun composeInternal(treePaths: List<String>, sizePx: Int): Bitmap? {
        val definitionPath = findAdaptiveIconPath(treePaths) ?: return null
        val definition = readFile(definitionPath)?.withoutXmlComments() ?: return null
        val layers = parseAdaptiveIcon(definition) ?: return null
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "adaptive icon at $definitionPath -> bg=${layers.background} fg=${layers.foreground}")
        }
        // A foreground is what carries the artwork; without one there is nothing worth showing that the
        // plain raster wouldn't do just as well.
        val foregroundRef = layers.foreground ?: return null
        val foreground = resolveLayer(foregroundRef, treePaths, sizePx) ?: return null
        val background = layers.background?.let { resolveLayer(it, treePaths, sizePx) }
            ?: ColorDrawable(Color.TRANSPARENT)
        val composed = AdaptiveIconDrawable(background, foreground)
            .toBitmap(width = sizePx, height = sizePx, config = Bitmap.Config.ARGB_8888)
        if (composed.isFullyTransparent()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "composed icon came out empty, keeping the raster instead")
            return null
        }
        return composed
    }

    /**
     * Resolves one `@mipmap/…`, `@drawable/…` or `@color/…` layer reference to something drawable:
     * a raster file, a vector rendered here, or a flat colour. Null when the reference names nothing
     * this can read.
     */
    private suspend fun resolveLayer(
        reference: String,
        treePaths: List<String>,
        sizePx: Int,
    ): Drawable? {
        val name = reference.substringAfterLast('/')
        if (reference.startsWith("@color/") || reference.startsWith("@android:color/")) {
            return resolveColour(name, treePaths)?.let(::ColorDrawable)
        }
        // A raster wins when the repo ships one: it is the artwork itself rather than a re-drawing of it.
        rasterPathFor(name, treePaths)?.let { path ->
            readBytes(path)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return BitmapDrawable(null, it) }
            }
        }
        vectorPathFor(name, treePaths)?.let { path ->
            readFile(path)?.let { xml ->
                renderVector(xml.withoutXmlComments(), sizePx)?.let { return BitmapDrawable(null, it) }
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "could not resolve icon layer $reference")
        return null
    }

    /** A `<color name="…">#RRGGBB</color>` from a file in `res/values`, for a colour-only layer. */
    private suspend fun resolveColour(name: String, treePaths: List<String>): Int? {
        val colourFiles = treePaths.filter {
            it.contains("/res/values/") && it.endsWith(".xml")
        }.take(MAX_COLOUR_FILES)
        val pattern = Regex("""<color\s+name="${Regex.escape(name)}"\s*>\s*(#[0-9a-fA-F]{3,8})\s*</color>""")
        for (path in colourFiles) {
            val text = readFile(path) ?: continue
            val match = pattern.find(text) ?: continue
            return runCatching { Color.parseColor(match.groupValues[1]) }.getOrNull()
        }
        return null
    }

    private fun rasterPathFor(name: String, treePaths: List<String>): String? = treePaths
        .filter { path ->
            val file = path.substringAfterLast('/')
            val stem = file.substringBeforeLast('.')
            val ext = file.substringAfterLast('.', "").lowercase()
            stem == name && (ext == "png" || ext == "webp")
        }
        .maxByOrNull { densityOf(it) }

    private fun vectorPathFor(name: String, treePaths: List<String>): String? = treePaths
        .firstOrNull { it.substringAfterLast('/') == "$name.xml" && "/res/drawable" in it }

    private fun densityOf(path: String): Int {
        val dir = path.substringBeforeLast('/', "").substringAfterLast('/').lowercase()
        return when {
            dir.contains("xxxhdpi") -> 6
            dir.contains("xxhdpi") -> 5
            dir.contains("xhdpi") -> 4
            dir.contains("hdpi") -> 3
            dir.contains("mdpi") -> 2
            else -> 0
        }
    }
}

private const val TAG = "AdaptiveIconComposer"

/** Square size the composed icon is rendered at: comfortably above the largest place it is drawn (the
 *  detail hero card) on a high-density screen, so it is never upscaled. */
internal const val ICON_SIZE_PX = 384

/** Guards the colour lookup against walking a huge tree; a repo's own colours are in the first few. */
private const val MAX_COLOUR_FILES = 12

/** The two drawable references an `<adaptive-icon>` is made of, as raw `@pkg/name` strings. */
private data class AdaptiveIconLayers(val background: String?, val foreground: String?)

/**
 * The repository's adaptive launcher icon definition, preferring the density-independent `anydpi`
 * folder Android itself resolves first, and `ic_launcher` over the colour aliases some apps ship
 * alongside it (an alias is one of several optional themes, not the app's own icon).
 */
private fun findAdaptiveIconPath(treePaths: List<String>): String? {
    val candidates = treePaths.filter { path ->
        val file = path.substringAfterLast('/')
        val dir = path.substringBeforeLast('/', "").substringAfterLast('/').lowercase()
        file.endsWith(".xml") && dir.startsWith("mipmap") &&
            (file.startsWith("ic_launcher") || file.startsWith("ic_app") || file == "launcher.xml")
    }
    if (candidates.isEmpty()) return null
    return candidates.minWithOrNull(
        compareBy<String> { if (it.substringAfterLast('/') == "ic_launcher.xml") 0 else 1 }
            .thenBy { if ("anydpi" in it.lowercase()) 0 else 1 }
            .thenBy { it.length },
    )
}

/** Reads an `<adaptive-icon>`'s background/foreground references straight out of its XML text. */
private fun parseAdaptiveIcon(xml: String): AdaptiveIconLayers? {
    if ("<adaptive-icon" !in xml) return null
    fun layer(tag: String): String? =
        Regex("""<$tag[^>]*android:drawable\s*=\s*"([^"]+)"""").find(xml)?.groupValues?.get(1)
    val background = layer("background")
    val foreground = layer("foreground")
    if (background == null && foreground == null) return null
    return AdaptiveIconLayers(background = background, foreground = foreground)
}

private fun Bitmap.isFullyTransparent(): Boolean {
    // Sampled rather than exhaustive: a genuinely empty layer is empty everywhere, and reading every
    // pixel of a 384px bitmap for a check that nearly always passes isn't worth it.
    val step = (width / 24).coerceAtLeast(1)
    var x = 0
    while (x < width) {
        var y = 0
        while (y < height) {
            if (Color.alpha(getPixel(x, y)) != 0) return false
            y += step
        }
        x += step
    }
    return true
}

/**
 * Renders a `<vector>` drawable's XML to a bitmap.
 *
 * Android can only inflate a VectorDrawable from a compiled resource, never from XML text fetched at
 * runtime, so the small subset an adaptive-icon layer actually uses is drawn here instead: the
 * viewport, nested `<group>` transforms, and each `<path>`'s fill. Strokes and gradient fills are not
 * drawn; a layer relying on those simply comes out empty and is rejected by the caller's own check,
 * falling back to the raster rather than showing something half-drawn.
 */
private fun renderVector(xml: String, sizePx: Int): Bitmap? {
    val viewportWidth = xml.attr("android:viewportWidth")?.toFloatOrNull() ?: return null
    val viewportHeight = xml.attr("android:viewportHeight")?.toFloatOrNull() ?: return null
    if (viewportWidth <= 0f || viewportHeight <= 0f) return null

    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(sizePx / viewportWidth, sizePx / viewportHeight)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // One pass over the tags in order: a <group> opens a transform that its own </group> closes, and
    // every <path> in between draws under whatever transforms are currently open.
    val tags = Regex("""<(/?)(group|path)\b([^>]*)>""").findAll(xml)
    var depth = 0
    for (tag in tags) {
        val closing = tag.groupValues[1] == "/"
        val name = tag.groupValues[2]
        val attributes = tag.groupValues[3]
        when {
            name == "group" && closing -> if (depth > 0) { canvas.restore(); depth-- }
            name == "group" -> {
                canvas.save()
                depth++
                val pivotX = attributes.attr("android:pivotX")?.toFloatOrNull() ?: 0f
                val pivotY = attributes.attr("android:pivotY")?.toFloatOrNull() ?: 0f
                canvas.translate(
                    attributes.attr("android:translateX")?.toFloatOrNull() ?: 0f,
                    attributes.attr("android:translateY")?.toFloatOrNull() ?: 0f,
                )
                canvas.rotate(
                    attributes.attr("android:rotation")?.toFloatOrNull() ?: 0f,
                    pivotX,
                    pivotY,
                )
                canvas.scale(
                    attributes.attr("android:scaleX")?.toFloatOrNull() ?: 1f,
                    attributes.attr("android:scaleY")?.toFloatOrNull() ?: 1f,
                    pivotX,
                    pivotY,
                )
            }
            // A self-closing <path .../> matches with an empty closing group, so only draw the open form.
            name == "path" && !closing -> {
                val data = attributes.attr("android:pathData") ?: continue
                val fill = attributes.attr("android:fillColor") ?: continue
                // A theme/resource reference resolves to nothing here; skipping keeps a wrong colour off
                // the canvas rather than guessing one.
                if (fill.startsWith("@") || fill.startsWith("?")) continue
                val colour = runCatching { Color.parseColor(fill) }.getOrNull() ?: continue
                val alpha = attributes.attr("android:fillAlpha")?.toFloatOrNull() ?: 1f
                val path = runCatching { PathParser.createPathFromPathData(data) }.getOrNull() ?: continue
                if (attributes.attr("android:fillType").equals("evenOdd", ignoreCase = true)) {
                    path.fillType = Path.FillType.EVEN_ODD
                }
                paint.color = colour
                paint.alpha = (Color.alpha(colour) * alpha).toInt().coerceIn(0, 255)
                canvas.drawPath(path, paint)
            }
        }
    }
    while (depth > 0) { canvas.restore(); depth-- }
    return bitmap
}

/** Drops `<!-- ... -->` blocks. Every scan below reads tags with a regex, and repositories routinely
 *  open these files with a licence header that quotes markup (a GPL notice's own `<http://...>` among
 *  them), which would otherwise be read as content. */
private fun String.withoutXmlComments(): String = XML_COMMENT_REGEX.replace(this, "")

private val XML_COMMENT_REGEX = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

/** The value of an `android:*` attribute in a tag (or in a whole document, for the root's own). */
private fun String.attr(name: String): String? =
    Regex("""${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(this)?.groupValues?.get(1)
