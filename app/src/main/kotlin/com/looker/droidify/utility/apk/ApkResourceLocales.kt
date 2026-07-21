package com.looker.droidify.utility.apk

/**
 * Parses an Android compiled resource table (`resources.arsc`, found inside every APK's ZIP) to find
 * every distinct locale its resources are configured for — the app's REAL supported UI languages,
 * the same ground truth `AssetManager.getLocales()` reads once the app is installed, but readable
 * here from the APK file itself, before installing at all.
 *
 * A locale only counts when it translates a *substantial* share of the app's strings (see
 * [MIN_ENTRY_FRACTION]), not merely one — otherwise a bundled dependency (AndroidX, Material
 * Components, …) that ships its own strings pre-translated into 100+ languages makes every app that
 * uses it look "supported" everywhere too, since aapt2 merges a dependency's resources into the same
 * table with no way to tell them apart by locale alone. Getting this right turned out to need three
 * separate, independently-confirmed pieces, not one:
 * - Compare actually-*populated* entries, not the offsets-array slot count, which is constant
 *   regardless of translation completeness (see [readTypeChunk]).
 * - Compare within one resource type only — and specifically the type that's actually locale-
 *   qualified, not merely whichever type happens to have the most content in its default config (see
 *   [localeCodes]) — since non-string types (`attr`, `style`, `id`, `layout`, `drawable`, …) routinely
 *   outweigh `string` in a dependency-heavy app despite never varying by locale at all.
 * - Exclude a dependency's own pre-translated entries by *name*, not just by count (see
 *   [isBoilerplateKeyName]) — a fixed-size entry-count fraction alone can't tell a small app's real,
 *   substantially-complete translation apart from a small app's boilerplate-only "translation" of
 *   nothing but its bundled libraries' strings, since the latter's absolute size doesn't shrink just
 *   because the app itself is small.
 *
 * A fourth piece — distinguishing a script variant (e.g. Serbian written in Latin script, `sr-Latn`,
 * from the same language's default script) from its base language, rather than silently merging both
 * into one bucket (see [readTypeChunk]) — was added in a second audit round: mostly latent (a merge
 * rarely flips a final answer on its own), but confirmed to matter once the boilerplate list above is
 * thorough enough that it stops accidentally absorbing the merge's noise.
 *
 * All of this was confirmed necessary — not merely theoretical — by auditing this exact logic against
 * 64 real, structurally diverse F-Droid/IzzyOnDroid APKs across two independent rounds: the original
 * (missing all of it) matched ground truth on essentially none of them once coincidental matches were
 * excluded; this version matches on the large majority of both rounds' samples, with the remaining
 * gaps understood and documented at each function below rather than silently wrong.
 *
 * Byte layout reference: AOSP frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
 * (`ResTable_header`, `ResTable_package`, `ResTable_type`, `ResTable_config`, `ResStringPool_header`,
 * `ResTable_entry`). Verified byte-for-byte against an independent reference implementation
 * (pyaxmlparser) across 7 real, structurally diverse F-Droid APKs (0 to 120 locale configs, packed
 * 3-letter language/region codes included) before this port, and again against this exact Kotlin
 * logic re-expressed in Python and re-run on the same APKs, then re-verified against both 30- and
 * 34-APK audit rounds above.
 */
object ApkResourceLocales {

    private const val RES_TABLE_TYPE = 0x0002
    private const val RES_TABLE_PACKAGE_TYPE = 0x0200
    private const val RES_TABLE_TYPE_TYPE = 0x0201
    private const val RES_STRING_POOL_TYPE = 0x0001

    /** An offsets-array slot with this value means "this config doesn't define this particular
     *  resource ID," as opposed to a real byte offset to that entry's data. */
    private const val NO_ENTRY = 0xFFFFFFFFL

