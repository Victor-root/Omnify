package com.looker.droidify.compose.externalApps

import android.content.Context
import com.looker.droidify.external.ExternalReadme
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent cache of external apps' rendered README, keyed by
 * [com.looker.droidify.external.ExternalApp.key]. A cached copy shows instantly on open; the caller
 * decides (via [isFresh]) whether it's recent enough to skip a network refetch, or stale enough to
 * warrant one — a README changes far less often than its detail screen gets opened.
 */
object ReadmeCache {

    private val unsafeChars = Regex("[^A-Za-z0-9._-]")

    private fun file(context: Context, key: String): File {
        val dir = File(context.filesDir, "readme_cache").apply { mkdirs() }
        return File(dir, key.replace(unsafeChars, "_") + ".json")
    }

    fun load(context: Context, key: String): ExternalReadme? = runCatching {
        file(context, key).takeIf { it.exists() && it.length() > 0 }
            ?.readText()
            ?.let { Json.decodeFromString(ExternalReadme.serializer(), it) }
    }.getOrNull()

    fun save(context: Context, key: String, readme: ExternalReadme) {
        runCatching { file(context, key).writeText(Json.encodeToString(ExternalReadme.serializer(), readme)) }
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
