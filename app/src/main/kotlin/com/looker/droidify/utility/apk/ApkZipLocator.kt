package com.looker.droidify.utility.apk

/**
 * Locates one named entry inside a ZIP archive from just its End-Of-Central-Directory record and
 * Central Directory — the small, fixed "table of contents" at the end of every ZIP file — instead of
 * needing the whole archive. Used to find exactly which byte range of a remote, not-yet-downloaded
 * APK holds `resources.arsc`, so only that (a few hundred KB to a few MB) needs fetching rather than
 * the whole APK (often tens to hundreds of MB).
 *
 * Byte layout reference: PKWARE's .ZIP File Format Specification, sections 4.3.6 (Local File Header),
 * 4.3.12 (Central Directory File Header), 4.3.16 (End Of Central Directory record). Deliberately
 * doesn't support ZIP64 (a >4GB archive or >65535 entries) — no real-world APK is anywhere close to
 * either limit, and it's not worth the added complexity for a case that will never occur here.
 */
object ApkZipLocator {

    private val EOCD_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x05, 0x06) // "PK\x05\x06"
    private val CENTRAL_DIR_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x01, 0x02) // "PK\x01\x02"
    private val LOCAL_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4b, 0x03, 0x04) // "PK\x03\x04"

    /** Where the Central Directory itself lives within the ZIP file. */
    data class CentralDirectoryLocation(val offset: Long, val size: Long)

    /**
     * Finds the End-Of-Central-Directory record within [tail] — the last chunk of the ZIP file,
     * which must genuinely include the true end of the file — and returns where the Central
     * Directory lives. Null if no EOCD signature is found there (a truncated/too-short [tail], or a
     * ZIP64 archive, whose EOCD isn't this classic 32-bit record).
     */
    fun findCentralDirectory(tail: ByteArray): CentralDirectoryLocation? {
        val idx = lastIndexOf(tail, EOCD_SIGNATURE)
        if (idx < 0 || idx + 22 > tail.size) return null
        val centralDirectorySize = u32(tail, idx + 12)
        val centralDirectoryOffset = u32(tail, idx + 16)
        return CentralDirectoryLocation(centralDirectoryOffset, centralDirectorySize)
    }

    /** What's needed to fetch and decompress one Central Directory entry's actual file data. */
    data class CentralDirectoryEntry(
        val compressionMethod: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    /** Scans [centralDirectory] (exactly the byte range [findCentralDirectory] reported — the whole
     *  Central Directory, nothing more) for an entry named [entryName]. Null if not present. */
    fun findEntry(centralDirectory: ByteArray, entryName: String): CentralDirectoryEntry? {
        val nameBytes = entryName.toByteArray(Charsets.US_ASCII)
        var pos = 0
        val end = centralDirectory.size
        while (pos + 46 <= end) {
            if (!matchesAt(centralDirectory, pos, CENTRAL_DIR_SIGNATURE)) break
            val compressionMethod = u16(centralDirectory, pos + 10)
            val compressedSize = u32(centralDirectory, pos + 20)
            val uncompressedSize = u32(centralDirectory, pos + 24)
            val nameLength = u16(centralDirectory, pos + 28)
            val extraLength = u16(centralDirectory, pos + 30)
            val commentLength = u16(centralDirectory, pos + 32)
            val localHeaderOffset = u32(centralDirectory, pos + 42)
            val nameStart = pos + 46
            if (nameLength == nameBytes.size && regionEquals(centralDirectory, nameStart, nameBytes)) {
                return CentralDirectoryEntry(compressionMethod, compressedSize, uncompressedSize, localHeaderOffset)
            }
            pos = nameStart + nameLength + extraLength + commentLength
        }
        return null
    }

    /**
     * Scans [centralDirectory] for every entry whose name matches [predicate], returning their names.
     * Unlike [findEntry] (one known name), this is for enumerating a whole directory — e.g. every
     * per-language `.pak` file under `assets/locales/` — when the set of names isn't known ahead of
     * time. Metadata-only, like [findEntry]: a caller that needs an entry's actual data still looks it
     * up by exact name afterwards.
     */
    fun findEntryNames(centralDirectory: ByteArray, predicate: (String) -> Boolean): List<String> {
        val names = mutableListOf<String>()
        var pos = 0
        val end = centralDirectory.size
        while (pos + 46 <= end) {
            if (!matchesAt(centralDirectory, pos, CENTRAL_DIR_SIGNATURE)) break
            val nameLength = u16(centralDirectory, pos + 28)
            val extraLength = u16(centralDirectory, pos + 30)
            val commentLength = u16(centralDirectory, pos + 32)
            val nameStart = pos + 46
            if (nameStart + nameLength <= end) {
                val name = String(centralDirectory, nameStart, nameLength, Charsets.US_ASCII)
                if (predicate(name)) names += name
            }
            pos = nameStart + nameLength + extraLength + commentLength
        }
        return names
    }

    /**
     * Reads a Local File Header ([localHeaderBytes], starting exactly at [CentralDirectoryEntry
     * .localHeaderOffset]) to find precisely where the entry's actual file data begins — its name and
     * extra-field lengths can differ slightly from the Central Directory's copy, so this can't be
     * assumed from the Central Directory entry alone. Null if the bytes don't start with a Local File
     * Header signature.
     */
    fun localFileDataOffset(localHeaderBytes: ByteArray, localHeaderOffset: Long): Long? {
        if (localHeaderBytes.size < 30 || !matchesAt(localHeaderBytes, 0, LOCAL_HEADER_SIGNATURE)) {
            return null
        }
        val nameLength = u16(localHeaderBytes, 26)
        val extraLength = u16(localHeaderBytes, 28)
        return localHeaderOffset + 30 + nameLength + extraLength
    }

    private fun matchesAt(b: ByteArray, off: Int, signature: ByteArray): Boolean {
        if (off < 0 || off + signature.size > b.size) return false
        for (i in signature.indices) if (b[off + i] != signature[i]) return false
        return true
    }

    private fun regionEquals(b: ByteArray, off: Int, other: ByteArray): Boolean {
        if (off < 0 || off + other.size > b.size) return false
        for (i in other.indices) if (b[off + i] != other[i]) return false
        return true
    }

    private fun lastIndexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (i in haystack.size - needle.size downTo 0) {
            if (matchesAt(haystack, i, needle)) return i
        }
        return -1
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)
}
