package com.looker.droidify.utility.apk

import android.util.Log
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.RangeResult
import com.looker.droidify.network.header.HeadersBuilder
import java.util.zip.Inflater

/**
 * Fetches a remote, not-yet-downloaded APK's real launcher icon — via HTTP range requests against its
 * ZIP End-Of-Central-Directory, Central Directory, `AndroidManifest.xml`, `resources.arsc`, and finally
 * the icon file itself — for a catalogue entry whose repo index carries no icon of its own (confirmed
 * real: F-Droid's own official repo declares no `icon` field at all for some apps, e.g. PPSSPP, despite
 * the app plainly having one). Reads only the few small pieces actually needed, never the whole APK
 * (tens to hundreds of MB for a modern app).
 *
 * Deliberately narrow in what it resolves: [ApkBinaryManifest.applicationIconResourceId] gives the
 * `<application android:icon>` reference, then [resolveRasterIconPath] resolves it against
 * `resources.arsc` to the highest-density RASTER (`.png`/`.webp`/`.jpg`) file among that resource id's
 * configs — deliberately skipping any config whose value is an adaptive-icon XML (`density == 0xFFFE`,
 * Android's "any density" qualifier: an `<adaptive-icon>` combining a background and foreground
 * drawable, which this can't safely render without a full vector/XML compositor). A modern app ships
 * both, so there is nearly always a raster fallback to pick up; the rare app shipping ONLY an adaptive
 * icon (no raster densities at all) returns null here — display falls back to whatever this repo/catalog
 * screen already does for "no icon" (installed launcher icon, then a placeholder), never a guess.
 *
 * Byte layout reference: same as [ApkBinaryManifest] (binary XML) and [ApkResourceLocales]
 * (`resources.arsc`'s chunk/string-pool format) — this reader's own resource-id resolution
 * ([resolveRasterIconPath]) was cross-checked field-by-field against `pyaxmlparser`'s independent
 * implementation on a real APK (PPSSPP's official F-Droid release): both resolve `android:icon`'s
 * reference to the exact same file, `res/o-.png`, the highest-density (640) raster config among that
 * app's six `mipmap/ic_launcher` configs — the seventh, an `anydpi-v26` adaptive-icon XML, correctly
 * skipped by both.
 */
object RemoteApkIconReader {

    private const val TAG = "RemoteApkIconReader"
    private const val MANIFEST_ENTRY_NAME = "AndroidManifest.xml"
    private const val ARSC_ENTRY_NAME = "resources.arsc"

    /** Same bound [RemoteApkManifestReader] uses — a compiled manifest is at most a few hundred KB even
     *  for an unusually component-heavy app. */
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024

    /** Same bound [RemoteApkLocaleReader] uses (see its own doc comment for why this is sized against a
     *  real, confirmed 36MB outlier rather than the common case alone). */
    private const val MAX_ARSC_BYTES = 64L * 1024 * 1024

    /** A launcher icon is a few KB to, at the very most, a few hundred KB (a large adaptive-icon
     *  foreground PNG, say) — this only guards against reading an unreasonable amount of data for a
     *  pathological or adversarial file, not a size a real icon is expected to approach. */
    private const val MAX_ICON_BYTES = 4L * 1024 * 1024

    /** Same tail window the sibling readers use to find the EOCD. */
    private const val TAIL_FETCH_BYTES = 128L * 1024

