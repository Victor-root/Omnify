package com.looker.droidify.external

import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider-neutral view of a release, the shape the rest of the app works with. [ExternalApi] maps
 * each provider's REST payload into this.
 */
data class Release(
    val tag: String,
    val isPrerelease: Boolean,
    val assets: List<ReleaseAsset>,
    /** The release's own notes, in Markdown, as written by the maintainer when publishing it — GitHub/
     *  Gitea's "body" field, GitLab's "description". Used as a changelog fallback when the repo ships
     *  no CHANGELOG-style file at all (see [ExternalApi.fetchChangelogHtml]). Null when the provider
     *  reports none, or the release has no notes written. */
    val body: String? = null,
)

/**
 * Result of looking up the release to offer for a source ([ExternalApi.latestReleaseLookup]) — unlike a
 * plain nullable [Release], this tells the caller *why* nothing was found, so the install/update flow
 * can show an accurate message instead of a generic "couldn't reach" for every kind of failure.
 */
sealed class ReleaseLookup {
    data class Found(val release: Release) : ReleaseLookup()

    /** The request itself failed (network/HTTP/parse error), or GitHub's rate limit was hit. */
    data object FetchFailed : ReleaseLookup()

    /** Every release in the recent window is a pre-release and the source has "include pre-releases"
     *  turned off — nothing else disqualified them. */
    data object OnlyPrereleasesExcluded : ReleaseLookup()

    /** Releases were found (and, if relevant, pre-releases are allowed) but none of them ships an APK
     *  this device can install. */
    data object NoCompatibleApk : ReleaseLookup()
}

/** A downloadable file attached to a [Release]. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    /** When the file itself was last uploaded (GitHub/Gitea); null for providers that don't expose it.
     *  ISO-8601, so it sorts chronologically. Changes only when the file is actually re-uploaded. */
    val updatedAt: String? = null,
    /** Provider-assigned id of the uploaded file; null for providers that don't expose it. */
    val id: Long? = null,
    /** The file's size in bytes, for the "Taille" stat shown before installing — mirrors the F-Droid
     *  catalogue's APK size. Null for providers whose release API doesn't expose it (GitLab's link
     *  assets carry no size). */
    val size: Long? = null,
)

/**
 * GitHub's (and Gitea/Codeberg's, which mirror it) release JSON. The two share an identical shape,
 * so a single DTO covers both.
 */
@Serializable
data class RestReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val body: String? = null,
    val assets: List<RestAssetDto> = emptyList(),
) {
    fun toRelease(): Release = Release(
        tag = tagName,
        isPrerelease = prerelease,
        assets = assets.map {
            ReleaseAsset(
                name = it.name,
                downloadUrl = it.browserDownloadUrl,
                updatedAt = it.updatedAt ?: it.createdAt,
                id = it.id.takeIf { id -> id != 0L },
                size = it.size.takeIf { size -> size > 0L },
            )
        },
        body = body?.takeIf { it.isNotBlank() },
    )
}

@Serializable
data class RestAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    val id: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * GitLab's release JSON. APKs are uploaded as release **link** assets (`assets.links`), and the
 * "draft/prerelease" notion maps to `upcoming_release`.
 */
@Serializable
data class GitlabReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    @SerialName("upcoming_release") val upcomingRelease: Boolean = false,
    val description: String? = null,
    val assets: GitlabAssets = GitlabAssets(),
) {
    fun toRelease(): Release = Release(
        tag = tagName,
        isPrerelease = upcomingRelease,
        // Prefer the direct asset URL (the real file) over the generic redirect URL.
        assets = assets.links.map { ReleaseAsset(it.name, it.directAssetUrl ?: it.url) },
        body = description?.takeIf { it.isNotBlank() },
    )
}

@Serializable
data class GitlabAssets(
    val links: List<GitlabLinkDto> = emptyList(),
)

@Serializable
data class GitlabLinkDto(
    val name: String,
    val url: String,
    @SerialName("direct_asset_url") val directAssetUrl: String? = null,
)

/**
 * Picks the APK asset best suited to this device from a release's [assets].
 *
 * Release assets carry no ABI metadata, only file names, so we first narrow to the user's optional
 * name [filter] (a regex — e.g. to force a particular split), then match the device's ABIs (most
 * preferred first) against the name, then fall back to a universal build, then a same-priority
 * tie-break (see below), then the first APK. Returns null when the release ships no APK.
 */
