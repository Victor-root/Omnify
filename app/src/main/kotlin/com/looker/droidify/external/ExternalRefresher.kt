package com.looker.droidify.external

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.looker.droidify.BuildConfig
import com.looker.droidify.network.Downloader
import com.looker.droidify.utility.apk.ApkBinaryManifest
import com.looker.droidify.utility.apk.RemoteApkManifestReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks tracked external sources for new releases and reconciles each one against what is actually on
 * the device, the way [com.looker.droidify.work.SyncWorker] does for catalogue repositories.
 *
 * This deliberately lives outside any ViewModel. It used to be a method on
 * [com.looker.droidify.compose.externalApps.ExternalAppsViewModel], which meant it could only ever run
 * while a screen was open: external sources had no background update check at all, and an app whose
 * source published a new release only learned about it the next time the user happened to open Omnify.
 * A ViewModel needs a ViewModelStoreOwner, which a headless worker has none of, so the work had to move
 * here to be schedulable. The ViewModel now delegates to this, so the foreground and background paths
 * are the same code rather than two implementations that can drift apart.
 */
@Singleton
class ExternalRefresher @Inject constructor(
    private val externalApi: ExternalApi,
    private val repository: ExternalAppRepository,
    private val downloader: Downloader,
    @param:ApplicationContext private val context: Context,
) {

    /** Serialises refreshes: the screen-entry one and the scheduled one can otherwise overlap, and two
     *  passes writing the same records would each re-read a snapshot the other has already superseded. */
    private val mutex = Mutex()

    /** When the last refresh ran (elapsedRealtime), throttling the per-screen-entry ones. Process-wide,
     *  because this object is: the ViewModel is obtained with `hiltViewModel()` inside each navigation
     *  destination, so the app list and every detail screen get their own instance, and a per-instance
     *  timer would start back at zero on every single screen open. */
    private var lastRefreshAt = 0L

    /**
     * Re-checks every tracked source for a new release, and backfills one-time metadata (package id,
     * repo icon, real app name, TV support) for sources added before those existed.
     *
     * The release check is enabled-only, like a disabled repository: each one costs a provider API call,
     * so a source nobody's opted into yet shouldn't burn the anonymous 60-requests/hour budget on updates
     * nobody's watching for. The metadata backfill runs for every tracked app regardless of enabled
     * state, though: it's what the pre-install browsing UI (the sources list, "Choix d'Omnify") shows
     * *before* the user ever opts in, so a still-disabled source deserves its real icon too. It's
     * genuinely one-time per repo either way (the `*Checked` flags below), so it adds no ongoing cost.
     *
     * [force] is the user having asked, through the app list's refresh button. The throttle exists to
     * stop merely walking into a screen from spending the provider's rate limit, not to refuse someone
     * who pressed the button: without bypassing it, that button did visibly nothing for external sources
     * within ten minutes of the last screen entry, which is most of the time.
     *
     * The list is read straight from the repository, never from a shared state flow: those start on an
     * `emptyList()` placeholder, so at the moment this runs from a screen that has just composed it
     * hasn't emitted yet and the loop below would iterate nothing, silently skipping every source while
     * arming the throttle all the same.
     */
    suspend fun refresh(force: Boolean = false) {
        // Throttle checked under the lock, not before it: the screen-entry pass and the scheduled one can
        // arrive together, and testing outside would let both pass while neither had run yet, so the
        // second would go on to repeat the whole scan the moment the first released the lock.
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastRefreshAt < REFRESH_THROTTLE_MS) return
            lastRefreshAt = now
            val tracked = repository.getApps()
            // No sources yet, so nothing was requested: give the throttle window back instead of sitting
            // it out over a pass that cost nothing. This is a real first-launch case, since the seeded
            // sources are still being written by MainComposeActivity while the first screen composes.
            if (tracked.isEmpty()) {
                lastRefreshAt = 0L
                return
            }
            tracked.forEach { app -> refreshOne(app) }
            // Once a day, re-scan each enabled account for newly published apps (the apps it already
            // found are refreshed by the loop above). Disabled accounts and never-scanned ones (handled
            // by the ViewModel's init watcher) are skipped, so this barely adds to the API cost.
            repository.getAccounts()
                .filter {
                    it.enabled &&
                        it.lastScan != 0L &&
                        System.currentTimeMillis() - it.lastScan > ACCOUNT_RESCAN_INTERVAL_MS
                }
                .forEach { rescanAccountNow(it) }
        }
    }

    private suspend fun refreshOne(app: ExternalApp) {
        // A release may not exist yet (e.g. the seeded Omnify source has no published release), or the
        // source may simply not be enabled yet. Either way we still scan the repo below for its icon /
        // name / TV support, which don't depend on a downloadable APK, and simply keep the existing
        // release fields when there's none.
        val release = if (app.enabled) externalApi.latestReleaseFor(app) else null
        // Track the APK file's identity, not just the tag, so updates are detected from the actual APK
        // (see ExternalApp.hasUpdate); keep its file name for the "latest APK" line.
        val tag = release?.tag ?: app.latestTag
        val token = release?.apkVersionToken(filter = app.apkFilter) ?: app.latestApkToken
        val apkName = release?.apkFileName(filter = app.apkFilter) ?: app.latestApkName
        val apkSize = release?.apkFileSize(filter = app.apkFilter) ?: app.latestApkSize
        val apkUrl = release?.apkDownloadUrl(filter = app.apkFilter) ?: app.latestApkUrl
        val releaseAt = release?.apkUpdatedAtMillis(filter = app.apkFilter) ?: app.latestReleaseAt
        // Every field the update decision reads, on one line, plus the answer it produces: whether a
        // source offers an update comes down to comparing these two halves (see hasUpdateGiven), and
        // without them a wrongly-offered update is guesswork from outside. Logged whether or not the
        // lookup succeeded, since a source keeping its previous values because the provider couldn't be
        // reached is itself worth seeing.
        val onDevice = app.packageName?.let(::installedVersionName)
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "refresh ${app.key}: fetched=${release != null} | " +
                    "latest tag=$tag apk=$apkName token=$token | " +
                    "pkg=${app.packageName} isInstalled=${app.packageName?.let(::isInstalled)} | " +
                    "installed tag=${app.installedTag} token=${app.installedApkToken} " +
                    "version=${app.installedVersionName} onDevice=$onDevice | " +
                    "update=${app.copy(
                        latestTag = tag,
                        latestApkToken = token,
                        latestApkName = apkName,
                    ).hasUpdateGiven(onDevice)}",
            )
        }
        // Backfill the package id (source build.gradle, else the release APK's own manifest) for sources
        // added before this existed, so an installed app starts showing its real name + icon and is
        // matched as installed even when it arrived via another channel; the existing label reconcile
        // then fills in the on-device name. Never overwrites an id already learned.
        val packageId = resolvePackageId(app, apkUrl)
        // One-time backfill of the repo icon + real app name + TV support for sources added before these
        // existed. Gated by the *Checked flags so a repo is scanned at most once (spares the API rate
        // limit), and never overrides a user-picked icon or name.
        val needsIcon = !app.iconChecked && !app.iconOverridden && app.repoIconUrl == null
        val needsTv = !app.tvChecked
        val needsMeta = needsIcon || needsTv
        val meta = if (needsMeta) externalApi.fetchRepoMetadata(app) else null
        // meta == null means the scan failed (couldn't read the tree); don't mark anything "checked"
        // then, so it retries on a later refresh.
        val scanned = meta != null
        // Only adopt a repo icon when we were actually looking for one, so as not to clobber a set or
        // user-picked icon just because we re-scanned for TV support.
        val repoIcon = if (needsIcon) meta?.iconCandidates?.firstOrNull() ?: app.repoIconUrl else app.repoIconUrl
        val supportsTv = if (needsTv) meta?.supportsTelevision ?: app.supportsTelevision else app.supportsTelevision
        // Only replace the label while it's still an automatic default (never a user/on-device one):
        // either the bare repo name from before prettifying it existed, or the prettified version of it.
        val stillDefaultLabel = !app.nameOverridden &&
            app.packageName?.let { isInstalled(it) } != true &&
            (app.label == app.repo || app.label == prettifyRepoName(app.repo))
        val resolvedLabel = when {
            !stillDefaultLabel -> app.label
            // The real, manifest-read name when one was found (e.g. on a repo backfilled before this
            // scan ever ran). Otherwise, a repo that ships no Android source at all to read a name from
            // (brave/brave-browser, which openly says as much in its own README) never gets past this,
            // however many times it is rescanned. The prettified repo name is what it settles on
            // instead of the raw slug.
            meta?.appName != null -> meta.appName
            else -> prettifyRepoName(app.repo)
        }
        if (tag != app.latestTag ||
            token != app.latestApkToken ||
            apkName != app.latestApkName ||
            apkSize != app.latestApkSize ||
            apkUrl != app.latestApkUrl ||
            packageId != app.packageName ||
            repoIcon != app.repoIconUrl ||
            resolvedLabel != app.label ||
            supportsTv != app.supportsTelevision ||
            (needsMeta && scanned)
        ) {
            // Re-read the current record instead of copying from `app` (a snapshot taken before this
            // function's network calls, which can take several seconds): if the user installed or
            // updated this very app while the refresh was still running, `app`'s installedTag/
            // installedApkToken/packageName/label are already stale, and copying from it here would
            // silently overwrite that fresh install state with the old pre-install values, flashing the
            // Update button back on right after it correctly switched to Launch.
            val current = repository.getApps().firstOrNull { it.key == app.key } ?: app
            repository.upsertApp(
                current.copy(
                    packageName = current.packageName ?: packageId,
                    label = if (resolvedLabel != app.label) resolvedLabel else current.label,
                    latestTag = tag,
                    latestApkToken = token,
                    latestApkName = apkName,
                    latestApkSize = apkSize,
                    latestApkUrl = apkUrl,
                    latestReleaseAt = releaseAt,
                    repoIconUrl = repoIcon,
                    iconChecked = current.iconChecked || (needsIcon && scanned),
                    supportsTelevision = supportsTv,
                    tvChecked = current.tvChecked || (needsTv && scanned),
                ),
            )
        }
    }

    /**
     * Re-scans [account]'s repos to pick up newly published apps (existing ones are left untouched; the
     * per-app loop refreshes those). Returns how many new apps were discovered, so a manual rescan can
     * report it; the automatic callers ignore it.
     */
    suspend fun rescanAccountNow(account: ExternalAccount): Int {
        val repos = externalApi.listAccountRepos(
            account.provider,
            account.effectiveHost,
            account.owner,
            account.includeForks,
        )
        // Bump the last-scan time even when the listing fails/empties, so a transient failure doesn't
        // make every refresh hammer the API; a real new app shows up at the next daily scan.
        var discoveredCount = 0
        if (repos.isNotEmpty()) {
            // Skip repos already tracked: this account's existing apps, plus any standalone single-repo
            // source (so the account never absorbs e.g. the built-in Omnify repo).
            val skipKeys = repository.getApps()
                .filter { it.accountKey == null || it.accountKey == account.key }
                .map { it.key }
                .toSet()
            val discovered = discoverAccountApps(
                account = account,
                repos = repos,
                skipKeys = skipKeys,
                includePrereleases = false,
                muteUpdates = false,
                apkFilter = "",
                versionExcludeFilter = "",
            )
            if (discovered.isNotEmpty()) {
                repository.upsertApps(discovered)
                discoveredCount = discovered.size
            }
        }
        repository.upsertAccount(account.copy(lastScan = System.currentTimeMillis()))
        return discoveredCount
    }

    /**
     * For each repo of [account] not already tracked ([skipKeys]), keeps those that ship an installable
     * APK release and builds an [ExternalApp] for them (with package id, icon, name and TV support read
     * from the repo, like a single-repo source). Sequential to pace the provider's rate limit.
     */
    suspend fun discoverAccountApps(
        account: ExternalAccount,
        repos: List<RepoRef>,
        skipKeys: Set<String>,
        includePrereleases: Boolean,
        muteUpdates: Boolean,
        apkFilter: String,
        versionExcludeFilter: String,
    ): List<ExternalApp> {
        val filter = apkFilter.trim().ifEmpty { null }
        val excludeFilter = versionExcludeFilter.trim().ifEmpty { null }
        val result = mutableListOf<ExternalApp>()
        for (ref in repos) {
            val candidate = ExternalApp(
                provider = account.provider,
                host = account.host,
                owner = ref.owner,
                repo = ref.repo,
                includePrereleases = includePrereleases,
                muteUpdates = muteUpdates,
                apkFilter = filter,
                versionExcludeFilter = excludeFilter,
                enabled = account.enabled,
                accountKey = account.key,
                label = prettifyRepoName(ref.repo),
            )
            if (candidate.key in skipKeys) continue
            val release = externalApi.latestReleaseFor(candidate) ?: continue
            val packageId =
                resolvePackageId(candidate, release.apkDownloadUrl(filter = candidate.apkFilter))
            val meta = externalApi.fetchRepoMetadata(candidate)
            val resolvedLabel = packageId?.let { installedLabel(it) } ?: meta?.appName ?: candidate.label
            result += candidate.copy(
                packageName = packageId,
                label = resolvedLabel,
                repoIconUrl = meta?.iconCandidates?.firstOrNull(),
                iconChecked = meta != null,
                supportsTelevision = meta?.supportsTelevision ?: false,
                tvChecked = meta != null,
                latestTag = release.tag,
                latestApkToken = release.apkVersionToken(filter = candidate.apkFilter),
                latestApkName = release.apkFileName(filter = candidate.apkFilter),
                latestApkSize = release.apkFileSize(filter = candidate.apkFilter),
                latestApkUrl = release.apkDownloadUrl(filter = candidate.apkFilter),
                latestReleaseAt = release.apkUpdatedAtMillis(filter = candidate.apkFilter),
            )
        }
        return result
    }

    /**
     * The package id [app] installs under. Already-known [ExternalApp.packageName] wins outright. Otherwise
     * two independent sources are consulted, the source's `build.gradle` (cheap, a raw-file read) and the
     * latest release APK's own `<manifest package>` (a range download, but the authoritative id the app
     * really installs under), and the winner is whichever one is actually installed on the device, so an
     * app already present (from any channel) is matched even when `build.gradle` yields a wrong-but-plausible
     * id (a `namespace`/test id) or nothing at all (a monorepo/Flutter layout the fixed paths don't cover).
     * When neither candidate is installed, the APK's real id is preferred over the gradle guess. The APK read
     * is skipped only when the gradle id already matches an installed package. Null when nothing yields an
     * id. Never throws.
     */
    suspend fun resolvePackageId(
        app: ExternalApp,
        apkUrl: String? = app.latestApkUrl,
    ): String? {
        app.packageName?.let { return it }
        val gradleId = externalApi.fetchPackageId(app)
        // Fast path: the source-tree id is already the installed one, no need to touch the APK.
        if (gradleId != null && isInstalled(gradleId)) return gradleId
        val apkId = apkUrl?.let { readApkPackageId(it) }
        // Prefer whichever candidate is actually installed; else the APK's authoritative id; else the gradle
        // guess as a last resort (it may be the right id for an app that simply isn't installed yet).
        return listOfNotNull(apkId, gradleId).firstOrNull { isInstalled(it) }
            ?: apkId
            ?: gradleId
    }

    /** The package id declared by the APK at [apkUrl], read from its own binary manifest via a range
     *  request rather than downloading the whole file. Null when it can't be read. */
    suspend fun readApkPackageId(apkUrl: String): String? = runCatching {
        RemoteApkManifestReader.fetchManifestBytes(downloader, apkUrl)
            ?.let(ApkBinaryManifest::packageName)
    }.getOrNull()

    fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun installedVersionName(packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()

    fun installedLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private const val TAG = "ExternalRefresher"

/** Minimum gap between automatic network refreshes of external sources (they fire on every screen entry
 *  and each enabled source is one provider API call, so this protects the rate-limit budget). */
private const val REFRESH_THROTTLE_MS = 10 * 60 * 1000L

/** How often an account source is re-scanned for newly published apps. Listing a whole account is
 *  several API calls, so it runs at most once a day rather than on every refresh. */
private const val ACCOUNT_RESCAN_INTERVAL_MS = 24 * 60 * 60 * 1000L
