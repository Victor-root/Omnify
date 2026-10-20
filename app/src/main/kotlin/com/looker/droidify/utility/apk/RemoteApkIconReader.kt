package com.looker.droidify.utility.apk

import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.RangeResult
import com.looker.droidify.network.header.HeadersBuilder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fetches a remote, not-yet-downloaded APK's real launcher icon — via HTTP range requests against its
 * ZIP End-Of-Central-Directory, Central Directory, `AndroidManifest.xml`, `resources.arsc`, and finally
 * the icon file itself — for a catalogue entry whose repo index carries no icon of its own (confirmed
 * real: F-Droid's own official repo declares no `icon` field at all for some apps, e.g. PPSSPP, despite
 * the app plainly having one). Reads only the few small pieces actually needed, never the whole APK
 * (tens to hundreds of MB for a modern app).
 *
 * [fetchIconBytes] is deliberately narrow in what it resolves: [ApkBinaryManifest.applicationIconResourceId]
 * gives the `<application android:icon>` reference, then [resolveRasterIconPath] resolves it against
 * `resources.arsc` to the highest-density RASTER (`.png`/`.webp`/`.jpg`) file among that resource id's
 * configs — deliberately skipping any config whose value is an adaptive-icon XML (`density == 0xFFFE`,
 * Android's "any density" qualifier: an `<adaptive-icon>` combining a background and foreground
 * drawable, which this can't safely render without a full vector/XML compositor). A modern app ships
 * both, so there is nearly always a raster fallback to pick up; the rare app shipping ONLY an adaptive
 * icon (no raster densities at all) returns null here — display falls back to whatever this repo/catalog
 * screen already does for "no icon" (installed launcher icon, then a placeholder), never a guess.
 * [fetchIconArchive] covers that case instead, by handing Android itself what it needs to draw the icon.
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
    private const val RES_DIRECTORY = "res/"

    private fun logD(message: String) {
        if (BuildConfig.TRACES) {
            Log.d(TAG, message)
        }
    }

    /** Same bound [RemoteApkManifestReader] uses — a compiled manifest is at most a few hundred KB even
     *  for an unusually component-heavy app. */
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024

    /** Same bound [RemoteApkLocaleReader] uses (see its own doc comment for why this is sized against a
     *  real, confirmed 36MB outlier rather than the common case alone). */
    private const val MAX_ARSC_BYTES = 64L * 1024 * 1024

    /** A launcher icon is a few KB to, at the very most, a few hundred KB (a large adaptive-icon
     *  foreground PNG, say), every file it is drawn from included. This only guards against reading an
     *  unreasonable amount of data for a pathological or adversarial file, not a size a real icon is
     *  expected to approach. */
    private const val MAX_ICON_BYTES = 4L * 1024 * 1024

    /** Files [fetchIconArchive] follows an icon to, at most: Google Maps' adaptive icon reaches 12, its
     *  two layers' five densities each plus two XML files. */
    private const val MAX_ICON_FILES = 64

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
    ): ByteArray? = try {
        fetchRasterIcon(downloader, apkUrl, expectedTotalSize, headers)
    } catch (e: DownloadFailed) {
        null
    }

    private suspend fun fetchRasterIcon(
        downloader: Downloader,
        apkUrl: String,
        expectedTotalSize: Long?,
        headers: HeadersBuilder.() -> Unit,
    ): ByteArray? {
        val apk = openIconResources(downloader, apkUrl, expectedTotalSize, headers) ?: return null
        val iconPath = resolveRasterIconPath(apk.table, apk.iconResId)
            ?: return null.also { logD("$apkUrl: icon resource ${apk.iconResId.toString(16)} has no raster config") }
        return apk.entry(iconPath, MAX_ICON_BYTES)
            ?: return null.also { logD("$apkUrl: icon file $iconPath fetch failed") }
    }

    /**
     * Writes to [target] a stripped-down copy of the APK holding only what Android needs to draw its
     * launcher icon itself: the manifest, `resources.arsc`, and every file the icon resource reaches,
     * followed through each config and each reference an XML file makes (an `<adaptive-icon>`'s layers,
     * an `<inset>`'s drawable…). This is what an app shipping its icon only as an adaptive icon needs,
     * the case [fetchIconBytes] can't serve: confirmed on the Google Maps builds of nullcpy/rvb, whose
     * `mipmap-anydpi/ungoogled_legacy_icon.xml` has no raster config at all, its layers being rasters of
     * other resources. Anything but [IconArchive.WRITTEN] leaves [target] incomplete, for the caller to
     * delete.
     */
    suspend fun fetchIconArchive(
        downloader: Downloader,
        apkUrl: String,
        target: File,
        headers: HeadersBuilder.() -> Unit = {},
    ): IconArchive = try {
        writeIconArchive(downloader, apkUrl, target, headers)
    } catch (e: DownloadFailed) {
        logD("$apkUrl: a download failed, worth trying again")
        IconArchive.FAILED
    }

    /** What [fetchIconArchive] came to. */
    enum class IconArchive {
        WRITTEN,

        /** A final answer for this APK: it couldn't be read as one, or its icon reaches more than
         *  [MAX_ICON_FILES] files or [MAX_ICON_BYTES] in all. */
        UNAVAILABLE,

        /** A download failed (a dropped or timed-out connection, say): the APK may well be fine. */
        FAILED,
    }

    private suspend fun writeIconArchive(
        downloader: Downloader,
        apkUrl: String,
        target: File,
        headers: HeadersBuilder.() -> Unit,
    ): IconArchive {
        val apk = openIconResources(downloader, apkUrl, expectedTotalSize = null, headers)
            ?: return IconArchive.UNAVAILABLE
        val files = collectIconFiles(apk) ?: return IconArchive.UNAVAILABLE.also {
            logD("$apkUrl: icon resource ${apk.iconResId.toString(16)} not collected")
        }
        val written = runCatching {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                // First and uncompressed: Android then maps the table straight from the file rather than
                // inflating a copy in memory, its data starting 4-byte aligned right after this header.
                zip.putNextEntry(
                    ZipEntry(ARSC_ENTRY_NAME).apply {
                        method = ZipEntry.STORED
                        size = apk.arsc.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(apk.arsc) }.value
                    },
                )
                zip.write(apk.arsc)
                for ((name, bytes) in mapOf(MANIFEST_ENTRY_NAME to apk.manifest) + files) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                }
            }
        }.isSuccess
        return if (written) IconArchive.WRITTEN else IconArchive.UNAVAILABLE
    }

    /** Thrown on a download that failed rather than answered, so a passing network problem isn't taken
     *  for a final answer about the APK. */
    private class DownloadFailed : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** The remote APK's pieces both [fetchIconBytes] and [fetchIconArchive] start from, with [entry]
     *  reading any further file out of it. */
    private class IconResources(
        val manifest: ByteArray,
        val arsc: ByteArray,
        val table: ResourceTable,
        val iconResId: Int,
        val entry: suspend (name: String, maxBytes: Long) -> ByteArray?,
    )

    private suspend fun openIconResources(
        downloader: Downloader,
        apkUrl: String,
        expectedTotalSize: Long?,
        headers: HeadersBuilder.() -> Unit,
    ): IconResources? {
        val tailResult = downloader.getRange(apkUrl) {
            inRangeSuffix(TAIL_FETCH_BYTES)
            headers()
        }
        val tail = when (tailResult) {
            is RangeResult.Success -> {
                if (expectedTotalSize != null && tailResult.totalSize != expectedTotalSize) {
                    logD(
                        "$apkUrl: total size mismatch (remote ${tailResult.totalSize}B, " +
                            "expected ${expectedTotalSize}B) — not the same artifact",
                    )
                    return null
                }
                tailResult.bytes
            }
            RangeResult.RangeNotSupported ->
                return null.also { logD("$apkUrl: tail fetch failed (range not supported)") }
            is RangeResult.Failed -> throw DownloadFailed()
        }
        val centralDir = ApkZipLocator.findCentralDirectory(tail)
            ?: return null.also { logD("$apkUrl: no End-Of-Central-Directory found in ${tail.size}B tail") }
        if (centralDir.size <= 0 || centralDir.size > MAX_ARSC_BYTES) {
            logD("$apkUrl: central directory size out of bounds (${centralDir.size}B)")
            return null
        }
        val centralDirectoryBytes = fetchBytes(downloader, apkUrl) {
            inRange(centralDir.offset, centralDir.offset + centralDir.size - 1)
            headers()
        } ?: return null.also { logD("$apkUrl: central directory fetch failed") }
        val entry: suspend (String, Long) -> ByteArray? = { name, maxBytes ->
            fetchEntry(downloader, apkUrl, centralDirectoryBytes, name, maxBytes, headers)
        }

        val manifest = entry(MANIFEST_ENTRY_NAME, MAX_MANIFEST_BYTES)
            ?: return null.also { logD("$apkUrl: $MANIFEST_ENTRY_NAME fetch failed") }
        val iconResId = ApkBinaryManifest.applicationIconResourceId(manifest)
            ?: return null.also { logD("$apkUrl: no <application android:icon> reference in manifest") }

        val arsc = entry(ARSC_ENTRY_NAME, MAX_ARSC_BYTES)
            ?: return null.also { logD("$apkUrl: $ARSC_ENTRY_NAME fetch failed") }
        val table = ResourceTable.parse(arsc)
            ?: return null.also { logD("$apkUrl: $ARSC_ENTRY_NAME couldn't be parsed") }
        return IconResources(manifest, arsc, table, iconResId, entry)
    }

    /**
     * Every file [IconResources.iconResId] is drawn from, by path, or null when one couldn't be read or
     * the caps are passed. Only the app's own resources are followed: a framework one (package 0x01) is
     * already on the device.
     */
    private suspend fun collectIconFiles(apk: IconResources): Map<String, ByteArray>? {
        val packageId = apk.iconResId ushr 24
        val files = LinkedHashMap<String, ByteArray>()
        val pending = ArrayDeque(listOf(apk.iconResId))
        val seen = HashSet<Int>()
        var totalBytes = 0L
        while (pending.isNotEmpty()) {
            val resourceId = pending.removeFirst()
            if (resourceId ushr 24 != packageId || !seen.add(resourceId)) continue
            for (value in apk.table.values(resourceId)) {
                if (value.dataType == TYPE_REFERENCE) {
                    pending.add(value.data)
                    continue
                }
                if (value.dataType != TYPE_STRING) continue
                val path = apk.table.strings.getOrNull(value.data)
                    ?.takeIf { it.startsWith(RES_DIRECTORY) && it !in files }
                    ?: continue
                if (files.size >= MAX_ICON_FILES) return null
                val bytes = apk.entry(path, MAX_ICON_BYTES) ?: return null
                totalBytes += bytes.size
                if (totalBytes > MAX_ICON_BYTES) return null
                files[path] = bytes
                if (path.endsWith(".xml", ignoreCase = true)) {
                    pending.addAll(ApkBinaryManifest.referencedResourceIds(bytes) ?: return null)
                }
            }
        }
        return files.takeIf { it.isNotEmpty() }
    }

    /** Fetches and decompresses one named ZIP entry, given the already-fetched Central Directory bytes:
     *  the shared step for every file read once the Central Directory is in hand (manifest,
     *  resources.arsc, the icon's own files). Null on any failure or size-bound violation,
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
     * The highest-density RASTER config of [resourceId] (packageId<<24 | typeId<<16 | entryId, as read
     * off `<application android:icon>`), or null when there's no populated config for it at all, or every
     * populated config is an adaptive-icon XML (`density` == [DENSITY_ANY]) rather than a plain image:
     * that case is [fetchIconArchive]'s.
     */
    private fun resolveRasterIconPath(table: ResourceTable, resourceId: Int): String? =
        table.values(resourceId)
            .filter { it.density != DENSITY_ANY && it.dataType == TYPE_STRING }
            .mapNotNull { value ->
                table.strings.getOrNull(value.data)
                    ?.takeIf { RASTER_EXTENSION_REGEX.containsMatchIn(it) }
                    ?.let { value.density to it }
            }
            .maxByOrNull { it.first }
            ?.second

    /** One config's plain value of a resource: [data] is a global-string-pool index when [dataType] is
     *  [TYPE_STRING] (a file path), another resource's id when it is [TYPE_REFERENCE]. */
    private class ResourceValue(val density: Int, val dataType: Int, val data: Int)

    /** `resources.arsc`, read once: its global string pool and where each package's chunk lies. */
    private class ResourceTable(
        private val arsc: ByteArray,
        val strings: List<String>,
        private val packages: Map<Int, LongRange>,
    ) {

        /** Every config's plain value of [resourceId], walking the `ResTable_type` children of its
         *  package chunk, one per density/locale/etc. config its type ships. */
        fun values(resourceId: Int): List<ResourceValue> {
            val bounds = packages[(resourceId ushr 24) and 0xFF] ?: return emptyList()
            val targetTypeId = (resourceId ushr 16) and 0xFF
            val targetEntryId = resourceId and 0xFFFF
            val pkgHeaderSize = u16(arsc, bounds.first.toInt() + 2)
            var pos = bounds.first + pkgHeaderSize
            val results = mutableListOf<ResourceValue>()
            while (pos + 8 <= bounds.last + 1) {
                val chunkType = u16(arsc, pos.toInt())
                val chunkSize = u32(arsc, pos.toInt() + 4)
                if (chunkSize <= 0) break
                if (chunkType == RES_TABLE_TYPE_TYPE) {
                    val typeId = arsc[(pos + 8).toInt()].toInt() and 0xFF
                    if (typeId == targetTypeId) {
                        readEntryValue(arsc, pos, targetEntryId)?.let(results::add)
                    }
                }
                pos += chunkSize
            }
            return results
        }

        companion object {
            fun parse(arsc: ByteArray): ResourceTable? {
                if (arsc.size < 8 || u16(arsc, 0) != RES_TABLE_TYPE) return null
                val topHeaderSize = u16(arsc, 2)
                val topSize = u32(arsc, 4)
                var pos = topHeaderSize.toLong()
                val end = minOf(topSize, arsc.size.toLong())
                var globalPool: List<String>? = null
                val packages = HashMap<Int, LongRange>()
                while (pos + 8 <= end) {
                    val chunkType = u16(arsc, pos.toInt())
                    val chunkSize = u32(arsc, pos.toInt() + 4)
                    if (chunkSize <= 0 || pos + chunkSize > end) break
                    when (chunkType) {
                        RES_STRING_POOL_TYPE -> if (globalPool == null) globalPool = parseStringPool(arsc, pos.toInt())
                        RES_TABLE_PACKAGE_TYPE ->
                            packages.getOrPut(u32(arsc, pos.toInt() + 8).toInt()) { pos until pos + chunkSize }
                    }
                    pos += chunkSize
                }
                return ResourceTable(arsc, globalPool ?: return null, packages)
            }
        }
    }

    /** One `ResTable_type` chunk's value for [targetEntryId], or null when that entry isn't populated in
     *  this particular config or isn't a plain value (a complex/bag entry). Offsets-array encoding
     *  (dense/sparse/offset16) mirrors [ApkResourceLocales]' [readTypeChunk]-equivalent decoding (see
     *  its doc comment for the byte-format rationale). */
    private fun readEntryValue(arsc: ByteArray, chunkStart: Long, targetEntryId: Int): ResourceValue? {
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
        return ResourceValue(density, valueDataType, u32(arsc, valueStart + 4).toInt())
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
        RangeResult.RangeNotSupported -> null
        is RangeResult.Failed -> throw DownloadFailed()
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

    /** `Res_value.dataType` for a reference to another resource, by id. */
    private const val TYPE_REFERENCE = 0x01

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
