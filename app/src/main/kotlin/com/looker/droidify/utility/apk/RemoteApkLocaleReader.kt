package com.looker.droidify.utility.apk

import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.RangeResult
import com.looker.droidify.network.header.HeadersBuilder
import com.looker.droidify.utility.common.withoutNonLocalePrefix
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

    private fun logD(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

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
                    logD(
                        "$apkUrl: total size mismatch (remote ${tailResult.totalSize}B, " +
                            "expected ${expectedTotalSize}B) — not the same artifact",
                    )
                    return null
                }
                tailResult.bytes
            }
            RangeResult.RangeNotSupported, is RangeResult.Failed ->
                return null.also { logD("$apkUrl: tail fetch failed (range not supported or network error)") }
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
        // - Apps shipping one translation file per locale under an i18n-ish directory somewhere in
        //   `assets/` (see ASSET_LOCALE_FILE_REGEX): Flutter `easy_localization`
        //   (`assets/flutter_assets/assets/<i18n-ish-dir>/<code>.json`, confirmed against a real Obtainium
        //   APK — 29 per-locale files under assets/flutter_assets/assets/translations/, resources.arsc
        //   carrying zero Obtainium-authored strings), Capacitor/Ionic ngx-translate
        //   (`assets/public/assets/i18n/<code>.json`), Cordova (`assets/www/**/<code>.json`), and a
        //   native/C++ app rolling its own (`assets/lang/<code>.ini`, confirmed against a real PPSSPP
        //   release: 45 translations, resources.arsc carrying zero app-authored strings). Some
        //   Flutter i18n approaches instead compile translations straight into the Dart AOT snapshot with
        //   NO per-file signal at all — the official ARB-based `flutter gen-l10n`, and the `slang` package
        //   (confirmed on iyox Wormhole: resources.arsc carried zero app-authored strings and no per-locale
        //   asset file of any kind, despite genuinely having en/de/it/cs/zh) — those degrade safely to a
        //   less-reliable tier rather than a false "English only".
        // Just the file NAMES are needed — already in hand from the same central directory fetched for
        // resources.arsc below, so this costs no extra request. Chromium's own non-locale .pak files
        // (chrome_100_percent.pak, resources.pak, …) don't match the locale-code shape; a directory that
        // merely happens to be *named* something i18n-ish is rejected by what it actually holds (see
        // holdsTranslations).
        val assetLocales = assetLocalesFromEntryNames(ApkZipLocator.findEntryNames(centralDirectoryBytes) { true })
        if (assetLocales.isNotEmpty()) {
            logD("$apkUrl: found ${assetLocales.size} per-locale asset file(s) outside resources.arsc")
        }

        val entry = ApkZipLocator.findEntry(centralDirectoryBytes, ENTRY_NAME)
            ?: return assetLocales.ifEmpty { null }
                .also { logD("$apkUrl: no $ENTRY_NAME entry in central directory") }
        if (entry.uncompressedSize <= 0 || entry.uncompressedSize > MAX_ARSC_BYTES) {
            logD("$apkUrl: $ENTRY_NAME uncompressed size out of bounds (${entry.uncompressedSize}B)")
            return assetLocales.ifEmpty { null }
        }
        if (entry.compressionMethod != COMPRESSION_STORED && entry.compressionMethod != COMPRESSION_DEFLATED) {
            logD("$apkUrl: unsupported compression method ${entry.compressionMethod}")
            return assetLocales.ifEmpty { null }
        }

        // The Local File Header's own name/extra-field lengths can differ slightly from the Central
        // Directory's copy, so the exact data offset can only be known by reading it — 30 bytes covers
        // the header's fixed part; its variable name/extra fields aren't needed, only their lengths.
        val localHeaderBytes = fetchBytes(downloader, apkUrl) {
            inRange(entry.localHeaderOffset, entry.localHeaderOffset + 29)
            headers()
        } ?: return assetLocales.ifEmpty { null }
            .also { logD("$apkUrl: local file header fetch failed") }
        val dataStart = ApkZipLocator.localFileDataOffset(localHeaderBytes, entry.localHeaderOffset)
            ?: return assetLocales.ifEmpty { null }
                .also { logD("$apkUrl: couldn't compute local file data offset") }

        val compressedBytes = fetchBytes(downloader, apkUrl) {
            inRange(dataStart, dataStart + entry.compressedSize - 1)
            headers()
        } ?: return assetLocales.ifEmpty { null }
            .also { logD("$apkUrl: $ENTRY_NAME data fetch failed (${entry.compressedSize}B)") }

        val arscBytes = when (entry.compressionMethod) {
            COMPRESSION_STORED -> compressedBytes
            else -> inflateRaw(compressedBytes, entry.uncompressedSize.toInt())
                ?: return assetLocales.ifEmpty { null }
                    .also { logD("$apkUrl: inflate failed (${compressedBytes.size}B compressed)") }
        }
        if (arscBytes.size.toLong() != entry.uncompressedSize) {
            logD(
                "$apkUrl: decoded $ENTRY_NAME size mismatch (got ${arscBytes.size}B, " +
                    "expected ${entry.uncompressedSize}B)",
            )
            return assetLocales.ifEmpty { null }
        }

        val arscLocales = ApkResourceLocales.localeCodes(arscBytes)
        logD(
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

    /**
     * Matches one file of a per-locale translation set bundled under `assets/`, whatever put it there:
     * Flutter `easy_localization` (`assets/flutter_assets/assets/translations/fr.json`),
     * Capacitor/Ionic ngx-translate (`assets/public/assets/i18n/fr.json`), Cordova
     * (`assets/www/.../i18n/fr.json`), and a native/C++ app rolling its own (`assets/lang/fr_FR.ini`,
     * confirmed on a real PPSSPP release: 45 translations there, and a `resources.arsc` carrying not one
     * app-authored string, so the whole app read as English-only). They all ship one file per locale
     * inside an i18n-ish directory, differing only in the `assets/` sub-path, the separator inside the
     * code, and the file format. The optional wildcard path segment before that directory covers every
     * sub-path (including a Flutter app declaring its translations at project root,
     * `assets/flutter_assets/translations/`) rather than hard-coding one framework's.
     *
     * Deliberately loose about the format, since which one a project picks says nothing about whether
     * these are translations: the directory it put them in, and what its neighbours look like, is what
     * answers that ([holdsTranslations]). `_` is accepted alongside `-` because the Java/POSIX-flavoured
     * `fr_FR` spelling is at least as common as BCP47's `fr-FR` outside Android's own resource system
     * (all 45 of PPSSPP's use it); the captured code is normalised to `-` in
     * [assetLocalesFromEntryNames], so it can never be reported twice under two spellings.
     *
     * Up to two optional subtags (not just one) so a language+script+region code like "zh-Hant-TW" is
     * captured whole instead of just its last segment, plus an optional trailing CLDR/POSIX-style
     * `@variant` suffix (e.g. `en@pirate.json`, confirmed shipped as a genuine, deliberately-translated
     * locale variant by a real app). Without it, that suffix isn't part of a plain extension match at
     * all, so the whole file name fails to match and the variant is silently skipped entirely.
     */
    private val ASSET_LOCALE_FILE_REGEX = Regex(
        """assets/(?:.*/)?(?:i18n|l10n|intl|locales?|translations?|lang(?:uages?)?)/""" +
            """([a-zA-Z]{2,3}(?:[-_][a-zA-Z0-9]{2,4}){0,2}(?:@[a-zA-Z0-9]+)?)""" +
            """\.(?:json|ini|xml|ya?ml|properties|po|arb|strings|lang|txt|qm)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Below this, a directory isn't a translation set. A lone locale-shaped file name is as likely to
     *  be a coincidence as a translation, and an app that really does ship exactly one has nothing to
     *  contribute anyway: that one language is already whatever the app is written in. */
    private const val MIN_LOCALE_FILES = 2

    /**
     * Whether a directory holding [localeFiles] locale-shaped names out of [totalFiles] is really a set
     * of translations, rather than a directory that merely happens to be *called* something i18n-ish.
     *
     * The name alone is not enough, and this is not theoretical: a real Markor release keeps its
     * syntax-highlighting definitions in `assets/highlight/languages/`, where `cpp.json` and
     * `map.properties` are shaped exactly like locale codes. On a 32-APK corpus that one directory was
     * the *only* thing the previous, name-only rule ever matched, so the entire feature's real-world
     * output was a single app wrongly claiming to be translated into "cpp".
     *
     * What separates the two is the company a file keeps. A translation set is overwhelmingly made of
     * translations: PPSSPP's `assets/lang/` is 45 locales and one README. A directory of something else
     * only brushes past the shape here and there: Markor's is 2 of 4. Requiring a strict majority, on
     * top of [MIN_LOCALE_FILES], tells them apart without needing a curated list of language codes to
     * check against, which would fail the real cases anyway, since PPSSPP genuinely ships `cz`, `gr`
     * and `dr`, none of them an ISO 639-1 code, all of them real translations.
     */
    private fun holdsTranslations(localeFiles: Int, totalFiles: Int): Boolean =
        localeFiles >= MIN_LOCALE_FILES && localeFiles * 2 > totalFiles

    /**
     * Locale codes carried by per-locale asset file names among [entryNames] (a Chromium `.pak` bundle,
     * or any per-locale translation set, see [PAK_LOCALE_REGEX]/[ASSET_LOCALE_FILE_REGEX]), not
     * resources.arsc entries. Shared with [InstalledApkLocaleReader], which walks an installed package's
     * own local ZIP entries the same way this walks a remote APK's central-directory-derived ones.
     * Those locales are invisible to [ApkResourceLocales] either way, so both callers need this same
     * detection, not just the download path.
     *
     * [entryNames] must be the APK's *whole* entry list, not a pre-filtered one: [holdsTranslations]
     * judges a candidate directory by everything sitting in it, so the files that didn't match matter
     * as much as the ones that did.
     */
    fun assetLocalesFromEntryNames(entryNames: List<String>): List<String> = (
        entryNames.mapNotNull { PAK_LOCALE_REGEX.matchEntire(it)?.groupValues?.get(1) } +
            translationSetLocales(entryNames)
        ).distinct()

    /** Locale codes from every `assets/` directory that both looks and behaves like a translation set. */
    private fun translationSetLocales(entryNames: List<String>): List<String> = entryNames
        .filterNot { it.endsWith("/") }
        .groupBy { it.substringBeforeLast('/', missingDelimiterValue = "") }
        .values
        .flatMap { siblings ->
            val codes = siblings.mapNotNull { ASSET_LOCALE_FILE_REGEX.matchEntire(it)?.groupValues?.get(1) }
            if (holdsTranslations(codes.size, siblings.size)) codes else emptyList()
        }
        // The prefix pass first: a set named after its string table rather than its locale
        // ("app_fr.json") puts a non-locale word where the language belongs. See
        // [withoutNonLocalePrefix]. Everything else it returns untouched, separator included, which the
        // normalisation below then settles as before.
        .map { withoutNonLocalePrefix(it).replace('_', '-') }

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