fun selectApkAsset(
    assets: List<ReleaseAsset>,
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    filter: String? = null,
    releaseTag: String? = null,
): ReleaseAsset? {
    val apks = applyApkFilter(
        assets.filter { it.name.endsWith(".apk", ignoreCase = true) },
        filter,
    )
    if (apks.size <= 1) return apks.firstOrNull()

    // Try each ABI the device supports, in preference order, with a few common name aliases.
    for (abi in deviceAbis) {
        val aliases = abiAliases(abi)
        val match = apks.firstOrNull { apk ->
            val name = apk.name.lowercase()
            aliases.any { name.contains(it) }
        }
        if (match != null) return match
    }
    // No ABI-specific build matched: prefer a universal one.
    apks.firstOrNull { apk ->
        val name = apk.name.lowercase()
        "universal" in name || "noarch" in name || "all" in name
    }?.let { return it }

    // Still tied: several ABI-agnostic APKs with no "universal"-style naming either — confirmed on a
    // real release (Magisk's, shipping both "app-debug.apk" and "Magisk-v30.7.apk" side by side).
    // Upload order alone (what plain apks.first() would fall back to) isn't a meaningful ranking and
    // can just as easily surface a leftover debug build first. Two more signals narrow it down before
    // giving up: drop anything that looks like a development build, then prefer whichever name
    // actually carries the release's own version tag — each step only applies if it doesn't eliminate
    // every candidate (same "never refuse everything over one signal" spirit as [applyApkFilter]).
    val nonDebug = apks.filterNot { apk ->
        DEBUG_BUILD_MARKERS.any { marker -> marker in apk.name.lowercase() }
    }.ifEmpty { apks }
    val taggedMatch = releaseTag?.trimStart('v', 'V')?.takeIf { it.isNotBlank() }?.let { tag ->
        nonDebug.filter { it.name.contains(tag, ignoreCase = true) }
    }?.ifEmpty { null }
    return (taggedMatch ?: nonDebug).first()
}

/** Name fragments marking an APK as a development build rather than the intended release, never
 *  preferred over a same-priority sibling asset. Kept short and unambiguous on purpose — a false
 *  positive here would wrongly reject someone's actual release. */
private val DEBUG_BUILD_MARKERS = listOf("debug", "unsigned")

/**
 * Restricts [apks] to those whose file name matches the user's APK [filter] (a regex). A blank or
 * invalid pattern, or one that matches nothing, leaves the list untouched — better to offer *an* APK
 * than refuse everything over a typo (the ABI logic still picks the right one).
 */
private fun applyApkFilter(apks: List<ReleaseAsset>, filter: String?): List<ReleaseAsset> {
    val pattern = filter?.trim()?.takeIf { it.isNotEmpty() } ?: return apks
    val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return apks
    return apks.filter { regex.containsMatchIn(it.name) }.ifEmpty { apks }
}

/** ABI tokens we recognise in APK file names, used to tell a foreign-architecture build apart from a
 *  universal one. */
private val KNOWN_ABI_TOKENS = listOf(
    "arm64-v8a", "arm64", "aarch64",
    "armeabi-v7a", "armeabi", "armv7", "arm32", "armhf",
    "x86_64", "x64", "x86",
)

/**
 * True when this APK's file name advertises an ABI the device does *not* support. A name carrying no
 * recognised ABI token is treated as universal (returns false), so a plain "app-release.apk" is never
 * rejected. Used to spot a release that ships only foreign-architecture APKs.
 */
private fun ReleaseAsset.isForeignAbi(deviceAbis: List<String>): Boolean {
    val name = name.lowercase()
    val carriesAbi = KNOWN_ABI_TOKENS.any { name.contains(it) }
    if (!carriesAbi) return false
    val deviceAliases = deviceAbis.flatMap { abiAliases(it) }
    return deviceAliases.none { name.contains(it) }
}

/**
 * Whether this release ships an APK that can actually run on the device, after the user's name
 * [filter]: at least one APK that isn't a foreign-architecture build. Drives the "fall back to an
 * older release" logic — when the newest release has only, say, an x86 APK, [ExternalApi] keeps
 * looking down the list for one this device can install.
 */
fun Release.hasCompatibleApk(
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    filter: String? = null,
): Boolean {
    val apks = applyApkFilter(
        assets.filter { it.name.endsWith(".apk", ignoreCase = true) },
        filter,
    )
    return apks.any { !it.isForeignAbi(deviceAbis) }
}

