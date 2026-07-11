package com.looker.droidify.compose.externalApps

import android.content.Context
import java.io.File

/**
 * Persistent cache of external apps' rendered README HTML, keyed by
 * [com.looker.droidify.external.ExternalApp.key]. A cached copy shows instantly on open; the caller
 * decides (via [isFresh]) whether it's recent enough to skip a network refetch, or stale enough to
 * warrant one — a README changes far less often than its detail screen gets opened.
 */
object ReadmeCache {

    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    private fun file(context: Context, key: String): File {
        val dir = File(context.filesDir, "readme_cache").apply { mkdirs() }
        return File(dir, key.replace(unsafeChars, "_") + ".html")
    }

    fun load(context: Context, key: String): String? =
        runCatching { file(context, key).takeIf { it.exists() && it.length() > 0 }?.readText() }
            .getOrNull()

    fun save(context: Context, key: String, html: String) {
        runCatching { file(context, key).writeText(html) }
    }

    /** Whether the cached copy for [key] exists and was saved within [maxAgeMillis]. Backed by the
     *  cache file's own last-modified time (set implicitly by [save]), so no separate timestamp needs
     *  tracking. */
    fun isFresh(context: Context, key: String, maxAgeMillis: Long): Boolean = runCatching {
        val cacheFile = file(context, key)
        cacheFile.exists() && cacheFile.length() > 0 &&
            System.currentTimeMillis() - cacheFile.lastModified() < maxAgeMillis
    }.getOrDefault(false)
}
