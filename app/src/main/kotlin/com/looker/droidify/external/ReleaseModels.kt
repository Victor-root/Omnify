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
)

/** A downloadable file attached to a [Release]. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
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
    val assets: List<RestAssetDto> = emptyList(),
) {
    fun toRelease(): Release = Release(
        tag = tagName,
        isPrerelease = prerelease,
        assets = assets.map { ReleaseAsset(it.name, it.browserDownloadUrl) },
    )
}

@Serializable
data class RestAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
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
    val assets: GitlabAssets = GitlabAssets(),
) {
    fun toRelease(): Release = Release(
        tag = tagName,
        isPrerelease = upcomingRelease,
        // Prefer the direct asset URL (the real file) over the generic redirect URL.
        assets = assets.links.map { ReleaseAsset(it.name, it.directAssetUrl ?: it.url) },
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
 * Release assets carry no ABI metadata, only file names, so we match the device's ABIs (most
 * preferred first) against the name, then fall back to a universal build, then the only/first APK.
 * Returns null when the release ships no APK.
 */
fun selectApkAsset(
    assets: List<ReleaseAsset>,
    deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
): ReleaseAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
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
    // No ABI-specific build matched: prefer a universal one, else just the first APK.
    return apks.firstOrNull { apk ->
        val name = apk.name.lowercase()
        "universal" in name || "noarch" in name || "all" in name
    } ?: apks.first()
}

private fun abiAliases(abi: String): List<String> = when (abi) {
    "arm64-v8a" -> listOf("arm64-v8a", "arm64", "aarch64")
    "armeabi-v7a" -> listOf("armeabi-v7a", "armeabi", "armv7", "arm32")
    "x86_64" -> listOf("x86_64", "x64")
    "x86" -> listOf("x86")
    else -> listOf(abi.lowercase())
}