    /**
     * The launcher icon's raw file bytes (PNG/WEBP/JPEG, whatever the APK actually ships), or null when
     * they couldn't be read at all — no range support, a network error, a malformed ZIP/manifest/
     * resource table, the icon resource missing or only available as an adaptive-icon XML, or the file
     * oversized/compressed with an unsupported method. The caller should change nothing in that case,
     * same "don't act on uncertainty" rule every other reader in this package follows. Never throws.
     *
     * [expectedTotalSize] (when non-null) is compared against the remote file's real total size — see
     * [RemoteApkManifestReader]'s own doc comment for exactly why (mirror-identity, not integrity).
     */
    suspend fun fetchIconBytes(
        downloader: Downloader,
        apkUrl: String,
        expectedTotalSize: Long? = null,
        headers: HeadersBuilder.() -> Unit = {},
    ): ByteArray? {
        val tailResult = downloader.getRange(apkUrl) {
            inRangeSuffix(TAIL_FETCH_BYTES)
            headers()
        }
        val tail = when (tailResult) {
            is RangeResult.Success -> {
                if (expectedTotalSize != null && tailResult.totalSize != expectedTotalSize) {
                    Log.d(
                        TAG,
                        "$apkUrl: total size mismatch (remote ${tailResult.totalSize}B, " +
                            "expected ${expectedTotalSize}B) — not the same artifact",
                    )
                    return null
                }
                tailResult.bytes
            }
            RangeResult.RangeNotSupported, is RangeResult.Failed ->
                return null.also { Log.d(TAG, "$apkUrl: tail fetch failed (range not supported or network error)") }
        }
        val centralDir = ApkZipLocator.findCentralDirectory(tail)
            ?: return null.also { Log.d(TAG, "$apkUrl: no End-Of-Central-Directory found in ${tail.size}B tail") }
        if (centralDir.size <= 0 || centralDir.size > MAX_ARSC_BYTES) {
            Log.d(TAG, "$apkUrl: central directory size out of bounds (${centralDir.size}B)")
            return null
        }
        val centralDirectoryBytes = fetchBytes(downloader, apkUrl) {
            inRange(centralDir.offset, centralDir.offset + centralDir.size - 1)
            headers()
        } ?: return null.also { Log.d(TAG, "$apkUrl: central directory fetch failed") }

        val manifest = fetchEntry(downloader, apkUrl, centralDirectoryBytes, MANIFEST_ENTRY_NAME, MAX_MANIFEST_BYTES, headers)
            ?: return null.also { Log.d(TAG, "$apkUrl: $MANIFEST_ENTRY_NAME fetch failed") }
        val iconResId = ApkBinaryManifest.applicationIconResourceId(manifest)
            ?: return null.also { Log.d(TAG, "$apkUrl: no <application android:icon> reference in manifest") }

        val arsc = fetchEntry(downloader, apkUrl, centralDirectoryBytes, ARSC_ENTRY_NAME, MAX_ARSC_BYTES, headers)
            ?: return null.also { Log.d(TAG, "$apkUrl: $ARSC_ENTRY_NAME fetch failed") }
        val iconPath = resolveRasterIconPath(arsc, iconResId)
            ?: return null.also { Log.d(TAG, "$apkUrl: icon resource ${iconResId.toString(16)} has no raster config") }

        return fetchEntry(downloader, apkUrl, centralDirectoryBytes, iconPath, MAX_ICON_BYTES, headers)
            ?: return null.also { Log.d(TAG, "$apkUrl: icon file $iconPath fetch failed") }
    }

    /** Fetches and decompresses one named ZIP entry, given the already-fetched Central Directory bytes
     *  — the shared last step [fetchIconBytes] needs three times (manifest, resources.arsc, the icon
     *  file itself) once the Central Directory is in hand. Null on any failure or size-bound violation,
     *  same "couldn't determine" contract as the sibling readers. */
    private suspend fun fetchEntry(
        downloader: Downloader,
        apkUrl: String,
        centralDirectoryBytes: ByteArray,
        entryName: String,
        maxBytes: Long,
        headers: HeadersBuilder.() -> Unit,
    ): ByteArray? {
        val entry = ApkZipLocator.findEntry(centralDirectoryBytes, entryName) ?: return null
        if (entry.uncompressedSize <= 0 || entry.uncompressedSize > maxBytes) return null
        if (entry.compressionMethod != COMPRESSION_STORED && entry.compressionMethod != COMPRESSION_DEFLATED) {
            return null
        }
        val localHeaderBytes = fetchBytes(downloader, apkUrl) {
            inRange(entry.localHeaderOffset, entry.localHeaderOffset + 29)
            headers()
        } ?: return null
        val dataStart = ApkZipLocator.localFileDataOffset(localHeaderBytes, entry.localHeaderOffset) ?: return null
        val compressedBytes = fetchBytes(downloader, apkUrl) {
            inRange(dataStart, dataStart + entry.compressedSize - 1)
            headers()
        } ?: return null
        val bytes = when (entry.compressionMethod) {
            COMPRESSION_STORED -> compressedBytes
            else -> inflateRaw(compressedBytes, entry.uncompressedSize.toInt()) ?: return null
        }
        return bytes.takeIf { it.size.toLong() == entry.uncompressedSize }
    }

