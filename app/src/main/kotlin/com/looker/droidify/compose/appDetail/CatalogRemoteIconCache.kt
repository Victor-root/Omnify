package com.looker.droidify.compose.appDetail

import android.content.Context
import java.io.File

/**
 * Persistent cache of a catalogue app's real icon, extracted straight from its release APK
 * ([com.looker.droidify.utility.apk.RemoteApkIconReader]) for the rare case a repo's index declares
 * none at all (confirmed real: F-Droid's own official repo, for PPSSPP). Keyed by the release APK's
 * hash (stable across a release's lifetime, the same identity [AppRepository.cacheApkLocales] already
 * keys its own remote-read cache by), so the same build is only ever fetched once.
 */
object CatalogRemoteIconCache {

    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    fun iconFile(context: Context, apkHash: String): File {
        val dir = File(context.filesDir, "catalog_remote_icons").apply { mkdirs() }
        return File(dir, apkHash.replace(unsafeChars, "_") + ".png")
    }

    /** Writes [bytes] to this hash's cache file, returning it on success or null if the write failed. */
    fun save(context: Context, apkHash: String, bytes: ByteArray): File? = runCatching {
        val file = iconFile(context, apkHash)
        file.writeBytes(bytes)
        file
    }.getOrNull()
}
