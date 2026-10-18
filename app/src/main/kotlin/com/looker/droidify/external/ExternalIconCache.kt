package com.looker.droidify.external

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import java.io.File
import java.io.FileOutputStream

/**
 * Persistent cache of the *real* icon for each external app, keyed by [ExternalApp.key] and stored as
 * a PNG. A release ships no icon metadata at all, so this holds whichever best answer has been worked
 * out: the repository's adaptive icon composed as Android would draw it ([AdaptiveIconComposer]) before
 * the app is installed, then the icon read straight out of the APK once it has been.
 */
object ExternalIconCache {

    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    /** Created once per process (nothing ever removes it) rather than on every [iconFile] call, which
     *  every external app icon shown makes on the main thread. */
    @Volatile
    private var directory: File? = null

    fun iconFile(context: Context, key: String): File {
        val dir = directory ?: File(context.filesDir, "external_icons").also {
            if (it.mkdirs() || it.isDirectory) directory = it
        }
        return File(dir, key.replace(unsafeChars, "_") + ".png")
    }

    /**
     * [file] decoded, or null when it can't be read. Kept in the image loader's memory cache (bounded,
     * and trimmed under memory pressure) under the file's [lastModified] and length, so a tile coming
     * back into view reuses the bitmap instead of decoding the PNG again on the main thread, while a
     * file [save] rewrote is read afresh.
     */
    fun decodedIcon(context: Context, file: File, lastModified: Long): Bitmap? {
        val memoryCache = SingletonImageLoader.get(context).memoryCache
        val cacheKey = MemoryCache.Key(
            key = "external_icon:${file.path}",
            extras = mapOf("lastModified" to lastModified.toString(), "length" to file.length().toString()),
        )
        (memoryCache?.get(cacheKey)?.image as? BitmapImage)?.let { return it.bitmap }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?.also { memoryCache?.set(cacheKey, MemoryCache.Value(it.asImage())) }
    }

    fun save(context: Context, key: String, bitmap: Bitmap) {
        runCatching {
            FileOutputStream(iconFile(context, key)).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
