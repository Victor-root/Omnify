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
    /** Release tag the user last installed (e.g. "v1.2.3"). Kept for display. */
    val installedTag: String? = null,
    /** Most recent release tag seen on the provider (of a release that ships an APK). For display. */
    val latestTag: String? = null,
    /** Identity of the APK file the user installed (its upload time / id — see
     *  [com.looker.droidify.external.apkVersionToken]). Updates are tracked against this, not the tag,
     *  so a server-only version bump (new tag, same or no APK) isn't mistaken for an update. Null until
     *  first installed, or for apps installed before APK-token tracking existed. */
    val installedApkToken: String? = null,
    /** Identity of the APK in the latest release that actually ships one. */
    val latestApkToken: String? = null,
    /** File name of the APK in the latest release (e.g. "GlassKeep-1.5.0.apk"), shown as the "latest
     *  APK" line — universal across repos and often the real APK version when the tag isn't. */
    val latestApkName: String? = null,
    /** Whether to consider pre-releases when picking the latest release. */
    val includePrereleases: Boolean = false,
    /** Optional regex matched against APK file names to choose which APK to install when a release
     *  ships several (e.g. per-ABI splits). Empty/null = pick automatically by device architecture. */
    val apkFilter: String? = null,
    /** Whether this source is active. Disabled sources are hidden from the External tab and updates,
     *  and skipped when checking for new releases — exactly like a disabled F-Droid repository. */
    val enabled: Boolean = true,
    /** The [label] is a user-set custom name; when true it isn't overwritten by the installed app's
     *  on-device name. */
    val nameOverridden: Boolean = false,
    /** "Track only": keep this source up to date but don't surface its updates (Updates tab and update
     *  notifications) — useful for apps the user updates by other means. */
    val muteUpdates: Boolean = false,
    /** A launcher icon found in the source repo's `res/` tree (a raster PNG/WebP), shown *before* the
     *  app is installed so the card has the real icon, not just the account avatar. Null until detected
     *  or when the repo ships only vector/adaptive icons (then we fall back to the avatar). */
    val repoIconUrl: String? = null,
    /** True when [repoIconUrl] was chosen by the user via the icon picker, so auto-detection on refresh
     *  won't overwrite their choice (mirrors [nameOverridden]). */
    val iconOverridden: Boolean = false,
    /** Whether the repo has already been scanned for an icon. Stops a repo that ships only vector/
     *  adaptive icons (so [repoIconUrl] stays null) from being re-scanned on every refresh. */
    val iconChecked: Boolean = false,
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

    /**
     * A different APK than the one installed is available. Compared by APK identity (the file itself),
     * so a new release tag with no new APK doesn't count. Falls back to the release tag only for apps
     * installed before APK-token tracking existed (their token is backfilled on the next install).
     */
    val hasUpdate: Boolean
        get() = when {
            installedApkToken != null && latestApkToken != null ->
                installedApkToken != latestApkToken
            installedTag != null && latestTag != null -> latestTag != installedTag
            else -> false
        }
}
