package com.looker.droidify.utility.apk

/**
 * Parses an Android compiled resource table (`resources.arsc`, found inside every APK's ZIP) to find
 * every distinct locale its resources are configured for — the app's REAL supported UI languages,
 * the same ground truth `AssetManager.getLocales()` reads once the app is installed, but readable
 * here from the APK file itself, before installing at all.
 *
 * Byte layout reference: AOSP frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
 * (`ResTable_header`, `ResTable_package`, `ResTable_type`, `ResTable_config`). Verified byte-for-byte
 * against an independent reference implementation (pyaxmlparser) across 7 real, structurally diverse
 * F-Droid APKs (0 to 120 locale configs, packed 3-letter language/region codes included) before this
 * port, and again against this exact Kotlin logic re-expressed in Python and re-run on the same APKs.
 */
object ApkResourceLocales {

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201

    /**
     * Every distinct locale code (e.g. "fr", "pt-rBR", "zh-rTW") with at least one configured
     * resource, in the same "language" / "language-rREGION" qualifier convention Android's own
     * resource system uses (matching what [com.looker.droidify.compose.appDetail.AppDetailViewModel]
     * already gets from `AssetManager.getLocales()` for an installed app, so both sources feed the
     * same downstream display code unchanged).
     *
     * Null when [arsc] can't be parsed as a resource table at all (corrupt/truncated bytes) — the
     * caller should treat that as "couldn't determine this," not as an answer. An empty (non-null)
     * list is a genuine, reliable answer in its own right: a valid table with no locale-specific
     * resources at all, i.e. an unlocalized/English-only app. Never throws, since [arsc] comes from
     * an untrusted remote server.
     */
    fun localeCodes(arsc: ByteArray): List<String>? = runCatching {
        collectLocalePairs(arsc)?.map { (language, region) ->
            if (region.isNotEmpty()) "$language-r$region" else language
        }?.distinct()
    }.getOrNull()

    private fun collectLocalePairs(arsc: ByteArray): List<Pair<String, String>>? {
        val locales = LinkedHashSet<Pair<String, String>>()
        if (arsc.size < 8) return null
        // Top-level chunk: ResTable_header (ResChunk_header + uint32 packageCount). Its headerSize
        // already accounts for packageCount, so the first child chunk starts right after it.
        val topType = u16(arsc, 0)
        if (topType != RES_TABLE_TYPE) return null
        val topHeaderSize = u16(arsc, 2)
        val topSize = u32(arsc, 4)
        var pos = topHeaderSize.toLong()
        val end = minOf(topSize, arsc.size.toLong())
        while (pos + 8 <= end) {
            val chunkType = u16(arsc, pos.toInt())
            val chunkSize = u32(arsc, pos.toInt() + 4)
            if (chunkSize <= 0) break
            if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                walkPackage(arsc, pos, minOf(pos + chunkSize, end), locales)
            }
            pos += chunkSize
        }
        return locales.toList()
    }

    /** Walks one `ResTable_package` chunk's children, collecting the locale of every
     *  `ResTable_type` (0x0201) sub-chunk — one per (resource type, locale, density, …) config a
     *  package ships. String pools, type-spec chunks, and anything else are skipped; only a
     *  `ResTable_type`'s embedded `ResTable_config` is read. */
    private fun walkPackage(arsc: ByteArray, pkgStart: Long, pkgEnd: Long, locales: MutableSet<Pair<String, String>>) {
        val pkgHeaderSize = u16(arsc, pkgStart.toInt() + 2)
        var pos = pkgStart + pkgHeaderSize
        while (pos + 8 <= pkgEnd) {
            val chunkType = u16(arsc, pos.toInt())
            val chunkSize = u32(arsc, pos.toInt() + 4)
            if (chunkSize <= 0) break
            if (chunkType == RES_TABLE_TYPE_TYPE) {
                readTypeChunkLocale(arsc, pos)?.let { locales.add(it) }
            }
            pos += chunkSize
        }
    }

    /**
     * `ResTable_type`: ResChunk_header(8) + id(1) + flags(1) + reserved(2) + entryCount(4) +
     * entriesStart(4), then its embedded `ResTable_config` — always starting at a fixed offset of 20
     * bytes into the chunk, regardless of Android version (only the config's OWN trailing fields have
     * grown over time, never the fixed header before it). Returns null for the "any locale" default
     * config (language and region bytes all zero) — that isn't a real, distinct locale.
     */
    private fun readTypeChunkLocale(arsc: ByteArray, chunkStart: Long): Pair<String, String>? {
        val configStart = (chunkStart + 20).toInt()
        if (configStart + 12 > arsc.size) return null
        val configSize = u32(arsc, configStart)
        if (configSize < 12) return null
        // config: uint32 size(4) + imsi/mcc+mnc(4) + locale = language[2]+region[2](4) ...
        val langB0 = arsc[configStart + 8].toInt() and 0xFF
        val langB1 = arsc[configStart + 9].toInt() and 0xFF
        val regB0 = arsc[configStart + 10].toInt() and 0xFF
        val regB1 = arsc[configStart + 11].toInt() and 0xFF
        if (langB0 == 0 && langB1 == 0 && regB0 == 0 && regB1 == 0) return null
        val language = decodeLanguageOrRegion(langB0, langB1, 'a'.code)
        val region = decodeLanguageOrRegion(regB0, regB1, '0'.code)
        return language to region
    }

    /**
     * Decodes one `ResTable_config` language-or-region field (2 bytes). The simple case is two plain
     * ASCII characters (a 2-letter ISO code, or a 2-letter/2-digit region). When the high bit of the
     * first byte is set, the two bytes instead pack a base-25 (region) or base-26 (language) encoding
     * of a 3-character code (used for languages/regions with no 2-letter ISO form, e.g. "fil", "ber",
     * a UN M.49 3-digit area code) — this bit-packing is exactly mirrored from the AOSP C++
     * implementation (`ResTable_config::unpackLanguageOrRegion`).
     */
    private fun decodeLanguageOrRegion(b0: Int, b1: Int, base: Int): String {
        if ((b0 and 0x80) != 0) {
            val first = b1 and 0x1f
            val second = ((b1 and 0xe0) shr 5) or ((b0 and 0x03) shl 3)
            val third = (b0 and 0x7c) shr 2
            return "" + (first + base).toChar() + (second + base).toChar() + (third + base).toChar()
        }
        val sb = StringBuilder(2)
        if (b0 != 0) sb.append(b0.toChar())
        if (b1 != 0) sb.append(b1.toChar())
        return sb.toString()
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)
}
