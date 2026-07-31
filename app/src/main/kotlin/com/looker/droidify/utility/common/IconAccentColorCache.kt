package com.looker.droidify.utility.common

/**
 * Session-lifetime cache of [dominantAccentColor], keyed by icon identity (a package name or an
 * external app's key, each prefixed to keep the two namespaces apart). A detail screen's own accent
 * state resets on every visit (a fresh composition per navigation entry), so without this, an icon's
 * colour, which never changes within a session, got decoded and quantized from scratch again on every
 * single visit to the same app's page.
 */
object IconAccentColorCache {
    private val colorsByKey = mutableMapOf<String, Int>()

    fun get(key: String): Int? = colorsByKey[key]

    fun put(key: String, color: Int) {
        colorsByKey[key] = color
    }
}