    /** [ResTable_type.flags] bits (the byte at chunkStart+9). FLAG_SPARSE: the offsets array is a compact
     *  list of only the POPULATED entries — 4-byte (uint16 idx, uint16 offset/4) pairs — instead of one
     *  dense uint32 slot per resource id. FLAG_OFFSET16: the dense array uses uint16 offsets (real byte
     *  offset = value * 4) with 0xFFFF as the "absent" sentinel, instead of uint32s. Both are opt-in aapt2
     *  optimizations (aapt2 --enable-sparse-encoding / --enable-compact-entries, AGP resource
     *  optimization) that a developer-built release APK served verbatim (IzzyOnDroid, a self-hosted repo)
     *  can carry. Read here so such a table is decoded correctly rather than misparsed as dense uint32 —
     *  which silently defeated the boilerplate-name filter and over-reported languages as reliable. Any
     *  flag bit outside these two is unrecognised, so the chunk is treated as unparseable (null) rather
     *  than guessed at. */
    private const val FLAG_SPARSE = 0x01
    private const val FLAG_OFFSET16 = 0x02

    /** The uint16 "no entry" sentinel used by FLAG_OFFSET16 dense offset arrays (the uint32 [NO_ENTRY]'s
     *  16-bit counterpart). */
    private const val NO_ENTRY_16 = 0xFFFF

    /** [ResStringPool_header.flags] bit meaning the pool's strings are UTF-8 (length-prefixed byte
     *  runs) rather than the default UTF-16 (length-prefixed code-unit runs). */
    private const val UTF8_FLAG = 1 shl 8

    /** Fixed byte offset, from the start of a `ResTable_package` chunk, to its own `keyStrings` field
     *  (itself an offset to that package's key-name string pool): ResChunk_header(8) + id(4) +
     *  name[128 UTF-16 code units](256) + typeStrings(4) + lastPublicType(4) = 276. Fixed regardless
     *  of Android version — only fields *after* this one (a trailing typeIdOffset on some builds) vary
     *  the chunk's overall headerSize. */
    private const val PACKAGE_KEY_STRINGS_OFFSET = 276

    /** A `ResTable_config`'s locale, as language + region + script — script kept distinct from region
     *  rather than folded together (see [readTypeChunk]'s doc comment), so e.g. Serbian's Cyrillic
     *  (default, [script] empty) and Latin (`script` = "Latn") variants are counted, and reported,
     *  separately instead of silently merged into one. */
    private data class Locale(val language: String, val region: String, val script: String)

    /** The default/"any locale" config, matching what a config with all-zero language and region
     *  bytes AND no script/variant extension is treated as (see [readTypeChunk]). */
    private val DEFAULT_LOCALE = Locale("en", "", "")

    /** One resource type's populated-entry counts for one locale config: [total] populated slots (see
     *  [readTypeChunk]), and [nonBoilerplate] of those whose key name didn't match
     *  [isBoilerplateKeyName] — the actual signal [localeCodes] compares against
     *  [MIN_ENTRY_FRACTION]. [total] alone still drives *which resource type* gets compared (see
     *  [localeCodes]), since that choice needs to find the type with real locale variation at all,
     *  independent of how much of any one locale's content turns out to be boilerplate. */
    private data class EntryCounts(val total: Long, val nonBoilerplate: Long)

