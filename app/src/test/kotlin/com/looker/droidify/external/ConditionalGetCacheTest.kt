package com.looker.droidify.external

import com.looker.droidify.external.ConditionalGetCache.Companion.FRESH_WINDOW_MS
import com.looker.droidify.external.ConditionalGetCache.Companion.MAX_ENTRIES
import com.looker.droidify.external.ConditionalGetCache.Companion.MAX_ENTRY_CHARS
import com.looker.droidify.external.ConditionalGetCache.Companion.MAX_TOTAL_BYTES
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the external-source API's request savings rest on. Every claim in [ConditionalGetCache]'s own
 * documentation is asserted here, because the failure mode of getting this wrong is silent: an entry
 * wrongly considered fresh shows a version that is no longer the newest, and an entry wrongly
 * considered stale simply goes on spending the rate limit it exists to protect.
 */
class ConditionalGetCacheTest {

    @TempDir
    lateinit var dir: File

    private lateinit var cache: ConditionalGetCache

    private val url = "https://api.github.com/repos/Zverik/every_door/releases?per_page=30&page=1"

    @BeforeEach
    fun setUp() {
        cache = ConditionalGetCache(dir)
    }

    /** Ages the single stored entry by [millis], the way waiting would. */
    private fun age(millis: Long) {
        val file = dir.listFiles()!!.single()
        file.setLastModified(System.currentTimeMillis() - millis)
    }

    @Test
    fun `a url never stored reads as nothing`() {
        assertNull(cache.load(url))
    }

    @Test
    fun `a stored body comes back with its etag`() {
        cache.save(url, "\"abc123\"", """[{"tag_name":"v7.1"}]""")

        val entry = assertNotNull(cache.load(url))
        assertEquals("\"abc123\"", entry.etag)
        assertEquals("""[{"tag_name":"v7.1"}]""", entry.body)
    }

    @Test
    fun `a weak etag survives the round trip unchanged`() {
        // GitHub answers with weak validators (W/"…") on some endpoints, and the prefix is part of the
        // value: sending it back without it is a different request.
        cache.save(url, "W/\"abc123\"", "body")

        assertEquals("W/\"abc123\"", assertNotNull(cache.load(url)).etag)
    }

    @Test
    fun `a response with no etag is still stored, and reads back as having none`() {
        cache.save(url, null, "body")

        val entry = assertNotNull(cache.load(url))
        assertNull(entry.etag)
        assertEquals("body", entry.body)
        // Worth keeping even so: the fresh window alone collapses the burst of identical requests one
        // screen opening makes.
        assertTrue(cache.isFresh(entry))
    }

    @Test
    fun `an empty etag reads back as none rather than as an empty string`() {
        // Sending If-None-Match: "" would be a request for something no server has.
        cache.save(url, "", "body")

        assertNull(assertNotNull(cache.load(url)).etag)
    }

    @Test
    fun `a body containing newlines survives whole`() {
        val body = "first\nsecond\n\nfourth\n"
        cache.save(url, "\"e\"", body)

        val entry = assertNotNull(cache.load(url))
        assertEquals(body, entry.body)
        assertEquals("\"e\"", entry.etag)
    }

    @Test
    fun `an empty body is stored and read back as empty, not as a miss`() {
        cache.save(url, "\"e\"", "")

        assertEquals("", assertNotNull(cache.load(url)).body)
    }

    @Test
    fun `saving again replaces the previous body and etag`() {
        cache.save(url, "\"v1\"", "old")
        cache.save(url, "\"v2\"", "new")

        val entry = assertNotNull(cache.load(url))
        assertEquals("new", entry.body)
        assertEquals("\"v2\"", entry.etag)
        assertEquals(1, dir.listFiles()!!.size, "the same url should occupy one entry, not two")
    }

    @Test
    fun `two urls do not share an entry`() {
        val other = url.replace("page=1", "page=2")
        cache.save(url, "\"a\"", "first page")
        cache.save(other, "\"b\"", "second page")

        assertEquals("first page", assertNotNull(cache.load(url)).body)
        assertEquals("second page", assertNotNull(cache.load(other)).body)
    }

