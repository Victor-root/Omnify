package com.looker.droidify.external

import java.io.File
import java.security.MessageDigest

/**
 * Disk cache of successful GET responses, keyed by URL, holding each body together with the ETag the
 * server sent with it. Two savings, both about the rate limits the external-source feature lives
 * under (60 requests an hour on GitHub without a token):
 *
 * - Inside [FRESH_WINDOW_MS] the stored body answers on its own and nothing is sent at all. Opening
 *   one app's page asks for the same release listing three times over (the version list, whether the
 *   project ships a changelog, and the changelog itself), on top of the refresh that ran moments
 *   earlier.
 * - Past that window the stored ETag turns the next request into a conditional one: the server
 *   answers 304 with no body when what we hold is still current, and GitHub does not count a 304
 *   against the rate limit at all. A project publishes about once a month, so nearly every release
 *   check stops costing quota.
 *
 * On disk rather than in memory because the scheduled background refresh runs in a fresh process,
 * which is precisely the case this package's existing in-memory caches cannot help with. Under the
 * cache directory, so Android may reclaim it under storage pressure: losing it costs one ordinary
 * request per entry and nothing else.
 *
 * Deliberately free of Android types, so its behaviour can be tested for real on the JVM.
 */
class ConditionalGetCache(private val dir: File) {

    /** A stored response. [savedAt] is wall-clock, read from the file itself. */
    class Entry(val etag: String?, val body: String, val savedAt: Long)

    /** Whether [entry] may still be served without asking the server anything. */
    fun isFresh(entry: Entry): Boolean {
        val age = System.currentTimeMillis() - entry.savedAt
        // A clock moved backwards (a manual change, an NTP correction) makes an entry look younger
        // than it is. Reading a negative age as stale keeps the window from stretching.
        return age >= 0 && age < FRESH_WINDOW_MS
    }

    fun load(url: String): Entry? = runCatching {
        val file = file(url)
        val text = file.takeIf { it.isFile }?.readText() ?: return null
        val separator = text.indexOf('\n')
        // Written by save() and by nothing else, so a file with no ETag line is a write that was cut
        // short (the process killed mid-save, a full disk), not a shape to support.
        if (separator < 0) return null
        Entry(
            etag = text.take(separator).takeIf { it.isNotEmpty() },
            body = text.substring(separator + 1),
            savedAt = file.lastModified(),
        )
    }.getOrNull()

    /**
     * Stores [body] for [url]. [etag] is what makes the next request conditional; a response that
     * carries none is still worth keeping for [FRESH_WINDOW_MS].
     */
    fun save(url: String, etag: String?, body: String) {
        // A big repo's file tree runs to megabytes and already has its own short-lived cache in
        // memory. Keeping those here would evict every small entry that actually pays off.
        if (body.length > MAX_ENTRY_CHARS) return
        runCatching {
            dir.mkdirs()
            // ETag on the first line, body after it. A header value cannot contain a newline, so the
            // split is unambiguous.
            val file = file(url)
            file.writeText(etag.orEmpty() + "\n" + body)
            prune(keep = file)
        }
    }

    /** Restarts [url]'s fresh window: a 304 is the server saying what we hold is still current. */
    fun markStillFresh(url: String) {
        runCatching { file(url).setLastModified(System.currentTimeMillis()) }
    }

    /** Keeps the newest entries within both caps, deleting the oldest first. [keep] is the entry just
     *  written: it counts as the newest by construction, so it is never a candidate for deletion
     *  rather than trusting the file system to timestamp it apart from the entry written before it. */
    private fun prune(keep: File) {
        val others = dir.listFiles()?.filterNot { it == keep } ?: return
        var count = 1
        var bytes = keep.length()
        for (file in others.sortedByDescending { it.lastModified() }) {
            count++
            bytes += file.length()
            if (count > MAX_ENTRIES || bytes > MAX_TOTAL_BYTES) file.delete()
        }
    }

    private fun file(url: String) = File(dir, sha256Hex(url))

    /** Internal rather than private so the tests can hold the code to these numbers instead of
     *  restating them, which is how a cap and its test quietly drift apart. */
    internal companion object {
        /** How long a stored body answers by itself. GitHub declares exactly this on its own
         *  responses, and it is short enough that a version published in the meantime is never more
         *  than a minute from showing, while covering the burst of identical requests one screen
         *  opening produces. */
        const val FRESH_WINDOW_MS = 60_000L

        /** Bodies above this are not stored: see [save]. */
        const val MAX_ENTRY_CHARS = 512 * 1024

        /** Two caps rather than one, since neither implies the other: a great many tiny entries and a
         *  handful of large ones both need bounding. Sized for far more sources than anyone tracks. */
        const val MAX_ENTRIES = 256
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
    }
}

/** A URL as a file name: fixed length, nothing to escape, no collision worth guarding against. */
private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