    /**
     * Resolves [resourceId] (packageId<<24 | typeId<<16 | entryId, as read off `<application
     * android:icon>`) against [arsc]'s package/type/config chunks to the file path of its
     * highest-density RASTER config, or null when there's no populated config for it at all, or every
     * populated config is an adaptive-icon XML (`density` == [DENSITY_ANY]) rather than a plain image —
     * see this object's own doc comment for why that case isn't chased further. Never throws (wrapped
     * by [fetchIconBytes]'s callers already treating any failure here the same way).
     */
    private fun resolveRasterIconPath(arsc: ByteArray, resourceId: Int): String? {
        if (arsc.size < 8 || u16(arsc, 0) != RES_TABLE_TYPE) return null
        val targetPackageId = (resourceId ushr 24) and 0xFF
        val targetTypeId = (resourceId ushr 16) and 0xFF
        val targetEntryId = resourceId and 0xFFFF
        val topHeaderSize = u16(arsc, 2)
        val topSize = u32(arsc, 4)
        var pos = topHeaderSize.toLong()
        val end = minOf(topSize, arsc.size.toLong())
        var globalPool: List<String>? = null
        var best: Pair<Int, String>? = null // (density, path)
        while (pos + 8 <= end) {
            val chunkType = u16(arsc, pos.toInt())
            val chunkSize = u32(arsc, pos.toInt() + 4)
            if (chunkSize <= 0 || pos + chunkSize > end) break
            when (chunkType) {
                RES_STRING_POOL_TYPE -> if (globalPool == null) globalPool = parseStringPool(arsc, pos.toInt())
                RES_TABLE_PACKAGE_TYPE -> {
                    val pool = globalPool
                    if (pool != null && u32(arsc, pos.toInt() + 8).toInt() == targetPackageId) {
                        val found = resolveInPackage(arsc, pos, pos + chunkSize, targetTypeId, targetEntryId, pool)
                        for (candidate in found) {
                            if (best == null || candidate.first > best.first) best = candidate
                        }
                    }
                }
            }
            pos += chunkSize
        }
        return best?.second
    }

    /** Every raster (density, path) candidate for [targetEntryId] of [targetTypeId] within one
     *  `ResTable_package` chunk — walking its `ResTable_type` children, one per density/locale/etc.
     *  config that type ships, skipping any whose value isn't a plain string (a complex/bag entry, or a
     *  non-string typed value) or whose density is [DENSITY_ANY] (an adaptive-icon XML, not a raster). */
    private fun resolveInPackage(
        arsc: ByteArray,
        pkgStart: Long,
        pkgEnd: Long,
        targetTypeId: Int,
        targetEntryId: Int,
        globalPool: List<String>,
    ): List<Pair<Int, String>> {
        val pkgHeaderSize = u16(arsc, pkgStart.toInt() + 2)
        var pos = pkgStart + pkgHeaderSize
        val results = mutableListOf<Pair<Int, String>>()
        while (pos + 8 <= pkgEnd) {
            val chunkType = u16(arsc, pos.toInt())
            val chunkSize = u32(arsc, pos.toInt() + 4)
            if (chunkSize <= 0) break
            if (chunkType == RES_TABLE_TYPE_TYPE) {
                val typeId = arsc[(pos + 8).toInt()].toInt() and 0xFF
                if (typeId == targetTypeId) {
                    readRasterEntry(arsc, pos, targetEntryId, globalPool)?.let { results.add(it) }
                }
            }
            pos += chunkSize
        }
        return results
    }