    /**
     * Every distinct locale code (e.g. "fr", "pt-BR", "sr-Latn") with at least one configured
     * resource, in BCP47 form (see the mapping block below for why, specifically). Always includes
     * "en" when the table has any real content, since the unqualified default `res/values/` config is
     * treated as English (see [readTypeChunk]).
     *
     * The comparison type is the one with the largest *default*-locale populated-entry total among
     * types that actually vary by locale at all (more than just the default config) — falling back to
     * every type when none vary (nothing in the table is translated anywhere, so there's no wrong
     * choice to make; this keeps a genuinely unlocalized app's correct `["en"]` answer unchanged).
     * Restricting to locale-varying types matters: confirmed on a real APK (Xia Express) where `style`
     * (split across 13 non-locale, platform-version-qualified sub-chunks, all mapping to the same
     * default-locale key, summing to 474 entries) beat `string`'s single 107-entry default chunk —
     * `style` has only ever the one locale config, so picking it left nothing to compare a real French
     * translation against, and the answer collapsed to `["en"]` alone despite ~75 genuinely French-
     * translated app strings sitting right there in `string`.
     *
     * Null when [arsc] can't be parsed as a resource table at all (corrupt/truncated bytes) — the
     * caller should treat that as "couldn't determine this," not as an answer. An empty (non-null)
     * list is a genuine, reliable answer in its own right: a valid table with no resources of its own
     * at all. Never throws, since [arsc] comes from an untrusted remote server.
     */
    /** A locale is only counted, for the chosen resource type, when its non-boilerplate populated
     *  entry count (see [EntryCounts], [isBoilerplateKeyName]) reaches this fraction of that same
     *  type's default-locale non-boilerplate count.
     *
     *  Confirmed on real APKs across two audit rounds that this needs to sit comfortably above the
     *  residual noise a necessarily-incomplete boilerplate-name list still lets through — every time a
     *  new dependency's pre-translated strings turned up not yet named (Media3 in round one; Compose
     *  Material3's date/time pickers, BaseRecyclerViewAdapterHelper, uCrop, and several others in round
     *  two), it left a roughly *constant* non-boilerplate count in every non-real locale, ranging from
     *  a couple of percent up to 18% of the default count, until specifically named. 0.15 sits with
     *  real margin above every confirmed boilerplate floor observed across both rounds, at the cost of
     *  also missing some apps' genuine but low-completion community translations (a real, observed,
     *  and accepted trade-off — see [EntryCounts]'s doc comment for a concrete example) — and, per
     *  round two, breaks down in the opposite direction for catalog/directory-style apps whose default-
     *  locale string count is dominated by independently-translatable per-item content rather than UI
     *  chrome (confirmed on a real app: 6 of 13 genuinely, substantially translated languages fell
     *  under 15% purely because the denominator was inflated by hundreds of catalog entries, not
     *  because the translations themselves were incomplete) — a known, accepted gap in the current
     *  single-fraction design rather than a value this constant alone can be tuned to close. */
    private const val MIN_ENTRY_FRACTION = 0.15

    fun localeCodes(arsc: ByteArray): List<String>? = runCatching {
        val byType = collectPopulatedEntryCountsByType(arsc) ?: return@runCatching null
        if (byType.isEmpty()) return@runCatching emptyList()
        val localeVaryingTypes = byType.values.filter { it.size > 1 }
        val localeCounts = (localeVaryingTypes.ifEmpty { byType.values })
            .maxByOrNull { it[DEFAULT_LOCALE]?.total ?: 0L } ?: return@runCatching emptyList()
        val defaultCount = localeCounts[DEFAULT_LOCALE]?.nonBoilerplate?.takeIf { it > 0 }
            ?: return@runCatching emptyList()
        val threshold = defaultCount * MIN_ENTRY_FRACTION
        localeCounts.filterValues { it.nonBoilerplate >= threshold }.keys.map { locale ->
            // BCP47 form ("pt-BR", "sr-Latn"), matching what java.util.Locale.forLanguageTag() (used
            // by the display code downstream) actually understands — NOT the "-r" infix Android uses
            // only for resource *directory names* ("values-pt-rBR"); forLanguageTag() silently drops
            // an "rBR"-shaped subtag as ill-formed, which would quietly lose the region in the
            // display. Script comes before region per BCP47 subtag ordering (language-Script-Region).
            listOfNotNull(
                locale.language,
                locale.script.takeIf { it.isNotEmpty() },
                locale.region.takeIf { it.isNotEmpty() },
            ).joinToString("-")
        }.distinct()
    }.getOrNull()