    @Test
    fun `urls differing only in case or trailing slash are different entries`() {
        // Two spellings of the same resource are two entries rather than one wrong one: a needless
        // request costs one unit of quota, a wrong body is a wrong version on screen.
        cache.save("https://example.org/a", "\"a\"", "lower")
        cache.save("https://example.org/A", "\"b\"", "upper")
        cache.save("https://example.org/a/", "\"c\"", "slash")

        assertEquals("lower", assertNotNull(cache.load("https://example.org/a")).body)
        assertEquals("upper", assertNotNull(cache.load("https://example.org/A")).body)
        assertEquals("slash", assertNotNull(cache.load("https://example.org/a/")).body)
    }

    @Test
    fun `a url with characters no file name allows is still stored`() {
        val awkward = "https://gitlab.com/api/v4/projects/foo%2Fbar/releases?x=a b&y=../../etc"
        cache.save(awkward, "\"e\"", "body")

        assertEquals("body", assertNotNull(cache.load(awkward)).body)
        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(
            dir.listFiles()!!.single().parentFile == dir,
            "an entry must never be written outside the cache directory",
        )
    }

    @Test
    fun `a freshly stored entry is fresh`() {
        cache.save(url, "\"e\"", "body")

        assertTrue(cache.isFresh(assertNotNull(cache.load(url))))
    }

    @Test
    fun `the window is a minute`() {
        // The tests around it are written against the constant rather than the number, which is what
        // keeps them honest when it changes. This one pins the number itself, because it is the one
        // that decides how out of date a version on screen can be: a release published now shows
        // within a minute. Widening it is a product decision, not a tidy-up.
        assertEquals(60_000L, FRESH_WINDOW_MS)
    }

    @Test
    fun `an entry just inside the window is fresh and just outside it is not`() {
        cache.save(url, "\"e\"", "body")

        age(FRESH_WINDOW_MS / 2)
        assertTrue(cache.isFresh(assertNotNull(cache.load(url))), "half the window should still be fresh")

        age(FRESH_WINDOW_MS + 1_000)
        assertFalse(cache.isFresh(assertNotNull(cache.load(url))), "past the window it must be revalidated")
    }

    @Test
    fun `a stale entry is still readable, since its etag is what makes the next request free`() {
        cache.save(url, "\"e\"", "body")
        age(FRESH_WINDOW_MS * 10)

        val entry = assertNotNull(cache.load(url))
        assertFalse(cache.isFresh(entry))
        assertEquals("\"e\"", entry.etag)
        assertEquals("body", entry.body)
    }

    @Test
    fun `a clock moved backwards does not stretch the window`() {
        cache.save(url, "\"e\"", "body")
        // A timestamp in the future is what a backwards clock change looks like from here. Reading the
        // resulting negative age as "very fresh" would pin the entry until the clock caught up.
        dir.listFiles()!!.single().setLastModified(System.currentTimeMillis() + FRESH_WINDOW_MS * 100)

        assertFalse(cache.isFresh(assertNotNull(cache.load(url))))
    }

    @Test
    fun `marking an entry still fresh restarts its window`() {
        cache.save(url, "\"e\"", "body")
        age(FRESH_WINDOW_MS * 10)
        assertFalse(cache.isFresh(assertNotNull(cache.load(url))))

        cache.markStillFresh(url)

        val entry = assertNotNull(cache.load(url))
        assertTrue(cache.isFresh(entry), "a 304 says the body is current, so the window starts again")
        assertEquals("body", entry.body, "and the body itself must be untouched")
        assertEquals("\"e\"", entry.etag)
    }

