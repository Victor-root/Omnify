package com.looker.droidify.external

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _pending = MutableStateFlow<Set<String>>(emptySet())

    /** Keys of the sources whose icon is being looked for right now ([ExternalRefresher]), so their tile
     *  can show one is on its way rather than settle on the account's avatar, which on a slow connection
     *  looked like the icon had failed. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    fun markPending(keys: Collection<String>) = _pending.update { it + keys }

    fun clearPending(key: String) = _pending.update { it - key }

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

    /** Written beside the icon then renamed over it, as icons are saved off the main thread while
     *  [decodedIcon] may be reading the same file for a tile on screen. */
    fun save(context: Context, key: String, bitmap: Bitmap) {
        val target = iconFile(context, key)
        runCatching {
            val partial = File.createTempFile(target.name, ".tmp", target.parentFile)
            runCatching {
                FileOutputStream(partial).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                check(partial.renameTo(target))
            }.onFailure { partial.delete() }
        }
    }
}
