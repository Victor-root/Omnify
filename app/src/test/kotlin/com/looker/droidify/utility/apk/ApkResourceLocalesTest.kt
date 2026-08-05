package com.looker.droidify.utility.apk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Reading languages out of a `resources.arsc` means walking a binary format whose own contents say
 * how far to jump next, and the file being walked came off the network. This covers the case where
 * those jumps point past the end of what was actually received.
 *
 * What matters is not that a malformed file is rejected (it always was) but *how much* it takes down
 * with it. Every package in the file is walked by one loop under a single catch-all, so a package
 * chunk that ends before the header field the walk starts from used to fail the whole read: no
 * languages for the file at all, including packages already walked successfully. Skipping just that
 * package is what the walk's own doc says should happen when its key pool can't be read.
 */
class ApkResourceLocalesTest {

    @Test
    fun `a package chunk cut short is skipped, not fatal to the whole file`() {
        // Declares a package far longer than the bytes actually present: what a truncated download,
        // or a file built to trip this, looks like. The read must come back with an answer (no
        // languages found) rather than the null that says the whole file failed to parse.
        val arsc = resourceTable(packageChunk(declaredSize = 4096, actualBytes = 100))

        val locales = ApkResourceLocales.localeCodes(arsc)

        assertNotNull(locales, "A short package chunk failed the entire file instead of being skipped")
        assertEquals(emptyList(), locales)
    }

    @Test
    fun `a package whose header is present is walked`() {
        // 288 bytes is a real ResTable_package header, so this one is walked rather than skipped. It
        // carries no type chunks, hence no languages. The point is that it is not rejected on its
        // size the way the truncated one above is, which is what would break language detection for
        // every ordinary app.
        val arsc = resourceTable(packageChunk(declaredSize = 288, actualBytes = 288))

        val locales = ApkResourceLocales.localeCodes(arsc)

        assertNotNull(locales, "A well-formed package chunk was rejected")
        assertEquals(emptyList(), locales)
    }

    @Test
    fun `a file that is not a resource table is refused`() {
        assertEquals(null, ApkResourceLocales.localeCodes(ByteArray(64)))
    }

    @Test
    fun `an empty file is refused`() {
        assertEquals(null, ApkResourceLocales.localeCodes(ByteArray(0)))
    }

    /** A `ResTable_header` (type 0x0002, 12-byte header, then a package count) wrapping [body]. */
    private fun resourceTable(body: ByteArray): ByteArray {
        val header = ByteArray(TABLE_HEADER_SIZE)
        header.putU16(0, RES_TABLE_TYPE)
        header.putU16(2, TABLE_HEADER_SIZE)
        header.putU32(4, (TABLE_HEADER_SIZE + body.size).toLong())
        header.putU32(8, 1)
        return header + body
    }

    /**
     * A `ResTable_package` chunk (type 0x0200) that announces [declaredSize] bytes while only
     * [actualBytes] of it exist, so [declaredSize] > [actualBytes] produces the truncated case.
     */
    private fun packageChunk(declaredSize: Int, actualBytes: Int): ByteArray {
        val chunk = ByteArray(actualBytes)
        chunk.putU16(0, RES_TABLE_PACKAGE_TYPE)
        chunk.putU16(2, PACKAGE_HEADER_SIZE)
        chunk.putU32(4, declaredSize.toLong())
        return chunk
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        for (i in 0 until 4) this[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private companion object {
        const val RES_TABLE_TYPE = 0x0002
        const val RES_TABLE_PACKAGE_TYPE = 0x0200
        const val TABLE_HEADER_SIZE = 12
        const val PACKAGE_HEADER_SIZE = 288
    }
}
