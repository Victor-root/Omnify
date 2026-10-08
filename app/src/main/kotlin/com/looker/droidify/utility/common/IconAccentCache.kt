package com.looker.droidify.utility.common

import android.graphics.Bitmap

/**
 * Session-lifetime cache of [iconAccent], keyed by icon identity (a package name or an external app's
 * key, each prefixed to keep the two namespaces apart). A detail screen's own accent state resets on
 * every visit (a fresh composition per navigation entry), so without this, an icon's accent, which
 * never changes within a session, got decoded and quantized from scratch again on every single visit
 * to the same app's page.
 *
 * Caching the [IconAccent] rather than a resolved colour is what keeps this correct across a theme
 * change: [IconAccent.Monochrome] only becomes black or white at the moment it is drawn.
 */
object IconAccentCache {
    private class Entry(val accent: IconAccent, val source: String?)

    private val accentsByKey = mutableMapOf<String, Entry>()

    fun get(key: String): IconAccent? = accentsByKey[key]?.accent

    fun put(key: String, accent: IconAccent) {
        accentsByKey[key] = Entry(accent, source = null)
    }

    /**
     * The accent of [bitmap], the picture [source] currently shows as [key]'s icon, worked out once per
     * source. For an icon that can change picture while the page is open: an external source's own icon
     * read off the device replacing the one composed before it was installed, say. Keeping only the
     * first answer, as [put] does, would hold on to the accent of whichever picture happened to load
     * first for the rest of the session.
     */
    fun accentOf(key: String, source: String, bitmap: Bitmap): IconAccent? =
        accentsByKey[key]?.takeIf { it.source == source }?.accent
            ?: bitmap.iconAccent()?.also { accentsByKey[key] = Entry(it, source) }
}
