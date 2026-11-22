package com.looker.droidify.external

import android.os.Build

/**
 * One APK of a release, as the version list's file chooser shows it. A release carries file names and
 * nothing else, so what tells its APKs apart is read out of those names: the processor each is built
 * for, and the words only some of them carry (Breezy Weather's "standard" and "freenet", a project's
 * "fdroid" and "github" builds).
 */
data class ApkVariant(
    val asset: ReleaseAsset,
    /** The processor this file is built for, as Android names it ("arm64-v8a"); null for one that runs
     *  on any. */
    val abi: String?,
    /** The words of its name the release's other APKs don't all share, in the order the name gives
     *  them. Versions and processors are not among them. */
    val words: List<String>,
    /** Whether this device can run it. */
    val compatible: Boolean,
)

/** Every APK of [assets] as an [ApkVariant], in the release's own order. */
fun apkVariants(
    assets: List<ReleaseAsset>,
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
): List<ApkVariant> {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    val parsed = apks.map { it to parseApkName(it.name) }
    val shared = parsed.map { (_, name) -> name.words.toSet() }
        .reduceOrNull { common, words -> common intersect words }
        .orEmpty()
    return parsed.map { (asset, name) ->
        ApkVariant(
            asset = asset,
            abi = name.abi,
            words = name.words.filter { it !in shared }.distinct(),
            compatible = name.abi == null || name.abi in deviceAbis,
        )
    }
}

/**
 * The APK filter that keeps a source on [chosen] in this release and the ones after it, or null when
 * [chosen] is the file Omnify picks with no filter at all, which is what clearing the filter brings
 * back. Built from what sets [chosen] apart from its siblings (its processor, its own words) rather
 * than from its whole name, which carries the version and would match no later release; the plain
 * word alone when it is enough. Its full name, as a last resort, when nothing else picks it out.
 */
fun variantFilter(
    assets: List<ReleaseAsset>,
    chosen: ReleaseAsset,
    releaseTag: String? = null,
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
): String? {
    if (selectApkAsset(assets, deviceAbis, filter = null, releaseTag = releaseTag) == chosen) return null
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    val picks = { filter: String ->
        val regex = Regex(filter, RegexOption.IGNORE_CASE)
        apks.filter { regex.containsMatchIn(it.name) } == listOf(chosen) &&
            selectApkAsset(assets, deviceAbis, filter, releaseTag) == chosen
    }
    val variants = apkVariants(assets, deviceAbis)
    val own = variants.first { it.asset == chosen }
    val others = variants - own
    val required = listOfNotNull(own.abi?.let { abiTokenIn(chosen.name) }) + own.words
    required.firstOrNull { picks(it.escapedForRegex()) }?.let { return it.escapedForRegex() }
    val excluded = (others.mapNotNull { variant -> variant.abi?.let { abiTokenIn(variant.asset.name) } } +
        others.flatMap { it.words })
        .filter { it !in required }
        .distinct()
    // A file with nothing of its own (Mihon's plain universal build, beside its "foss" one) is
    // picked out by what it lacks alone. Plain words first, shown as they are in the source's
    // settings; the same words held to whole words only when a plain one also matches inside another.
    if (required.isNotEmpty() || excluded.isNotEmpty()) {
        val plainExcluded = excluded.filterNot { word -> excluded.any { it != word && it in word } }
        val plain = "^" + required.joinToString("") { "(?=.*${it.escapedForRegex()})" } +
            plainExcluded.joinToString("") { "(?!.*${it.escapedForRegex()})" }
        if (picks(plain)) return plain
        val whole = "^" + required.joinToString("") { "(?=.*${it.asToken()})" } +
            excluded.joinToString("") { "(?!.*${it.asToken()})" }
        if (picks(whole)) return whole
    }
    return "^" + chosen.name.escapedForRegex() + "$"
}

private class VariantName(val abi: String?, val words: List<String>)

/** Processor names as releases spell them, longest first so "arm64-v8a" is read before "arm64",
 *  each with the name Android gives it. */
private val ABI_SPELLINGS = listOf(
    "arm64-v8a" to "arm64-v8a", "armeabi-v7a" to "armeabi-v7a", "x86_64" to "x86_64",
    "aarch64" to "arm64-v8a", "arm64" to "arm64-v8a",
    "armv7" to "armeabi-v7a", "arm32" to "armeabi-v7a", "armhf" to "armeabi-v7a", "armeabi" to "armeabi-v7a",
    "x64" to "x86_64", "x86" to "x86",
)

/** Words that say a file runs on any processor, which the label already says on its own. */
private val UNIVERSAL_WORDS = setOf("universal", "noarch")

private val VERSION_WORD = Regex("""v?\d+""")

private fun parseApkName(fileName: String): VariantName {
    var rest = fileName.lowercase().removeSuffix(".apk")
    var abi: String? = null
    for ((spelling, name) in ABI_SPELLINGS) {
        val at = tokenIndex(rest, spelling)
        if (at < 0) continue
        abi = name
        rest = rest.removeRange(at, at + spelling.length)
        break
    }
    val words = rest.split('-', '_', '.', ' ', '+')
        .filter { it.isNotEmpty() && !VERSION_WORD.matches(it) && it !in UNIVERSAL_WORDS }
    return VariantName(abi, words)
}

/** The processor name as [fileName] spells it, for a filter that matches the same spelling later. */
private fun abiTokenIn(fileName: String): String? {
    val lower = fileName.lowercase()
    return ABI_SPELLINGS.firstOrNull { (spelling, _) -> tokenIndex(lower, spelling) >= 0 }?.first
}

/** Where [token] stands in [text] as a whole word, not inside a longer one, or -1. */
private fun tokenIndex(text: String, token: String): Int {
    var from = 0
    while (true) {
        val at = text.indexOf(token, from)
        if (at < 0) return -1
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + token.length)
        if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return at
        from = at + 1
    }
}

private fun String.asToken(): String = "(?<![a-z0-9])${escapedForRegex()}(?![a-z0-9])"