    /** One `ResTable_type` chunk's (density, path) for [targetEntryId], or null when that entry isn't
     *  populated in this particular config, isn't a plain string value, or this config is
     *  [DENSITY_ANY]. Offsets-array encoding (dense/sparse/offset16) mirrors [ApkResourceLocales]'
     *  [readTypeChunk]-equivalent decoding — see its doc comment for the byte-format rationale. */
    private fun readRasterEntry(
        arsc: ByteArray,
        chunkStart: Long,
        targetEntryId: Int,
        globalPool: List<String>,
    ): Pair<Int, String>? {
        val flags = arsc[(chunkStart + 9).toInt()].toInt() and 0xFF
        if ((flags and (FLAG_SPARSE or FLAG_OFFSET16).inv()) != 0) return null
        val sparse = (flags and FLAG_SPARSE) != 0
        val offset16 = (flags and FLAG_OFFSET16) != 0
        if (sparse && offset16) return null
        val entryCount = u32(arsc, (chunkStart + 12).toInt())
        if (entryCount < 0 || entryCount > arsc.size) return null
        val entriesStart = u32(arsc, (chunkStart + 16).toInt())
        val configStart = (chunkStart + 20).toInt()
        if (configStart + 16 > arsc.size) return null
        val configSize = u32(arsc, configStart)
        if (configSize < 16) return null
        // ResTable_config: size(4) + imsi(4) + locale(4) + orientation(1)+touchscreen(1)+density(2).
        val density = u16(arsc, configStart + 12 + 2)
        if (density == DENSITY_ANY) return null
        val offsetsStart = configStart + configSize.toInt()
        val entryCountInt = entryCount.toInt()
        val entryOffset: Long = when {
            offset16 -> {
                if (targetEntryId >= entryCountInt) return null
                if (offsetsStart + entryCountInt * 2 > arsc.size) return null
                val raw = u16(arsc, offsetsStart + targetEntryId * 2)
                if (raw == NO_ENTRY_16) return null
                raw.toLong() * 4
            }
            sparse -> {
                if (offsetsStart + entryCountInt * 4 > arsc.size) return null
                var found = -1L
                for (i in 0 until entryCountInt) {
                    if (u16(arsc, offsetsStart + i * 4) == targetEntryId) {
                        found = u16(arsc, offsetsStart + i * 4 + 2).toLong() * 4
                        break
                    }
                }
                if (found < 0) return null
                found
            }
            else -> {
                if (targetEntryId >= entryCountInt) return null
                if (offsetsStart + entryCountInt * 4 > arsc.size) return null
                val raw = u32(arsc, offsetsStart + targetEntryId * 4)
                if (raw == NO_ENTRY) return null
                raw
            }
        }
        val entryDataStart = chunkStart + entriesStart + entryOffset
        if (entryDataStart + 8 > arsc.size) return null
        val entryFlags = u16(arsc, entryDataStart.toInt() + 2)
        if ((entryFlags and FLAG_COMPLEX) != 0) return null
        val valueStart = entryDataStart.toInt() + 8
        if (valueStart + 8 > arsc.size) return null
        val valueDataType = arsc[valueStart + 3].toInt() and 0xFF
        if (valueDataType != TYPE_STRING) return null
        val valueData = u32(arsc, valueStart + 4).toInt()
        val path = globalPool.getOrNull(valueData) ?: return null
        if (!RASTER_EXTENSION_REGEX.containsMatchIn(path)) return null
        return density to path
    }

    /** Parses a `ResStringPool` chunk — same format [ApkResourceLocales]' own pool reader uses (kept
     *  duplicated here rather than shared, matching this package's existing convention of each reader
     *  keeping its own small, self-contained copy — see [RemoteApkLocaleReader]/[RemoteApkManifestReader]'s
     *  own duplicated `inflateRaw`). */
    private fun parseStringPool(arsc: ByteArray, poolStart: Int): List<String>? {
        if (poolStart < 0 || poolStart + 28 > arsc.size) return null
        if (u16(arsc, poolStart) != RES_STRING_POOL_TYPE) return null
        val headerSize = u16(arsc, poolStart + 2)
        val stringCount = u32(arsc, poolStart + 8).toInt()
        val flags = u32(arsc, poolStart + 16).toInt()
        val stringsStart = u32(arsc, poolStart + 20).toInt()
        if (stringCount < 0 || stringCount > arsc.size) return null
        val utf8 = (flags and UTF8_FLAG) != 0
        val offsetsStart = poolStart + headerSize
        if (offsetsStart + stringCount * 4 > arsc.size) return null
        val dataStart = poolStart + stringsStart
        return (0 until stringCount).map { i ->
            val entryOffset = u32(arsc, offsetsStart + i * 4).toInt()
            readPoolString(arsc, dataStart, entryOffset, utf8) ?: ""
        }
    }

    private fun readPoolString(arsc: ByteArray, dataStart: Int, entryByteOffset: Int, utf8: Boolean): String? {
        val start = dataStart + entryByteOffset
        if (start < 0 || start >= arsc.size) return null
        return runCatching {
            if (utf8) {
                val (_, afterCharLen) = decodeLength8(arsc, start)
                val (byteLen, afterByteLen) = decodeLength8(arsc, afterCharLen)
                if (afterByteLen + byteLen > arsc.size) return null
                String(arsc, afterByteLen, byteLen, Charsets.UTF_8)
            } else {
                val (unitLen, afterLen) = decodeLength16(arsc, start)
                val byteLen = unitLen * 2
                if (afterLen + byteLen > arsc.size) return null
                String(arsc, afterLen, byteLen, Charsets.UTF_16LE)
            }
        }.getOrNull()
    }

