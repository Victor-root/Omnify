package com.looker.droidify.utility.apk

import android.util.Log
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.RangeResult
import com.looker.droidify.network.header.HeadersBuilder
import java.util.zip.Inflater

/**
 * Fetches just `resources.arsc` out of a remote, not-yet-downloaded APK — via HTTP range requests
 * against its ZIP End-Of-Central-Directory, Central Directory, and that one entry — and reads its
 * real supported locales ([ApkResourceLocales]), instead of downloading the whole APK (tens to
 * hundreds of MB for a modern app) just to answer "which languages is this translated into?".
 *
 * Verified end-to-end (this exact request sequence, replayed against real HTTP range requests) on 7
 * real, structurally diverse F-Droid APKs across two different host setups (f-droid.org's nginx,
 * IzzyOnDroid's Apache), matching a full-download ground truth every time.
 */
object RemoteApkLocaleReader {

    private const val TAG = "RemoteApkLocaleReader"
    private const val ENTRY_NAME = "resources.arsc"

    /** Generous but bounded: real resources.arsc files are typically well under 5MB even for very
     *  heavily localized apps (F-Droid's own client, translated into 100+ languages, is ~4.7MB) — but a
     *  browser embedding Chromium's full localization data straight into the standard Android resource
     *  table, rather than as separate per-locale asset files, is a real, confirmed exception: Brave's own
     *  "universal" (non-split) release build carries a genuine, valid ~36MB resources.arsc across its
     *  ~85 supported languages (confirmed via a real device's Logcat: 38056328B, previously rejected
     *  outright by a 20MB cap sized only against F-Droid-client-scale apps, silently losing every one of
     *  those languages). Comfortable headroom above that real, confirmed value — this still guards
     *  against reading an unbounded amount of data for a pathological or adversarial file, just no
     *  longer at a size a legitimate heavily-localized app can actually exceed. */
    private const val MAX_ARSC_BYTES = 64L * 1024 * 1024

    /** EOCD is 22 bytes plus an optional (near-always empty, for an APK) comment of up to 65535 bytes
     *  — this tail comfortably covers both with margin to spare. */
    private const val TAIL_FETCH_BYTES = 128L * 1024

