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

    /** A newer release than the one installed is available. */
    val hasUpdate: Boolean
        get() = installedTag != null && latestTag != null && latestTag != installedTag
}