    private fun decodeLength8(arsc: ByteArray, pos: Int): Pair<Int, Int> {
        val b0 = arsc[pos].toInt() and 0xFF
        return if (b0 and 0x80 != 0) {
            val b1 = arsc[pos + 1].toInt() and 0xFF
            (((b0 and 0x7F) shl 8) or b1) to (pos + 2)
        } else {
            b0 to (pos + 1)
        }
    }

    private fun decodeLength16(arsc: ByteArray, pos: Int): Pair<Int, Int> {
        val u0 = u16(arsc, pos)
        return if (u0 and 0x8000 != 0) {
            val u1 = u16(arsc, pos + 2)
            (((u0 and 0x7FFF) shl 16) or u1) to (pos + 4)
        } else {
            u0 to (pos + 2)
        }
    }

    private suspend fun fetchBytes(
        downloader: Downloader,
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ): ByteArray? = when (val result = downloader.getRange(url, headers)) {
        is RangeResult.Success -> result.bytes
        RangeResult.RangeNotSupported, is RangeResult.Failed -> null
    }

    /** Inflates raw (headerless) DEFLATE data — mirrors [RemoteApkLocaleReader]/[RemoteApkManifestReader]'s
     *  own identical helper (see either's doc comment for why "no wrap" mode specifically). */
    private fun inflateRaw(compressed: ByteArray, expectedSize: Int): ByteArray? = runCatching {
        val inflater = Inflater(true)
        try {
            inflater.setInput(compressed)
            val output = ByteArray(expectedSize)
            var written = 0
            var stalled = false
            while (written < expectedSize && !inflater.finished() && !stalled) {
                val n = inflater.inflate(output, written, expectedSize - written)
                stalled = n == 0 && inflater.needsInput()
                written += n
            }
            if (written == expectedSize) output else null
        } finally {
            inflater.end()
        }
    }.getOrNull()

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201

    /** [ResStringPool_header.flags] bit meaning the pool's strings are UTF-8 rather than UTF-16. */
    private const val UTF8_FLAG = 1 shl 8

    /** An offsets-array slot with this value means "this config doesn't define this particular resource
     *  id" for the dense (uint32) encoding — see [ApkResourceLocales]'s identical constant. */
    private const val NO_ENTRY = 0xFFFFFFFFL

    /** As [NO_ENTRY], for the compact `FLAG_OFFSET16` (uint16) encoding. */
    private const val NO_ENTRY_16 = 0xFFFF

    /** [ResTable_type.flags] bits — see [ApkResourceLocales]'s identical constants for the full
     *  aapt2-optimization rationale (sparse/compact-entries encodings a real self-hosted repo's
     *  developer-built release can carry). */
    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02

    /** `ResTable_entry.flags` bit meaning this is a `ResTable_map_entry` (a complex/bag resource, e.g. a
     *  style or array) rather than a plain value — a launcher icon is never one of these, so such an
     *  entry is skipped rather than misread as a plain `Res_value`. */
    private const val FLAG_COMPLEX = 0x0001

    /** `Res_value.dataType` for a string: `data` is a global-string-pool index — what a plain file-path
     *  resource value (an image, or an XML drawable) always compiles to. */
    private const val TYPE_STRING = 0x03

    /** Android's `DENSITY_ANY` qualifier (`anydpi`, e.g. `mipmap-anydpi-v26/`): the config an
     *  adaptive-icon XML resource is declared under, never a raster image. */
    private const val DENSITY_ANY = 0xFFFE

    /** A resolved icon path ending in one of these is an actual raster image this can display as-is;
     *  anything else (an XML drawable, most commonly) is skipped. Case-insensitive: a self-hosted
     *  build's file names aren't guaranteed lowercase. */
    private val RASTER_EXTENSION_REGEX = Regex("""\.(?:png|webp|jpe?g)$""", RegexOption.IGNORE_CASE)

    /** ZIP `compressionMethod` values [fetchEntry] knows how to read — same pair the sibling readers
     *  support (see [RemoteApkManifestReader]'s identical constants). */
    private const val COMPRESSION_STORED = 0
    private const val COMPRESSION_DEFLATED = 8
}