    /**
     * The APK's real supported locale codes: null when it couldn't be determined at all (the server
     * doesn't support range requests, a network error, a malformed ZIP/resource table, or
     * `resources.arsc` missing/oversized/compressed with an unsupported method) — the caller should
     * fall back to a less reliable signal in that case. A non-null (possibly empty) list is a genuine,
     * reliable answer: empty means a valid table with no locale-specific resources at all (an
     * unlocalized/English-only app). Never throws.
     *
     * [expectedTotalSize] (when non-null) is compared against the remote file's real total size, read
     * from the first range response's own Content-Range header — a mismatch aborts with null. Its
     * purpose is identity, not integrity: when [apkUrl] is a derived mirror of the file rather than the
     * repo's own address (see [RangeCapableMirrors], used when the origin host ignores Range requests),
     * an exact size match against what the repo index declares confirms the mirror holds the same
     * artifact and not, say, a newer build under the same name. Mirrors [RemoteApkManifestReader]'s own
     * identity check.
     */
    suspend fun fetchLocales(
        downloader: Downloader,
        apkUrl: String,
        expectedTotalSize: Long? = null,
        headers: HeadersBuilder.() -> Unit = {},
    ): List<String>? {
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

        // Some frameworks don't localise through Android's resource-table mechanism at all — their UI
        // strings live entirely in their own per-language asset files instead, invisible to
        // resources.arsc no matter how thoroughly it's parsed:
        // - A Chromium-based app's PER-DEVICE-LANGUAGE-SPLIT install (Android App Bundle config splits,
        //   e.g. split_config.fr.apk): `assets/locales/<code>.pak`. NOT what a Chromium app's own
        //   "universal"/non-split release build does, though: confirmed on a real Brave release APK
        //   (Bravearm64Universal, real device Logcat) that it carries no assets/locales/*.pak at all —
        //   its ~85 languages are compiled straight into a single, large resources.arsc instead (see
        //   MAX_ARSC_BYTES), exactly like an ordinary Android app's res/values-xx/ folders would produce.
        //   This detector still matters for a genuinely split-installed app's own base/config APKs
        //   (see InstalledApkLocaleReader), where the real per-locale .pak files do live at this path.
        // - Cross-platform apps shipping one translation JSON per locale under an i18n-ish directory
        //   somewhere in `assets/` (see ASSET_JSON_LOCALE_REGEX): Flutter `easy_localization`
        //   (`assets/flutter_assets/assets/<i18n-ish-dir>/<code>.json`, confirmed against a real Obtainium
        //   APK — 29 per-locale files under assets/flutter_assets/assets/translations/, resources.arsc
        //   carrying zero Obtainium-authored strings), Capacitor/Ionic ngx-translate
        //   (`assets/public/assets/i18n/<code>.json`) and Cordova (`assets/www/**/<code>.json`). Some
        //   Flutter i18n approaches instead compile translations straight into the Dart AOT snapshot with
        //   NO per-file signal at all — the official ARB-based `flutter gen-l10n`, and the `slang` package
        //   (confirmed on iyox Wormhole: resources.arsc carried zero app-authored strings and no per-locale
        //   asset file of any kind, despite genuinely having en/de/it/cs/zh) — those degrade safely to a
        //   less-reliable tier rather than a false "English only".
        // Just the file NAMES are needed — already in hand from the same central directory fetched for
        // resources.arsc below, so this costs no extra request. Each pattern's own non-locale files
        // (Chromium's chrome_100_percent.pak/chrome_200_percent.pak/resources.pak; a Flutter app's other,
        // non-i18n JSON assets) don't match the locale-code shape and are naturally excluded.
        val assetLocales = assetLocalesFromEntryNames(ApkZipLocator.findEntryNames(centralDirectoryBytes) { true })
        if (assetLocales.isNotEmpty()) {
            Log.d(TAG, "$apkUrl: found ${assetLocales.size} per-locale asset file(s) outside resources.arsc")
        }

        val entry = ApkZipLocator.findEntry(centralDirectoryBytes, ENTRY_NAME)
            ?: return assetLocales.ifEmpty { null }
                .also { Log.d(TAG, "$apkUrl: no $ENTRY_NAME entry in central directory") }
        if (entry.uncompressedSize <= 0 || entry.uncompressedSize > MAX_ARSC_BYTES) {
            Log.d(TAG, "$apkUrl: $ENTRY_NAME uncompressed size out of bounds (${entry.uncompressedSize}B)")
            return assetLocales.ifEmpty { null }
        }
        if (entry.compressionMethod != COMPRESSION_STORED && entry.compressionMethod != COMPRESSION_DEFLATED) {
            Log.d(TAG, "$apkUrl: unsupported compression method ${entry.compressionMethod}")
            return assetLocales.ifEmpty { null }
        }

        // The Local File Header's own name/extra-field lengths can differ slightly from the Central
        // Directory's copy, so the exact data offset can only be known by reading it — 30 bytes covers
        // the header's fixed part; its variable name/extra fields aren't needed, only their lengths.
        val localHeaderBytes = fetchBytes(downloader, apkUrl) {
            inRange(entry.localHeaderOffset, entry.localHeaderOffset + 29)
            headers()
        } ?: return assetLocales.ifEmpty { null }
            .also { Log.d(TAG, "$apkUrl: local file header fetch failed") }
        val dataStart = ApkZipLocator.localFileDataOffset(localHeaderBytes, entry.localHeaderOffset)
            ?: return assetLocales.ifEmpty { null }
                .also { Log.d(TAG, "$apkUrl: couldn't compute local file data offset") }

        val compressedBytes = fetchBytes(downloader, apkUrl) {
            inRange(dataStart, dataStart + entry.compressedSize - 1)
            headers()
        } ?: return assetLocales.ifEmpty { null }
            .also { Log.d(TAG, "$apkUrl: $ENTRY_NAME data fetch failed (${entry.compressedSize}B)") }

        val arscBytes = when (entry.compressionMethod) {
            COMPRESSION_STORED -> compressedBytes
            else -> inflateRaw(compressedBytes, entry.uncompressedSize.toInt())
                ?: return assetLocales.ifEmpty { null }
                    .also { Log.d(TAG, "$apkUrl: inflate failed (${compressedBytes.size}B compressed)") }
        }
        if (arscBytes.size.toLong() != entry.uncompressedSize) {
            Log.d(
                TAG,
                "$apkUrl: decoded $ENTRY_NAME size mismatch (got ${arscBytes.size}B, " +
                    "expected ${entry.uncompressedSize}B)",
            )
            return assetLocales.ifEmpty { null }
        }

        val arscLocales = ApkResourceLocales.localeCodes(arscBytes)
        Log.d(
            TAG,
            "$apkUrl: parsed ${arscBytes.size}B $ENTRY_NAME -> ${arscLocales?.size ?: "unparsable"} " +
                "locale(s), plus ${assetLocales.size} from per-locale asset files",
        )
        // Union, not "prefer one over the other": an app can genuinely mix both mechanisms (some
        // Chromium-derived apps keep a few Android-native strings — e.g. notification channel names —
        // in res/values-xx/ on top of their .pak-bundled UI text), and either list alone can be null/
        // empty while the other still has a real answer.
        return ((arscLocales.orEmpty()) + assetLocales).distinct().sorted().ifEmpty { arscLocales }
    }

