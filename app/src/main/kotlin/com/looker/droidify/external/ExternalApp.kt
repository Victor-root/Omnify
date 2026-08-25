package com.looker.droidify.external

import com.looker.droidify.BuildConfig
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
    /** The on-device APK's real versionName (from the package manager), captured at the same moment
     *  as [installedTag]/[installedApkToken] — i.e. only once the install actually succeeded. Used to
     *  tell a genuinely up-to-date record apart from a stale one (e.g. the recorded install never
     *  really landed, or the app was since replaced by another install source): see [hasUpdateGiven].
     *  Null for apps installed before this was tracked, which makes them self-heal through the same
     *  fallback the very first time [hasUpdateGiven] runs. */
    val installedVersionName: String? = null,
    /** Identity of the APK in the latest release that actually ships one. */
    val latestApkToken: String? = null,
    /** File name of the APK in the latest release (e.g. "GlassKeep-1.5.0.apk"), shown as the "latest
     *  APK" line — universal across repos and often the real APK version when the tag isn't. */
    val latestApkName: String? = null,
    /** Size in bytes of the APK in the latest release, for the hero card's "Taille" stat — mirrors the
     *  F-Droid catalogue's APK size stat. Null when not yet known or the provider doesn't expose it
     *  (GitLab's release link assets carry no size). */
    val latestApkSize: Long? = null,
    /** Direct download URL of the APK [latestApkName] refers to — captured for free alongside the other
     *  latest* fields whenever a release is fetched (it's already part of that same response), instead
     *  of costing a dedicated network call later. Used to read the APK's real signing certificate via a
     *  cheap HTTP range request ([com.looker.droidify.utility.apk.ApkSigningBlockReader]) and cross-check
     *  it against whatever's actually installed under [packageName] — the same package-name-collision
     *  risk the F-Droid catalogue can hit (a de-Googled fork sharing an app's real package id, say), just
     *  without an index to compare against ahead of time. Null for sources added before this existed,
     *  which simply skip that check until their next refresh backfills it. */
    val latestApkUrl: String? = null,
    /** Epoch millis of the latest release's APK publication date (from the release asset's `updated_at`),
     *  captured alongside the other latest-* fields so the app can be ranked in the "recently updated"
     *  discovery row next to catalogue apps. Null for sources added before this existed (backfilled on
     *  their next refresh) or when the date couldn't be parsed. */
    val latestReleaseAt: Long? = null,
    /** Whether to consider pre-releases when picking the latest release. */
    val includePrereleases: Boolean = false,
    /** Optional regex matched against APK file names to choose which APK to install when a release
     *  ships several (e.g. per-ABI splits). Empty/null = pick automatically by device architecture. */
    val apkFilter: String? = null,
    /** Optional comma-separated keywords matched (plain substring, case-insensitive) against a
     *  release's tag and title; any match is skipped when picking a release, even if
     *  [includePrereleases] is on and even when the provider's own release isn't flagged as a
     *  pre-release. For sources whose automated publishing doesn't reliably set that flag on unstable
     *  builds (e.g. a browser that ships its Nightly/Beta channel as a plain GitHub release).
     *  Empty/null = no extra filtering beyond [includePrereleases]. */
    val versionExcludeFilter: String? = null,
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
    /** Whether the repo has already been scanned for an `<adaptive-icon>` to compose (see
     *  [AdaptiveIconComposer]), which is a separate question from [iconChecked]: a source scanned before
     *  that existed found a flat raster, marked itself checked, and would otherwise never look for the
     *  real icon at all. Defaulting to false gives every already-tracked source exactly one more scan. */
    val adaptiveIconChecked: Boolean = false,
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
    /** True for a source Omnify itself ships pre-seeded as a suggestion (see MainComposeActivity),
     *  shown in its own "Omnify's picks" section on the repositories screen instead of the regular
     *  sources list — a hand-picked, growing set of apps worth discovering, distinct from a source the
     *  user added themselves. */
    val curated: Boolean = false,
    /** True for a [curated] source that's part of the Android TV pack (see MainComposeActivity's
     *  curatedTvPack) — grouped into their own "Made for TV" sub-heading within "Omnify's picks" on the
     *  repositories screen, rather than mixed in alphabetically with every other curated pick. Meaningless
     *  when [curated] is false. */
    val curatedTv: Boolean = false,
) {
    /** The host actually called: [host] when set, otherwise the provider's public default. */
    val effectiveHost: String
        get() = host.ifEmpty { publicHost(provider) }

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

    /** [path] for use inside a URL, where a name has to stay a name (see [urlPathSegment]). Every
     *  address built below goes through this rather than [path]. */
    internal val repoPath: String get() = repoPath(owner, repo)

    val webUrl: String get() = "https://$effectiveHost/$repoPath"

    /** Origin shown in the UI: the provider name for a public host (GitHub / GitLab / Codeberg), or the
     *  actual instance host for a self-hosted source — so a Forgejo at git.example.org isn't labelled
     *  "Codeberg" just because it shares the Gitea API. */
    val sourceLabel: String get() = if (host.isEmpty()) provider.label else host

    /** Branchless raw base for fetching the project's files (README, manifest, build files) and for
     *  loading icons. These clients send a non-browser user-agent, which Gitea's API raw endpoint serves
     *  the real file to. No default-branch lookup is needed. */
    val readmeBaseUrl: String
        get() = when (provider) {
            SourceProvider.GITHUB -> "https://raw.githubusercontent.com/$repoPath/HEAD/"
            SourceProvider.CODEBERG -> "https://$effectiveHost/api/v1/repos/$repoPath/raw/"
            SourceProvider.GITLAB -> "https://$effectiveHost/$repoPath/-/raw/HEAD/"
        }

    /** Base the README WebView resolves relative links/images against. It differs from [readmeBaseUrl]
     *  only for Gitea/Forgejo: that API raw endpoint returns an HTML page (not the file) to browser
     *  user-agents like the WebView, so the browser-facing web raw path is used for images to load. */
    val readmeWebBaseUrl: String
        get() = when (provider) {
            SourceProvider.CODEBERG -> "https://$effectiveHost/$repoPath/raw/HEAD/"
            else -> readmeBaseUrl
        }

    /** The human-browsable page for a repo-root file (e.g. a changelog) — unlike [readmeBaseUrl],
     *  this is a page meant to be opened in a real browser, not fetched as raw content, so each
     *  provider's own file-viewer path is used instead of its raw-content one. */
    fun fileViewUrl(fileName: String): String = when (provider) {
        SourceProvider.GITHUB -> "https://$effectiveHost/$repoPath/blob/HEAD/$fileName"
        SourceProvider.CODEBERG -> "https://$effectiveHost/$repoPath/src/branch/HEAD/$fileName"
        SourceProvider.GITLAB -> "https://$effectiveHost/$repoPath/-/blob/HEAD/$fileName"
    }

    /** The provider's own "all releases" page — the changelog destination for a repo that documents
     *  its changes only through release notes rather than a checked-in CHANGELOG-style file (see
     *  [com.looker.droidify.external.ExternalApi.fetchChangelogUrl]). */
    val releasesUrl: String
        get() = when (provider) {
            SourceProvider.GITLAB -> "https://$effectiveHost/$repoPath/-/releases"
            SourceProvider.GITHUB, SourceProvider.CODEBERG -> "https://$effectiveHost/$repoPath/releases"
        }

    /**
     * A logo to show *before* the app is installed: the source account's avatar. GitHub exposes a
     * stable per-owner avatar at `github.com/<owner>.png` (for AdAway that's the AdAway logo). The
     * other providers have no equally stable by-name URL, so we fall back to a placeholder until the
     * app is installed (then the real launcher icon is used).
     */
    val iconUrl: String?
        get() = when {
            provider == SourceProvider.GITHUB && host.isEmpty() ->
                "https://github.com/${owner.urlPathSegment()}.png"
            else -> null
        }

    /**
     * A different APK than the one installed is available. Compared by APK identity (the file itself),
     * so a new release tag with no new APK doesn't count. Falls back to the release tag only for apps
     * installed before APK-token tracking existed (their token is backfilled on the next install).
     *
     * A token/tag difference alone doesn't always mean a real update, though: a release can ship more
     * than one APK for the same version under different file names (confirmed real: Magisk's GitHub
     * release carries both "app-debug.apk" and "Magisk-v30.7.apk" side by side) — [installedApkToken]
     * then just reflects *which of those files* got picked at install time, not a genuinely different
     * version, so a since-fixed selection heuristic (or a filter change) can otherwise wave a false
     * "update available" forever at everyone who installed before the fix, even though reinstalling
     * would fetch the exact same version again. That specific case is only genuinely ambiguous when the
     * release TAG itself is unchanged too (the same published release, just a different asset picked
     * from it) — so the version-name-label agreement above is trusted to suppress the update only then,
     * never on its own. Trusting it unconditionally previously hid a real update whenever two different
     * releases' dotted version labels happened to collapse to the same string (confirmed real: a
     * project tagging build-iteration rebuilds "v8.7.3-1"/"v8.7.3-2" without bumping the app's own
     * versionName — the hyphenated suffix isn't part of the dotted-version extraction, so both tags
     * reduce to the identical "8.7.3" label despite being genuinely different releases with genuinely
     * different APKs). The label must fall back from the APK file name to the release tag when the file
     * name carries no version at all (via [releaseVersionLabel], the same fallback every other version
     * label in the app already uses). Otherwise this comparison can never agree (confirmed real:
     * Brave's GitHub APKs are named per architecture with no version in the file name at all, e.g.
     * "Bravearm64Universal.apk"), which silently disables this whole suppression and turns any
     * re-uploaded-but-unchanged release (the same tag and version, just a replaced asset) into a
     * permanent false "update available".
     */
    val hasUpdate: Boolean
        get() {
            val tokenOrTagChanged = when {
                installedApkToken != null && latestApkToken != null ->
                    installedApkToken != latestApkToken
                installedTag != null && latestTag != null -> latestTag != installedTag
                else -> false
            }
            if (!tokenOrTagChanged) return false
            val sameRelease = installedTag != null && installedTag == latestTag
            val installedLabel = installedVersionName
            val latestLabel = releaseVersionLabel(latestApkName, latestTag).takeIf { it.isNotEmpty() }
            val sameLabel = installedLabel != null && latestLabel != null &&
                installedLabel.equals(latestLabel, ignoreCase = true)
            return !(sameRelease && sameLabel)
        }

    /**
     * Same as [hasUpdate], but also catches a record that doesn't actually reflect what's on the
     * device right now — either because it was installed before its source was tracked (or before
     * APK-token tracking existed), or because the recorded install never really landed (e.g. a system
     * install that silently failed, most commonly a signing-key conflict with a copy installed by
     * another client) and [installedTag]/[installedApkToken]/[installedVersionName] were left pointing
     * at a version that was never actually applied.
     *
     * Falls back to comparing [currentInstalledVersionName] (the on-device APK's real versionName,
     * read from the package manager right now) against the latest release's version whenever it
     * disagrees with our own recorded [installedVersionName] — that mismatch is exactly what "the
     * record doesn't match reality" looks like. When they agree, [hasUpdate]'s provenance-based check
     * is more precise and is trusted as-is (it correctly ignores a tag-only bump that ships no new APK).
     */
    fun hasUpdateGiven(currentInstalledVersionName: String?): Boolean {
        val latestVersion = latestDottedVersion
        val installedVersion = currentInstalledVersionName?.let(::dottedVersionOrNull)
        // Going backwards is never an update, whatever the records say. This happens for real: a
        // project whose newest build reaches other channels first (Brave ships to Play while the
        // matching GitHub release is still a pre-release, which a source that excludes pre-releases
        // rightly ignores) leaves the newest *visible* release older than what is installed. Offering
        // it would push the user down a version, and Android would refuse the install anyway.
        if (latestVersion != null && installedVersion != null &&
            compareVersionStrings(latestVersion, installedVersion) < 0
        ) {
            return false
        }
        val recordMatchesReality = currentInstalledVersionName != null &&
            currentInstalledVersionName == installedVersionName
        if ((installedApkToken != null || installedTag != null) && recordMatchesReality) {
            return hasUpdate
        }
        // Both sides have to be real version numbers to be worth comparing. They used to fall back to
        // the raw APK file name when it carried no version, which then got compared to a version
        // number as text: "BraveMonoarm64.apk" against "1.93.130" compares 'B' to '1', and every
        // letter sorts after every digit, so any such release read as newer than anything installed.
        if (latestVersion == null || installedVersion == null) return false
        return compareVersionStrings(latestVersion, installedVersion) > 0
    }

    /**
     * Whether this source is offering an update right now: [hasUpdateGiven], minus the two ways a source
     * opts out of being counted as one. A disabled source is skipped like a disabled repository, and a
     * "track only" source ([muteUpdates]) keeps following new releases but stays out of the Updates tab
     * and its count.
     *
     * The single definition of that rule, so the Updates tab, its badge and the automatic update
     * installer can't disagree about what counts, a disagreement that would either silently install
     * something the user was never shown, or show an update nothing would ever act on. Note that an app
     * that isn't actually installed can never satisfy this: [hasUpdateGiven] takes the on-device version
     * and returns false without one.
     */
    fun isUpdatePending(currentInstalledVersionName: String?): Boolean =
        !offersOtherReleaseChannel && enabled && !muteUpdates &&
            hasUpdateGiven(currentInstalledVersionName)

    /**
     * True when this is Omnify's own built-in source and the release it currently offers belongs to
     * the other release channel: a stable build published while a beta is running, or the reverse.
     *
     * Such a release is not an update, and installing it would not behave like one. Android identifies
     * an app by its applicationId alone, and the beta channel carries a ".beta" suffix, so the two
     * builds are unrelated apps to it: installing one over the other puts a SECOND Omnify on the device
     * instead of replacing the first, leaving the original behind with its data and still offering that
     * same "update" forever — and with automatic updates on, all of it without anyone pressing
     * anything. So this keeps the release out of [isUpdatePending], which is the single definition the
     * Updates tab, its badge and the automatic installer all read, and the migration prompt takes over
     * from there (see MigrationState).
     *
     * The channel is read off the published release's own APK file name and tag, both of which Gradle
     * derives from the build's versionName, so it follows the build rather than any naming done by
     * hand. Either naming the release a beta is enough to call it one: when the two disagree, treating
     * it as a beta is the reading that leaves an actual beta-to-beta update working normally. When
     * neither is known yet, nothing is blocked — an update is never withheld on a guess.
     */
    /**
     * Whether to actively offer the switch, as opposed to merely refusing to treat it as an update.
     *
     * Only ever from a beta. [offersOtherReleaseChannel] is symmetric because the danger is: installing
     * either channel over the other puts a second Omnify on the device, so both sides have to be kept
     * from doing it by mistake. Inviting is not symmetric. A beta is a stop on the way to the stable
     * build and its user is meant to move on; someone on the stable build is where they belong, and
     * pushing a beta at them is neither wanted nor this feature's business — the more so once betas are
     * published as pre-releases, which most sources are set to ignore anyway.
     */
    val offersStableSwitch: Boolean
        get() = RUNNING_BUILD_IS_BETA && offersOtherReleaseChannel

    val offersOtherReleaseChannel: Boolean
        get() {
            if (key != OMNIFY_REPO_KEY) return false
            // Walking the switch through without publishing a release to trigger it (see the build
            // file). Never true in a release build, whatever is passed to the build.
            if (BuildConfig.SIMULATE_CHANNEL_SWITCH) return true
            val published = listOfNotNull(latestApkName, latestTag)
            if (published.isEmpty()) return false
            val publishedIsBeta = published.any { it.contains(BETA_CHANNEL_MARKER, ignoreCase = true) }
            return publishedIsBeta != RUNNING_BUILD_IS_BETA
        }

    /** The latest release's version as a plain dotted number: from the APK file name when it carries
     *  one (usually more accurate than the tag, see [releaseVersionLabel]), from the release tag
     *  otherwise. Null when neither does, i.e. when there is no version here to compare. */
    private val latestDottedVersion: String?
        get() = latestApkName?.let(::dottedVersionOrNull) ?: latestTag?.let(::dottedVersionOrNull)

    companion object {
        /** Key of the built-in Omnify repo source (github.com/Victor-root/Omnify). Pinned to the top of
         *  the sources list and only toggleable (no edit/remove) since it's the app's own channel. */
        const val OMNIFY_REPO_KEY = "GITHUB/Victor-root/Omnify"

        /** What marks a build, a tag or an APK file name as belonging to the beta channel. Gradle puts
         *  it there itself (the build type's versionNameSuffix, which the APK file name is derived
         *  from), so it is never typed by hand at release time. */
        private const val BETA_CHANNEL_MARKER = "beta"

        /**
         * Whether the running build is the beta channel, i.e. carries the ".beta" applicationId suffix
         * its build type appends. See [offersOtherReleaseChannel].
         *
         * A debug build walking through the channel switch (see the build file) counts as one too:
         * that walkthrough is of a beta about to become the stable build, and a debug build carries
         * ".debug" rather than ".beta", so without this it would be shown the opposite direction —
         * the one case the simulation is not there to look at.
         */
        val RUNNING_BUILD_IS_BETA: Boolean =
            BuildConfig.APPLICATION_ID.endsWith(".$BETA_CHANNEL_MARKER") ||
                BuildConfig.SIMULATE_CHANNEL_SWITCH
    }
}

/**
 * A repo's bare slug turned into something worth reading, for when a real app name (read from the
 * manifest's own `<application android:label>`) genuinely cannot be found: a repo that carries no
 * Android source at all, such as brave/brave-browser, whose own README says it exists only for
 * issues, releases and the wiki, with the actual source kept in a different repository entirely.
 * Hyphens and underscores become spaces, and each resulting word is capitalised.
 *
 * A single already mixed-case word (NewPipe, K9Mail, microG) is left untouched, since that already
 * reads as a deliberately chosen style rather than a raw slug; only a plain lowercase one gets its
 * first letter capitalised.
 */
internal fun prettifyRepoName(repo: String): String = when {
    repo.any { it == '-' || it == '_' } -> repo.split('-', '_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    repo.any { it.isUpperCase() } -> repo
    else -> repo.replaceFirstChar(Char::uppercase)
}
