package com.looker.droidify.utility.common

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * Session-lifetime cache of [iconAccent], keyed by icon identity (a package name or an external app's
 * key, each prefixed to keep the two namespaces apart). A detail screen's own accent state resets on
 * every visit (a fresh composition per navigation entry), so without this, an icon's accent, which
 * never changes within a session, got decoded and quantized from scratch again on every single visit
 * to the same app's page.
 *
 * Caching the [IconAccent] rather than a resolved colour is what keeps this correct across a theme
 * change: [IconAccent.Monochrome] only becomes black or white at the moment it is drawn.
 *
 * Only used from the main thread: the accents themselves are worked out off it (see [IconAccentState]).
 */
object IconAccentCache {
    private class Entry(val accent: IconAccent, val source: String?)

    private val accentsByKey = mutableMapOf<String, Entry>()

    fun get(key: String): IconAccent? = accentsByKey[key]?.accent

    /** [key]'s accent when it was worked out from the picture [source] names. */
    fun get(key: String, source: String): IconAccent? = accentsByKey[key]?.takeIf { it.source == source }?.accent

    fun put(key: String, accent: IconAccent, source: String? = null) {
        accentsByKey[key] = Entry(accent, source)
    }
}

/** A detail page's icon accent, see [rememberIconAccent]. */
@Stable
class IconAccentState internal constructor(
    private val cacheKey: String,
    private val followsPicture: Boolean,
) {
    private class Picture(val source: String, val bitmap: () -> Bitmap?)

    /** What reading a [Picture] found, absent when it couldn't be read as a bitmap at all. */
    private class Reading(val accent: IconAccent?)

    /** Seeded from [IconAccentCache] so revisiting the same app doesn't wait for its icon to be read and
     *  quantized all over again. */
    var accent by mutableStateOf(IconAccentCache.get(cacheKey))
        private set

    /** Every picture shown, in order: each is read in turn, as each was when this ran on the main
     *  thread, so an unreadable later picture never loses the accent of the one before it. */
    private val pictures = Channel<Picture>(Channel.UNLIMITED)

    /** Only touched on the main thread, by [onIconBitmap]. */
    private var lastSource: String? = null

    /**
     * For the icon to pass each picture it shows, with a source naming it and a way to read it as a
     * bitmap (null when it can't be). A repeat of the current source is ignored: Coil can report the
     * same load more than once.
     *
     * Null when the accent is already known as the page opens and only the first answer counts: the icon
     * then keeps Coil's hardware bitmaps, as a software request would reject the hardware bitmap already
     * in memory and decode the file again. Decided once, so the icon's request never changes mid-way.
     */
    val onIconBitmap: ((source: String, bitmap: () -> Bitmap?) -> Unit)? =
        if (followsPicture || accent == null) {
            { source, bitmap ->
                if (lastSource != source) {
                    lastSource = source
                    pictures.trySend(Picture(source, bitmap))
                }
            }
        } else {
            null
        }

    internal suspend fun follow() {
        for (picture in pictures) read(picture)
    }

    private suspend fun read(picture: Picture) {
        if (!followsPicture && accent != null) return
        // Each source's accent is worked out once: for an icon that can change picture while the page is
        // open (an external source's own icon read off the device replacing the one composed before it
        // was installed, say), keeping only the first answer would hold on to the accent of whichever
        // picture happened to load first for the rest of the session.
        val known = if (followsPicture) IconAccentCache.get(cacheKey, picture.source) else null
        if (known != null) {
            accent = known
            return
        }
        // Off the main thread: the read may redraw the picture, and the accent is a pass over every pixel.
        val reading = withContext(Dispatchers.Default) {
            picture.bitmap()?.let { Reading(it.iconAccent()) }
        } ?: return
        accent = reading.accent
        reading.accent?.let { IconAccentCache.put(cacheKey, it, picture.source.takeIf { followsPicture }) }
    }
}

/**
 * The accent [cacheKey]'s icon gives its detail page, from the pictures the icon hands to
 * [IconAccentState.onIconBitmap]: worked out off the main thread, and only while [enabled] (the "match
 * accent to app icon" setting), nothing else using it. With [followsPicture] the latest picture wins,
 * its accent replacing the last; without it the first answer stays for the session.
 */
@Composable
fun rememberIconAccent(cacheKey: String, followsPicture: Boolean, enabled: Boolean): IconAccentState {
    val state = remember { IconAccentState(cacheKey, followsPicture) }
    LaunchedEffect(state, enabled) {
        if (enabled) state.follow()
    }
    return state
}