    /** Matches a Chromium `.pak` locale-resource-bundle file name under `assets/locales/` (e.g.
     *  `assets/locales/fr.pak`, `assets/locales/zh-CN.pak`, `assets/locales/es-419.pak`), capturing the
     *  locale code. Chromium's own non-locale .pak files (chrome_100_percent.pak, resources.pak, …)
     *  don't match this shape — they contain underscores/digits a locale code never does — so they're
     *  naturally excluded without an explicit denylist. */
    private val PAK_LOCALE_REGEX = Regex("""assets/locales/([a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,4})?)\.pak$""")

    /** Matches a per-locale translation JSON file bundled under `assets/`, whatever the cross-platform
     *  framework that put it there: Flutter `easy_localization` (`assets/flutter_assets/assets/
     *  translations/fr.json`), Capacitor/Ionic ngx-translate (`assets/public/assets/i18n/fr.json`),
     *  Cordova (`assets/www/.../i18n/fr.json`) — all ship one JSON per locale inside an i18n-ish directory
     *  (i18n/l10n/intl/lang/locales/translations/…), just under a different `assets/` sub-path per
     *  framework. The optional wildcard path segment before the i18n-ish directory matches all of them
     *  (and a Flutter app that
     *  declares its translations at project root, `assets/flutter_assets/translations/`, with no nested
     *  `assets/`) rather than hard-coding one framework's prefix. Up to two optional subtags (not just
     *  one) so a language+script+region code like "zh-Hant-TW" is captured whole instead of just its last
     *  segment, plus an optional trailing CLDR/POSIX-style `@variant` suffix (e.g. `en@pirate.json`,
     *  confirmed shipped as a genuine, deliberately-translated locale variant by a real app) — without
     *  it, that suffix isn't part of a plain `.json` extension match at all, so the whole file name fails
     *  to match and the variant is silently skipped entirely. */
    private val ASSET_JSON_LOCALE_REGEX = Regex(
        """assets/(?:.*/)?(?:i18n|l10n|intl|locales?|translations?|lang(?:uages?)?)/""" +
            """([a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,4}){0,2}(?:@[a-zA-Z0-9]+)?)\.json$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Locale codes carried by per-locale asset file names among [entryNames] (a Chromium `.pak` bundle,
     * or a cross-platform framework's per-locale translation JSON — see [PAK_LOCALE_REGEX]/
     * [ASSET_JSON_LOCALE_REGEX]), not resources.arsc entries. Shared with [InstalledApkLocaleReader],
     * which walks an installed package's own local ZIP entries the same way this walks a remote APK's
     * central-directory-derived ones — those frameworks' locales are invisible to [ApkResourceLocales]
     * either way, so both callers need this same detection, not just the download path.
     */
    fun assetLocalesFromEntryNames(entryNames: List<String>): List<String> = (
        entryNames.filter { PAK_LOCALE_REGEX.matches(it) }
            .mapNotNull { PAK_LOCALE_REGEX.find(it)?.groupValues?.get(1) } +
            entryNames.filter { ASSET_JSON_LOCALE_REGEX.matches(it) }
                .mapNotNull { ASSET_JSON_LOCALE_REGEX.find(it)?.groupValues?.get(1) }
        ).distinct()

    private suspend fun fetchBytes(
        downloader: Downloader,
        url: String,
        headers: HeadersBuilder.() -> Unit,
    ): ByteArray? = when (val result = downloader.getRange(url, headers)) {
        is RangeResult.Success -> result.bytes
        RangeResult.RangeNotSupported, is RangeResult.Failed -> null
    }

    /** Inflates raw (headerless) DEFLATE data — the "no wrap" mode matches the ZIP spec's compressed
     *  data format directly, as opposed to the zlib- or gzip-wrapped variants `Inflater` also supports. */
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

    private const val COMPRESSION_STORED = 0
    private const val COMPRESSION_DEFLATED = 8
}
