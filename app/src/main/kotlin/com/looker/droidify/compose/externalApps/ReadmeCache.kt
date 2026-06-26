package com.looker.droidify.compose.externalApps

import android.content.Context
import java.io.File

/**
 * Persistent cache of external apps' rendered README HTML, keyed by
 * [com.looker.droidify.external.ExternalApp.key]. The README is fetched from the network on every
 * detail open; caching it lets a re-open show instantly while a fresh copy is fetched in the
 * background.
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
}
