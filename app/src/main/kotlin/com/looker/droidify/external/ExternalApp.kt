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
    /** The instance host, e.g. "git.example.org" for a self-hosted Gitea/Forgejo. Empty means the
     *  provider's public host (github.com / gitlab.com / codeberg.org), so every existing source keeps
     *  working unchanged and old backups deserialize as before. */
    val host: String = "",
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
    /** Size in bytes of the APK in the latest release, for the hero card's "Taille" stat — mirrors the
     *  F-Droid catalogue's APK size stat. Null when not yet known or the provider doesn't expose it
     *  (GitLab's release link assets carry no size). */
    val latestApkSize: Long? = null,
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
    /** True when the source repo's manifest declares Android TV support (the leanback launcher /
     *  uses-feature), detected from the repo without downloading the APK. Drives the "Made for TV" row. */
    val supportsTelevision: Boolean = false,
    /** Whether the repo has already been scanned for TV support, so it's checked at most once (mirrors
     *  [iconChecked]); lets sources added before this existed backfill [supportsTelevision] on refresh. */
    val tvChecked: Boolean = false,
    /** [ExternalAccount.key] of the account this app was auto-discovered from (a whole-account source),
     *  or null for a manually added single-repo source. Apps with an account are managed as one row in
     *  the sources list (the account) instead of individually, and follow the account's enabled state. */
    val accountKey: String? = null,
) {
    /** The host actually called: [host] when set, otherwise the provider's public default. */
    val effectiveHost: String
        get() = host.ifEmpty {
            when (provider) {
                SourceProvider.GITHUB -> "github.com"
                SourceProvider.GITLAB -> "gitlab.com"
                SourceProvider.CODEBERG -> "codeberg.org"
            }
        }

    /** Stable identity for lists / de-duplication (provider- and host-scoped, so the same owner/repo on
     *  two instances stays distinct). Keeps the old format for public sources so existing data matches. */
    val key: String
        get() = if (host.isEmpty()) {
            "${provider.name}/$owner/$repo"
        } else {
            "${provider.name}/$host/$owner/$repo"
        }

    /** "owner/repo", shown in the UI. */
    val path: String get() = "$owner/$repo"

    val webUrl: String get() = "https://$effectiveHost/$owner/$repo"

    /** Origin shown in the UI: the provider name for a public host (GitHub / GitLab / Codeberg), or the
     *  actual instance host for a self-hosted source — so a Forgejo at git.example.org isn't labelled
     *  "Codeberg" just because it shares the Gitea API. */
    val sourceLabel: String get() = if (host.isEmpty()) provider.label else host

    /** Branchless raw base for fetching the project's files (README, manifest, build files) and for
     *  loading icons. These clients send a non-browser user-agent, which Gitea's API raw endpoint serves
     *  the real file to. No default-branch lookup is needed. */
    val readmeBaseUrl: String
        get() = when (provider) {
            SourceProvider.GITHUB -> "https://raw.githubusercontent.com/$owner/$repo/HEAD/"
            SourceProvider.CODEBERG -> "https://$effectiveHost/api/v1/repos/$owner/$repo/raw/"
            SourceProvider.GITLAB -> "https://$effectiveHost/$owner/$repo/-/raw/HEAD/"
        }

    /** Base the README WebView resolves relative links/images against. It differs from [readmeBaseUrl]
     *  only for Gitea/Forgejo: that API raw endpoint returns an HTML page (not the file) to browser
     *  user-agents like the WebView, so the browser-facing web raw path is used for images to load. */
    val readmeWebBaseUrl: String
        get() = when (provider) {
            SourceProvider.CODEBERG -> "https://$effectiveHost/$owner/$repo/raw/HEAD/"
            else -> readmeBaseUrl
        }

    /** The human-browsable page for a repo-root file (e.g. a changelog) — unlike [readmeBaseUrl],
     *  this is a page meant to be opened in a real browser, not fetched as raw content, so each
     *  provider's own file-viewer path is used instead of its raw-content one. */
    fun fileViewUrl(fileName: String): String = when (provider) {
        SourceProvider.GITHUB -> "https://$effectiveHost/$owner/$repo/blob/HEAD/$fileName"
        SourceProvider.CODEBERG -> "https://$effectiveHost/$owner/$repo/src/branch/HEAD/$fileName"
        SourceProvider.GITLAB -> "https://$effectiveHost/$owner/$repo/-/blob/HEAD/$fileName"
    }

    /**
     * A logo to show *before* the app is installed: the source account's avatar. GitHub exposes a
     * stable per-owner avatar at `github.com/<owner>.png` (for AdAway that's the AdAway logo). The
     * other providers have no equally stable by-name URL, so we fall back to a placeholder until the
     * app is installed (then the real launcher icon is used).
     */
    val iconUrl: String?
        get() = when {
            provider == SourceProvider.GITHUB && host.isEmpty() -> "https://github.com/$owner.png"
            else -> null
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

    companion object {
        /** Key of the built-in Omnify repo source (github.com/Victor-root/Omnify). Pinned to the top of
         *  the sources list and only toggleable (no edit/remove) since it's the app's own channel. */
        const val OMNIFY_REPO_KEY = "GITHUB/Victor-root/Omnify"
    }
}
