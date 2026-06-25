package com.looker.droidify.external

import kotlinx.serialization.Serializable

/**
 * A project tracked as an external app source (Obtainium-style): Droidify fetches its latest release
 * from [provider] and installs / updates the APK directly, without a real F-Droid repository.
 *
 * Persisted as JSON (see [ExternalAppRepository]). [packageName] and [installedTag] are filled in
 * once the user installs a release, so updates can be detected against the latest release tag.
 */
@Serializable
data class ExternalApp(
    val provider: SourceProvider = SourceProvider.GITHUB,
    val owner: String,
    val repo: String,
    val label: String = repo,
    /** Resolved from the installed APK's manifest; null until first installed. */
    val packageName: String? = null,
    /** Release tag the user last installed (e.g. "v1.2.3"). */
    val installedTag: String? = null,
    /** Most recent release tag seen on the provider. */
    val latestTag: String? = null,
    /** Whether to consider pre-releases when picking the latest release. */
    val includePrereleases: Boolean = false,
    /** Whether this source is active. Disabled sources are hidden from the External tab and updates,
     *  and skipped when checking for new releases — exactly like a disabled F-Droid repository. */
    val enabled: Boolean = true,
) {
    /** Stable identity for lists / de-duplication (provider-scoped, so the same owner/repo on two
     *  providers stays distinct). */
    val key: String get() = "${provider.name}/$owner/$repo"

    /** "owner/repo", shown in the UI. */
    val path: String get() = "$owner/$repo"

    val webUrl: String
        get() = when (provider) {
            SourceProvider.GITHUB -> "https://github.com/$owner/$repo"
            SourceProvider.GITLAB -> "https://gitlab.com/$owner/$repo"
            SourceProvider.CODEBERG -> "https://codeberg.org/$owner/$repo"
        }

    /**
     * A logo to show *before* the app is installed: the source account's avatar. GitHub exposes a
     * stable per-owner avatar at `github.com/<owner>.png` (for AdAway that's the AdAway logo). The
     * other providers have no equally stable by-name URL, so we fall back to a placeholder until the
     * app is installed (then the real launcher icon is used).
     */
    val iconUrl: String?
        get() = when (provider) {
            SourceProvider.GITHUB -> "https://github.com/$owner.png"
            SourceProvider.GITLAB, SourceProvider.CODEBERG -> null
        }

    /** A newer release than the one installed is available. */
    val hasUpdate: Boolean
        get() = installedTag != null && latestTag != null && latestTag != installedTag
}
