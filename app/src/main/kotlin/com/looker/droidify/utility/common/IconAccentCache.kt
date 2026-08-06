package com.looker.droidify.utility.common

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
    private val accentsByKey = mutableMapOf<String, IconAccent>()

    fun get(key: String): IconAccent? = accentsByKey[key]

    fun put(key: String, accent: IconAccent) {
        accentsByKey[key] = accent
    }
}