    @Test
    fun `marking a url that was never stored does nothing and creates nothing`() {
        cache.markStillFresh(url)

        assertNull(cache.load(url))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `a body at the size limit is stored and one past it is not`() {
        cache.save(url, "\"e\"", "x".repeat(MAX_ENTRY_CHARS))
        assertNotNull(cache.load(url), "a body exactly at the limit is worth keeping")

        val other = url + "&big"
        cache.save(other, "\"e\"", "x".repeat(MAX_ENTRY_CHARS + 1))
        assertNull(cache.load(other), "a huge repo tree must not evict every entry that pays off")
    }

    @Test
    fun `an oversized body leaves an existing entry alone rather than half replacing it`() {
        cache.save(url, "\"v1\"", "small")
        cache.save(url, "\"v2\"", "x".repeat(MAX_ENTRY_CHARS + 1))

        val entry = assertNotNull(cache.load(url))
        assertEquals("small", entry.body)
        assertEquals("\"v1\"", entry.etag)
    }

    @Test
    fun `a file cut short mid-write reads as a miss instead of a wrong body`() {
        cache.save(url, "\"e\"", "body")
        // No newline: what a write killed partway through leaves behind. Serving that as a body would
        // hand a caller a truncated release listing to parse.
        dir.listFiles()!!.single().writeText("this write never finished")

        assertNull(cache.load(url))
    }

    @Test
    fun `an unreadable cache directory reads as a miss rather than throwing`() {
        // Losing the cache must cost one ordinary request, never a crashed refresh.
        val gone = ConditionalGetCache(File(dir, "does/not/exist"))

        assertNull(gone.load(url))
        gone.markStillFresh(url)
    }

    @Test
    fun `a directory it cannot create is survived by save too`() {
        val blocked = File(dir, "blocked")
        blocked.writeText("a file where the cache wants a directory")
        val cache = ConditionalGetCache(File(blocked, "entries"))

        cache.save(url, "\"e\"", "body")

        assertNull(cache.load(url))
    }

    @Test
    fun `the entry count stays capped, and the newest entries are the ones kept`() {
        val extra = 20
        repeat(MAX_ENTRIES + extra) { index ->
            cache.save("https://example.org/$index", "\"e$index\"", "body $index")
            // Timestamps set by hand: writing hundreds of small files can land several in the same
            // file-system tick, and this test is about which entry is oldest, not about that. Every
            // entry already written carries one of these, so the newest is the one just saved.
            dir.listFiles()!!.maxByOrNull { it.lastModified() }!!
                .setLastModified(1_000_000L + index * 1_000L)
        }

        assertEquals(MAX_ENTRIES, dir.listFiles()!!.size)
        assertNull(cache.load("https://example.org/0"), "the oldest entry should have been evicted")
        assertEquals(
            "body ${MAX_ENTRIES + extra - 1}",
            assertNotNull(cache.load("https://example.org/${MAX_ENTRIES + extra - 1}")).body,
            "the entry just written must always survive its own pruning",
        )
    }

    @Test
    fun `the total size stays capped`() {
        val body = "x".repeat(MAX_ENTRY_CHARS)
        val needed = (MAX_TOTAL_BYTES / MAX_ENTRY_CHARS).toInt() + 3
        repeat(needed) { index ->
            cache.save("https://example.org/$index", "\"e$index\"", body)
        }

        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue(
            total <= MAX_TOTAL_BYTES,
            "the cache grew to $total bytes, past its $MAX_TOTAL_BYTES ceiling",
        )
        assertNotNull(
            cache.load("https://example.org/${needed - 1}"),
            "the entry just written must survive even when it is what tipped the cache over",
        )
    }

    @Test
    fun `pruning for size keeps the newest entries`() {
        val body = "x".repeat(MAX_ENTRY_CHARS)
        val needed = (MAX_TOTAL_BYTES / MAX_ENTRY_CHARS).toInt() + 3
        repeat(needed) { index ->
            cache.save("https://example.org/$index", "\"e$index\"", body)
            dir.listFiles()!!.maxByOrNull { it.lastModified() }!!
                .setLastModified(1_000_000L + index * 1_000L)
        }

        assertNull(cache.load("https://example.org/0"), "the oldest entry should have gone first")
    }
}