    /** Every `ResTable_type` chunk's populated-entry counts, grouped first by its resource type ID
     *  (the package's own numbering — never assumed to be any particular value, since it depends on
     *  how many types the package declares and in what order) and then by locale, so [localeCodes] can
     *  compare each locale only against the *same type's* own default-locale counts. */
    private fun collectPopulatedEntryCountsByType(arsc: ByteArray): Map<Int, Map<Locale, EntryCounts>>? {
        val byType = LinkedHashMap<Int, LinkedHashMap<Locale, EntryCounts>>()
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
                walkPackage(arsc, pos, minOf(pos + chunkSize, end), byType)
            }
            pos += chunkSize
        }
        return byType
    }

    /** Walks one `ResTable_package` chunk's children, accumulating every `ResTable_type` (0x0201)
     *  sub-chunk's populated-entry counts into [byType] — one per (resource type, locale, density, …)
     *  config a package ships, summed across every density/etc. variant a (type, locale) pair appears
     *  in. String pools, type-spec chunks, and anything else are skipped.
     *
     *  Parses this package's own key-name string pool once (via its `keyStrings` field, see
     *  [PACKAGE_KEY_STRINGS_OFFSET]) and reuses it for every entry in every type chunk that follows,
     *  rather than re-parsing it per entry — the pool only depends on the package, not the type or
     *  locale. Null (rather than failing outright) when the pool itself can't be parsed; every entry
     *  is then conservatively treated as non-boilerplate (see [readTypeChunk]) so a corrupt/unusual key
     *  pool degrades this package to the type-selection fix alone, instead of losing locale detection
     *  for it entirely. */
    private fun walkPackage(
        arsc: ByteArray,
        pkgStart: Long,
        pkgEnd: Long,
        byType: MutableMap<Int, LinkedHashMap<Locale, EntryCounts>>,
    ) {
        val pkgHeaderSize = u16(arsc, pkgStart.toInt() + 2)
        val keyStringsRelOffset = u32(arsc, pkgStart.toInt() + PACKAGE_KEY_STRINGS_OFFSET)
        val keyPool = parseStringPool(arsc, (pkgStart + keyStringsRelOffset).toInt())
        var pos = pkgStart + pkgHeaderSize
        while (pos + 8 <= pkgEnd) {
            val chunkType = u16(arsc, pos.toInt())
            val chunkSize = u32(arsc, pos.toInt() + 4)
            if (chunkSize <= 0) break
            if (chunkType == RES_TABLE_TYPE_TYPE) {
                readTypeChunk(arsc, pos, keyPool)?.let { (typeId, locale, counts) ->
                    val typeCounts = byType.getOrPut(typeId) { LinkedHashMap() }
                    val existing = typeCounts[locale] ?: EntryCounts(0L, 0L)
                    typeCounts[locale] = EntryCounts(
                        existing.total + counts.total,
                        existing.nonBoilerplate + counts.nonBoilerplate,
                    )
                }
            }
            pos += chunkSize
        }
    }

    /**
     * `ResTable_type`: ResChunk_header(8) + id(1) + flags(1) + reserved(2) + entryCount(4) +
     * entriesStart(4), then its embedded `ResTable_config` — always starting at a fixed offset of 20
     * bytes into the chunk, regardless of Android version (only the config's OWN trailing fields have
     * grown over time, never the fixed header before it). The "any locale" default config (language and
     * region bytes all zero, and no script — see below) is treated as English: Android's near-universal
     * convention is an unqualified `res/values/` as the base/default strings, virtually always written
     * in English, with `values-xx/` only for the *other* languages — so a default config with real
     * content is the app's English UI, not merely "no locale specified." Without this, the base
     * language a translated app was actually *written* in never showed up at all, only the languages
     * layered on top of it.
     *
     * `entryCount` is the SIZE of the offsets array right after the config — how many resource IDs of
     * this type could possibly be defined — not how many actually are for *this* config; it's the same
     * fixed value for every locale of a given type, translated or not (confirmed on a real APK: every
     * one of Farhan's 86 "string"-type configs, from the untranslated-by-the-app-itself ones to the
     * fully-covered default, reported the identical entryCount=620). What distinguishes a real
     * translation is how many of those slots are actually *populated* — each offsets-array entry is
     * either a real byte offset to that resource's data, or the sentinel [NO_ENTRY] when this config
     * doesn't override that particular resource ID.
     *
     * The config's locale-script field (4 ASCII bytes, e.g. "Latn", NUL-padded — present whenever the
     * embedded config is at least 40 bytes long, which every config in a modern build is) distinguishes
     * e.g. Serbian's default (Cyrillic) script from its Latin-script variant. Without reading it, both
     * configs decode to the identical language+region pair ("sr", "") and [walkPackage]'s accumulation
     * silently sums their counts into one bucket, permanently losing which script(s) are actually
     * translated — confirmed at the byte level on real APKs (Noto, ListenBrainz among others) where two
     * physically distinct `sr` configs, one carrying "Latn" at this exact offset and one not, would
     * otherwise merge. `region` is decoded as `""` when only script (not region) qualifies a locale —
     * Android allows `language`+`script` alone, with no region — mirroring the same all-zero-bytes-
     * means-absent convention already used for language/region.
     *
     * For every populated entry, also resolves its name — a `ResTable_entry` (whether a plain entry or
     * the `ResTable_map_entry` a complex/bag resource uses instead, both sharing the same leading
     * size(2)+flags(2)+key(4) layout) has its `key` field, a [keyPool] index, at a fixed offset of 4
     * bytes into the entry's own data — and classifies it via [isBoilerplateKeyName], so [localeCodes]
     * can tell a dependency's pre-translated entries apart from the app's own by more than count alone.
     */
    private fun readTypeChunk(arsc: ByteArray, chunkStart: Long, keyPool: List<String>?): Triple<Int, Locale, EntryCounts>? {
        val typeId = arsc[(chunkStart + 8).toInt()].toInt() and 0xFF
        // The offsets-array encoding (see FLAG_SPARSE/FLAG_OFFSET16) — the byte at chunkStart+9. A flag
        // bit outside the two we can decode means an encoding we don't understand, so bail to "couldn't
        // determine" rather than misparse a dense uint32 array out of a compact one.
        val flags = arsc[(chunkStart + 9).toInt()].toInt() and 0xFF
        if ((flags and (FLAG_SPARSE or FLAG_OFFSET16).inv()) != 0) return null
        val sparse = (flags and FLAG_SPARSE) != 0
        val offset16 = (flags and FLAG_OFFSET16) != 0
        if (sparse && offset16) return null
        val entryCount = u32(arsc, (chunkStart + 12).toInt())
        if (entryCount > arsc.size) return null
        val entriesStart = u32(arsc, (chunkStart + 16).toInt())
        val configStart = (chunkStart + 20).toInt()
        if (configStart + 12 > arsc.size) return null
        val configSize = u32(arsc, configStart)
        if (configSize < 12) return null
        // config: uint32 size(4) + imsi/mcc+mnc(4) + locale = language[2]+region[2](4) ...
        val langB0 = arsc[configStart + 8].toInt() and 0xFF
        val langB1 = arsc[configStart + 9].toInt() and 0xFF
        val regB0 = arsc[configStart + 10].toInt() and 0xFF
        val regB1 = arsc[configStart + 11].toInt() and 0xFF
        val isDefaultLangRegion = langB0 == 0 && langB1 == 0 && regB0 == 0 && regB1 == 0
        // localeScript sits 28 bytes past the locale field (language[2]+region[2] at +8, then
        // screenType(4)+input(4)+screenSize(4)+version(4)+screenConfig(4)+screenSizeDp(4) = +28 more),
        // i.e. at a fixed offset of 36 from configStart — only present when this config's own trailing
        // fields extend that far.
        val scriptStart = configStart + 36
        val script = if (configSize >= 40 && scriptStart + 4 <= arsc.size) {
            decodeAsciiField(arsc, scriptStart, 4)
        } else {
            ""
        }
        val isDefaultConfig = isDefaultLangRegion && script.isEmpty()
        val locale = if (isDefaultConfig) {
            DEFAULT_LOCALE
        } else {
            Locale(
                language = if (isDefaultLangRegion) "en" else decodeLanguageOrRegion(langB0, langB1, 'a'.code),
                region = if (isDefaultLangRegion) "" else decodeLanguageOrRegion(regB0, regB1, '0'.code),
                script = script,
            )
        }
        // The offsets array starts right after the embedded ResTable_config, whose own leading "size"
        // field is how long it actually is. Its slot width depends on the encoding: a dense uint32 slot
        // per potential resource id (default, and FLAG_OFFSET16's uint16 variant), or a compact 4-byte
        // (idx, offset/4) pair per POPULATED entry only under FLAG_SPARSE.
        val offsetsStart = configStart + configSize.toInt()
        val entryCountInt = entryCount.toInt()
        val slotWidth = if (offset16) 2 else 4
        if (offsetsStart + entryCountInt * slotWidth > arsc.size) return null
        var total = 0L
        var nonBoilerplate = 0L
        for (i in 0 until entryCountInt) {
            // The byte offset (from entriesStart) to this populated entry's data, or `continue` for a
            // dense slot that isn't populated. Sparse arrays list only populated entries, so every slot
            // counts; both compact forms store the offset pre-divided by 4.
            val entryOffset: Long = when {
                offset16 -> {
                    val raw = u16(arsc, offsetsStart + i * 2)
                    if (raw == NO_ENTRY_16) continue
                    raw.toLong() * 4
                }
                sparse -> u16(arsc, offsetsStart + i * 4 + 2).toLong() * 4
                else -> {
                    val raw = u32(arsc, offsetsStart + i * 4)
                    if (raw == NO_ENTRY) continue
                    raw
                }
            }
            total++
            val entryDataStart = chunkStart + entriesStart + entryOffset
            val name = keyPool?.let { pool ->
                if (entryDataStart + 8 > arsc.size) return@let null
                val keyIndex = u32(arsc, (entryDataStart + 4).toInt()).toInt()
                pool.getOrNull(keyIndex)
            }
            if (name == null || !isBoilerplateKeyName(name)) nonBoilerplate++
        }
        return Triple(typeId, locale, EntryCounts(total, nonBoilerplate))
    }

    /** A dependency's own pre-translated resource-key names — AndroidX AppCompat, Material Components
     *  2/3, Compose Material3 (including its date/time pickers and bottom sheets), Preference, Media3,
     *  Biometric, MediaRouter, Google Play Services, androidx.browser, Leanback (the Android-TV UI
     *  library), and a handful of others — seen shipping into an app's `resources.arsc` regardless of
     *  whether the app itself translates anything, confirmed against real string *values* (not just
     *  names) across two 30+-app audit rounds: every one of these resolved to generic framework text
     *  (button labels, a11y descriptions, date/time-picker strings, media transport controls,
     *  biometric-prompt copy, …), never anything referencing an app's own actual feature vocabulary.
     *  Necessarily incomplete — new dependency versions, or dependencies neither audit round happened to
     *  sample, can add more — see [MIN_ENTRY_FRACTION]'s doc comment for how the threshold stays safe
     *  against a name this list doesn't yet know about. */
    private val BOILERPLATE_KEY_PREFIXES = listOf(
        "abc_", "mtrl_", "m3_", "m3c_", "mc2_", "material_", "bottomsheet_", "character_counter_",
        "call_notification_", "searchview_", "side_sheet_", "nav_app_bar_", "path_password_",
        "exposed_dropdown_", "v7_preference_", "fingerprint_", "zxing_", "exo_", "settingslib_",
        "tooltip_", "state_", "search_menu_", "status_bar_", "summary_collapsed_", "password_toggle_",
        "fab_transformation_", "hide_bottom_view_", "androidx_compose_", "search_bar_", "glance_",
        "media3_", "bottom_sheet_", "date_picker_", "date_input_", "date_range_picker_",
        "date_range_input_", "time_picker_", "twofortyfouram_", "brvah_",
        "common_google_play_services_", "fallback_menu_item_", "ucrop_", "mr_chooser_",
        "mr_controller_", "mr_route_name_",
        // androidx.leanback: ~39 pre-translated strings shipped into ~70 locales by the standard
        // Android-TV UI library — the exact over-report vector for a small TV app on this TV-focused
        // fork (a leanback-only locale carries only lb_/orb_ keys, which clear the 0.15 floor whenever
        // the app's own English string count is under ~220). Confirmed: every leanback string is
        // lb_-prefixed except the single orb_-prefixed search-orb one.
        "lb_", "orb_",
    )

    /** As [BOILERPLATE_KEY_PREFIXES], but names too short or generic-looking to safely prefix-match
     *  (an app could plausibly have its own "state_empty" or "error_message_custom_thing") — each
     *  still confirmed, by real string content, to be a specific dependency's own boilerplate rather
     *  than an app's. The `error_message_*` set is `androidx.media3`'s complete, fixed error-code
     *  vocabulary specifically (session/notification error strings), not a general "error_message_"
     *  prefix, for exactly that reason; the same applies to the `androidx.biometric`,
     *  `androidx.mediarouter`, and Play-Services entries below (each a small, complete, verified set
     *  from that one dependency, not a prefix guess). */
    private val BOILERPLATE_KEY_EXACT = setOf(
        "status_bar_notification_info_overflow", "preference_copied", "androidx_startup",
        "summary_collapsed_preference_list", "switch_role", "expand_button_title",
        "icon_content_description", "item_view_role_description", "copy_toast_msg",
        "app_component_factory", "delete_repeated_notification", "not_selected", "in_progress",
        "range_end", "range_start", "selected", "tab", "template_percent", "bottom_sheet_behavior",
        "default_error_message", "default_popup_window_title", "action_menu_overflow_description",
        "snackbar_pane_title", "navigation_menu", "dropdown_menu", "indeterminate",
        "open_search_field", "close_sheet", "close_search", "close_drawer",
        "clear_text_end_icon_content_description", "error_a11y_label", "error_icon_content_description",
        "copy", "not_set", "autofill", "default_notification_channel_name",
        "error_message_authentication_expired", "error_message_bad_value",
        "error_message_concurrent_stream_limit", "error_message_content_already_playing",
        "error_message_disconnected", "error_message_end_of_playlist", "error_message_fallback",
        "error_message_info_cancelled", "error_message_invalid_state", "error_message_io",
        "error_message_not_available_in_region", "error_message_not_supported",
        "error_message_parental_control_restricted", "error_message_permission_denied",
        "error_message_premium_account_required", "error_message_setup_required",
        "error_message_skip_limit_reached",
        "appbar_scrolling_view_behavior", "searchbar_scrolling_view_behavior",
        "collapsed", "expanded", "dialog", "off", "on", "snackbar_dismiss", "suggestions_available",
        "default_error_msg",
        "biometric_prompt_message", "biometric_or_screen_lock_prompt_message", "face_prompt_message",
        "face_or_screen_lock_prompt_message", "screen_lock_prompt_message", "use_biometric_label",
        "use_biometric_or_screen_lock_label", "use_face_label", "use_face_or_screen_lock_label",
        "use_fingerprint_label", "use_fingerprint_or_screen_lock_label",
        "confirm_device_credential_password", "generic_error_no_device_credential",
        "generic_error_no_keyguard", "generic_error_user_canceled",
        "common_open_on_phone", "nav_rail_collapsed_a11y_label", "nav_rail_expanded_a11y_label",
        "mr_dialog_default_group_name", "mr_user_route_category_name",
        "ic_media_route_learn_more_accessibility",
    )

    private fun isBoilerplateKeyName(name: String): Boolean =
        name in BOILERPLATE_KEY_EXACT || BOILERPLATE_KEY_PREFIXES.any { name.startsWith(it) }

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

    /** Decodes a fixed-[length]-byte ASCII field (a `ResTable_config` script or variant tag) starting
     *  at [pos] — NUL-padded, so trailing NUL bytes (and everything after the first one) are trimmed
     *  off; an all-NUL field decodes as an empty string, meaning "absent," consistent with how
     *  [decodeLanguageOrRegion] treats all-zero bytes. */
    private fun decodeAsciiField(arsc: ByteArray, pos: Int, length: Int): String {
        var end = pos
        val limit = pos + length
        while (end < limit && arsc[end] != 0.toByte()) end++
        return String(arsc, pos, end - pos, Charsets.US_ASCII)
    }

    /** Parses a `ResStringPool` chunk (used here for a package's `keyStrings` pool specifically, but
     *  the format is the same one `resources.arsc`'s own top-level string pools use) starting at
     *  [poolStart], returning every string in it, indexed the same way a `ResStringPool_ref` (a plain
     *  index into this list, as `ResTable_entry.key` is) expects. Null if [poolStart] doesn't hold a
     *  valid string-pool chunk header. */
    private fun parseStringPool(arsc: ByteArray, poolStart: Int): List<String>? {
        if (poolStart < 0 || poolStart + 8 > arsc.size) return null
        if (u16(arsc, poolStart) != RES_STRING_POOL_TYPE) return null
        val headerSize = u16(arsc, poolStart + 2)
        if (poolStart + headerSize + 8 > arsc.size) return null
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

    /** Reads one string from a `ResStringPool`'s string-data blob ([dataStart], i.e. [parseStringPool]'s
     *  pool start plus its own `stringsStart` field) at [entryByteOffset] (this string's own entry in
     *  the pool's offsets array). A UTF-8 entry is prefixed by two variable-width lengths (a legacy
     *  UTF-16 character count, skipped — only needed by callers wanting `char[]`-style random access,
     *  not here — then the real UTF-8 byte count); a UTF-16 entry by one variable-width UTF-16 code-unit
     *  count (see [decodeLength8]/[decodeLength16]). Null on any bounds problem rather than throwing,
     *  since [arsc] is untrusted remote data. */
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

    /** Decodes one variable-width length prefix from a UTF-8-encoded `ResStringPool` entry: 1 byte
     *  normally, or 2 when the first byte's top bit is set (the low 7 bits of the first byte combine
     *  with all 8 bits of the second as a 15-bit length) — mirrors AOSP's ResStringPool::decodeLength
     *  (the 8-bit-unit overload). Returns the decoded length and the position right after it. */
    private fun decodeLength8(arsc: ByteArray, pos: Int): Pair<Int, Int> {
        val b0 = arsc[pos].toInt() and 0xFF
        return if (b0 and 0x80 != 0) {
            val b1 = arsc[pos + 1].toInt() and 0xFF
            (((b0 and 0x7F) shl 8) or b1) to (pos + 2)
        } else {
            b0 to (pos + 1)
        }
    }

    /** As [decodeLength8], but for UTF-16-encoded pool entries, where each length unit is a 2-byte
     *  little-endian uint16 instead of a single byte, and the top bit of the FIRST 16-bit unit is the
     *  "2 units follow" marker instead of the top bit of a single byte. */
    private fun decodeLength16(arsc: ByteArray, pos: Int): Pair<Int, Int> {
        val u0 = u16(arsc, pos)
        return if (u0 and 0x8000 != 0) {
            val u1 = u16(arsc, pos + 2)
            (((u0 and 0x7FFF) shl 16) or u1) to (pos + 4)
        } else {
            u0 to (pos + 2)
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        u16(b, off).toLong() or (u16(b, off + 2).toLong() shl 16)
}