/**
 * A stable identity for the APK this release would install: the file's upload time (GitHub/Gitea),
 * falling back to its provider id, then its name. This tracks the actual APK rather than the release
 * tag — the tag is the project's own (server) version and can change with no new APK, so comparing
 * tags falsely flags updates. The token changes only when the APK file itself is re-uploaded.
 * Returns null when the release ships no APK.
 */
fun Release.apkVersionToken(
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    filter: String? = null,
): String? {
    val apk = selectApkAsset(assets, deviceAbis, filter, releaseTag = tag) ?: return null
    return apk.updatedAt ?: apk.id?.toString() ?: apk.name
}

/** The file name of the APK this release would install (e.g. "GlassKeep-1.5.0.apk"), shown as the
 *  "latest APK" line. Universal across repos — whatever the file is actually called — and often
 *  carries the real APK version when the tag doesn't. Null when the release ships no APK. */
fun Release.apkFileName(
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    filter: String? = null,
): String? =
    selectApkAsset(assets, deviceAbis, filter, releaseTag = tag)?.name

/** The size in bytes of the APK this release would install, for the "Taille" stat shown before
 *  installing — mirrors the F-Droid catalogue's APK size stat. Null when the release ships no APK, or
 *  the provider's release API doesn't expose a file size (GitLab). */
fun Release.apkFileSize(
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    filter: String? = null,
): Long? =
    selectApkAsset(assets, deviceAbis, filter, releaseTag = tag)?.size

/** ISO-8601 timestamp of when the APK this release would install was last uploaded, for the release
 *  date shown in the version list — mirrors the F-Droid catalogue's own per-version date
 *  ([com.looker.droidify.compose.appDetail.components.PackageItem]). Null when the release ships no APK,
 *  or the provider's release API doesn't expose an upload time (GitLab's link assets carry none, same
 *  limitation [Release.apkFileSize] already documents). */
fun Release.apkUpdatedAt(
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    filter: String? = null,
): String? =
    selectApkAsset(assets, deviceAbis, filter, releaseTag = tag)?.updatedAt

/** The direct download URL of the APK this release would install — used to read its signing
 *  certificate via a cheap HTTP range request ([com.looker.droidify.utility.apk.ApkSigningBlockReader])
 *  instead of downloading the whole file. Null when the release ships no APK. */
fun Release.apkDownloadUrl(
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    filter: String? = null,
): String? =
    selectApkAsset(assets, deviceAbis, filter, releaseTag = tag)?.downloadUrl

private val versionInFileName = Regex("""\d+(?:\.\d+)+""")

/** Pulls a dotted version number out of an APK file name (e.g. "GlassKeep-v1.4.6.apk" -> "1.4.6") for
 *  a tidy "latest APK" line, falling back to the whole file name when none is present. */
fun apkVersionLabel(fileName: String): String =
    versionInFileName.find(fileName)?.value ?: fileName

/**
 * Compares two dotted version strings (e.g. "2.7.0" vs "2.6.0") component by component, numerically —
 * positive when [a] is newer than [b]. A non-numeric component (e.g. a "-dev.7" suffix) falls back to a
 * plain string comparison for just that component, so this degrades gracefully instead of throwing on a
 * non-standard version string. Used to detect an update against the app's real on-device version when no
 * release tag/APK identity was ever recorded for it (see [com.looker.droidify.external.ExternalApp.hasUpdateGiven]).
 */
fun compareVersionStrings(a: String, b: String): Int {
    val partsA = a.split('.', '-', '+', '_')
    val partsB = b.split('.', '-', '+', '_')
    for (i in 0 until maxOf(partsA.size, partsB.size)) {
        val partA = partsA.getOrNull(i) ?: return -1
        val partB = partsB.getOrNull(i) ?: return 1
        val numA = partA.toIntOrNull()
        val numB = partB.toIntOrNull()
        val comparison = if (numA != null && numB != null) numA.compareTo(numB) else partA.compareTo(partB)
        if (comparison != 0) return comparison
    }
    return 0
}

private fun abiAliases(abi: String): List<String> = when (abi) {
    "arm64-v8a" -> listOf("arm64-v8a", "arm64", "aarch64")
    "armeabi-v7a" -> listOf("armeabi-v7a", "armeabi", "armv7", "arm32", "armhf")
    "x86_64" -> listOf("x86_64", "x64")
    "x86" -> listOf("x86")
    else -> listOf(abi.lowercase())
}
