package com.looker.droidify.compose.externalApps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.external.ExternalApi
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.apkFileName
import com.looker.droidify.external.apkVersionToken
import com.looker.droidify.external.parseExternalSource
import com.looker.droidify.external.selectApkAsset
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ExternalAppsViewModel @Inject constructor(
    private val externalApi: ExternalApi,
    private val repository: ExternalAppRepository,
    private val downloader: Downloader,
    private val installManager: InstallManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val apps: StateFlow<List<ExternalApp>> = repository.apps.asStateFlow(emptyList())

    /** Bumped to re-query the package manager (e.g. when the screen is reopened). */
    private val installedRefresh = MutableStateFlow(0)

    /** Keys of tracked apps that are currently installed on the device. */
    val installedKeys: StateFlow<Set<String>> = combine(
        repository.apps,
        installManager.state,
        installedRefresh,
    ) { apps, _, _ ->
        apps.filter { app -> app.packageName?.let { isInstalled(it) } == true }
            .map { it.key }
            .toSet()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptySet())

    /** Per-app system install state (Pending/Installing/…), keyed by [ExternalApp.key]. */
    val installStates: StateFlow<Map<String, InstallState>> = combine(
        repository.apps,
        installManager.state,
    ) { apps, states ->
        apps.mapNotNull { app ->
            val pkg = app.packageName ?: return@mapNotNull null
            val state = states[PackageName(pkg)] ?: return@mapNotNull null
            app.key to state
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyMap())

    /** Per-app real installed versionName (read from the package manager), keyed by
     *  [ExternalApp.key] — so the detail shows the version actually on the device vs. the repo's. */
    val installedVersions: StateFlow<Map<String, String>> = combine(
        repository.apps,
        installManager.state,
        installedRefresh,
    ) { apps, _, _ ->
        apps.mapNotNull { app ->
            val pkg = app.packageName ?: return@mapNotNull null
            val version = installedVersionName(pkg) ?: return@mapNotNull null
            app.key to version
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyMap())

    /** Live download progress per app (drives the per-card progress bar). */
    private val _downloads = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadStatus>> = _downloads

    /** Keys with a non-download network op in flight (add / update check). */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    /** Drives the Add-source dialog: it stays open with a spinner while the (network) add runs, then
     *  closes itself on success. */
    private val _addState = MutableStateFlow(AddSourceState.IDLE)
    val addState: StateFlow<AddSourceState> = _addState

    /** Acknowledge a finished add so the dialog state resets (called once the dialog has closed). If the
     *  dialog was dismissed while still adding, cancel the in-flight work so a late success can't leave a
     *  stale state that would auto-close the next dialog. */
    fun consumeAddState() {
        if (_addState.value == AddSourceState.LOADING) addJob?.cancel()
        _addState.value = AddSourceState.IDLE
    }

    private val downloadJobs = mutableMapOf<String, Job>()

    /** The in-flight "add source" coroutine, so it can be cancelled if the dialog is dismissed mid-add. */
    private var addJob: Job? = null

    /** When the last network refresh ran (elapsedRealtime), to throttle the per-screen-entry refresh. */
    private var lastNetworkRefreshAt = 0L

    val snackbarHostState = SnackbarHostState()

    /** README (HTML) of the app shown on the detail screen, or null while loading / when none. */
    private val _readme = MutableStateFlow<String?>(null)
    val readme: StateFlow<String?> = _readme

    fun loadReadme(app: ExternalApp) {
        viewModelScope.launch {
            // Show the cached README instantly (if any) so a re-open isn't blocked on the network,
            // then refresh in the background and update the disk cache.
            val cached = withContext(Dispatchers.IO) { ReadmeCache.load(context, app.key) }
            _readme.value = cached
            val fresh = externalApi.readmeHtml(app)
            if (fresh != null) {
                _readme.value = fresh
                withContext(Dispatchers.IO) { ReadmeCache.save(context, app.key, fresh) }
            }
        }
    }

    /** Launcher-icon candidates found in the source repo, for the icon picker (best first). Empty when
     *  none were found or the provider isn't supported (then the card uses the account avatar). */
    suspend fun loadIconCandidates(app: ExternalApp): List<String> =
        externalApi.fetchIconCandidates(app)

    /** Adds a project from any GitHub/GitLab/Codeberg URL after confirming it has a release. */
    fun addSource(
        url: String,
        includePrereleases: Boolean,
        customName: String = "",
        muteUpdates: Boolean = false,
        apkFilter: String = "",
    ) {
        val ref = parseExternalSource(url)
        if (ref == null) {
            snack(context.getString(R.string.external_invalid_url))
            return
        }
        val trimmedName = customName.trim()
        val app = ExternalApp(
            provider = ref.provider,
            owner = ref.owner,
            repo = ref.repo,
            includePrereleases = includePrereleases,
            muteUpdates = muteUpdates,
            apkFilter = apkFilter.trim().ifEmpty { null },
            label = trimmedName.ifEmpty { ref.repo },
            nameOverridden = trimmedName.isNotEmpty(),
        )
        if (apps.value.any { it.key == app.key }) {
            snack(context.getString(R.string.external_already_added, app.path))
            return
        }
        addJob = viewModelScope.launch {
            _addState.value = AddSourceState.LOADING
            var added = false
            try {
                withBusy(app.key) {
                    val release = externalApi.latestReleaseFor(app)
                    if (release == null) {
                        // A null release here is often the GitHub rate limit; if so (and no token is
                        // set) nudge the user toward adding one instead of a generic "no release".
                        val suggestToken = externalApi.shouldSuggestGithubToken()
                        snack(
                            message = if (suggestToken) {
                                context.getString(R.string.external_rate_limited)
                            } else {
                                context.getString(R.string.external_no_release, app.path)
                            },
                            long = suggestToken,
                        )
                        return@withBusy
                    }
                    // Resolve the package id from the repo's build.gradle (Obtainium-style) so an app
                    // that's already installed is matched and shows its real on-device name + icon right
                    // away, before the user installs it through us.
                    val packageId = externalApi.fetchPackageId(app)
                    // Pull the app's real launcher icon AND its real name from the repo (Obtainium-style),
                    // so the card shows both before anything is installed.
                    val meta = externalApi.fetchRepoMetadata(app)
                    // Name priority: a name the user typed, else the on-device name if it's already
                    // installed, else the real name read from the repo manifest, else the repo name.
                    val resolvedLabel = when {
                        app.nameOverridden -> app.label
                        else -> packageId?.let { installedLabel(it) } ?: meta.appName ?: app.label
                    }
                    repository.addApp(
                        app.copy(
                            packageName = packageId,
                            label = resolvedLabel,
                            repoIconUrl = meta.iconCandidates.firstOrNull(),
                            iconChecked = true,
                            latestTag = release.tag,
                            latestApkToken = release.apkVersionToken(filter = app.apkFilter),
                            latestApkName = release.apkFileName(filter = app.apkFilter),
                        ),
                    )
                    snack(context.getString(R.string.external_added, app.repo))
                    added = true
                }
            } finally {
                // Success closes the dialog; any failure leaves it open (with the error snackbar shown).
                _addState.value = if (added) AddSourceState.SUCCESS else AddSourceState.IDLE
            }
        }
    }

    /** Downloads the latest release's APK (with live progress) and installs it. */
    fun installOrUpdate(app: ExternalApp) {
        if (_downloads.value.containsKey(app.key)) return
        downloadJobs[app.key] = viewModelScope.launch { downloadAndInstall(app) }
    }

    /** Launches the installed app, if it exposes a launcher activity. */
    fun launch(app: ExternalApp) {
        val pkg = app.packageName ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            snack(context.getString(R.string.external_cant_launch, app.label))
            return
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Uninstalls the app via the system installer. */
    fun uninstall(app: ExternalApp) {
        val pkg = app.packageName ?: return
        viewModelScope.launch { installManager.uninstall(PackageName(pkg)) }
    }

    /** Cancels an in-progress download or system install for [app]. */
    fun cancel(app: ExternalApp) {
        val job = downloadJobs[app.key]
        if (job?.isActive == true) {
            job.cancel()
        } else {
            app.packageName?.let { installManager.cancel(PackageName(it)) }
        }
    }

    /** Re-checks every enabled app for a newer release tag (e.g. on opening the screen). Disabled
     *  sources are skipped, like a disabled repository. Throttled: this fires on every screen entry,
     *  and each enabled source costs a GitHub API call, so re-checking more than once every few minutes
     *  would needlessly burn the anonymous 60-requests/hour budget without surfacing fresher updates. */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetworkRefreshAt < REFRESH_THROTTLE_MS) return
        lastNetworkRefreshAt = now
        viewModelScope.launch {
            apps.value.filter { it.enabled }.forEach { app ->
                val release = externalApi.latestReleaseFor(app) ?: return@forEach
                // Track the APK file's identity, not just the tag, so updates are detected from the
                // actual APK (see ExternalApp.hasUpdate); keep its file name for the "latest APK" line.
                val token = release.apkVersionToken(filter = app.apkFilter)
                val apkName = release.apkFileName(filter = app.apkFilter)
                // Backfill the package id (from build.gradle) for sources added before this existed, so
                // an installed app starts showing its real name + icon; the existing label reconcile
                // then fills in the on-device name. Never overwrites an id already learned from install.
                val packageId = app.packageName ?: externalApi.fetchPackageId(app)
                // One-time backfill of the repo icon + real app name for sources added before this
                // existed. Gated by iconChecked so a repo is scanned at most once (a repo with only
                // vector icons / no resolvable name must not be re-scanned every refresh — spares the
                // API rate limit). Never overrides a user-picked icon or name.
                val needsMeta = !app.iconChecked && !app.iconOverridden && app.repoIconUrl == null
                val meta = if (needsMeta) externalApi.fetchRepoMetadata(app) else null
                val repoIcon = meta?.iconCandidates?.firstOrNull() ?: app.repoIconUrl
                // Only replace the label while it's still the bare repo name (never a user/on-device one).
                val resolvedLabel = if (
                    meta?.appName != null &&
                    !app.nameOverridden &&
                    app.label == app.repo &&
                    app.packageName?.let { isInstalled(it) } != true
                ) {
                    meta.appName
                } else {
                    app.label
                }
                if (release.tag != app.latestTag ||
                    token != app.latestApkToken ||
                    apkName != app.latestApkName ||
                    packageId != app.packageName ||
                    repoIcon != app.repoIconUrl ||
                    resolvedLabel != app.label ||
                    needsMeta
                ) {
                    repository.upsertApp(
                        app.copy(
                            packageName = packageId,
                            label = resolvedLabel,
                            latestTag = release.tag,
                            latestApkToken = token,
                            latestApkName = apkName,
                            repoIconUrl = repoIcon,
                            iconChecked = app.iconChecked || needsMeta,
                        ),
                    )
                }
            }
        }
    }

    /** Enables or disables a source — like toggling an F-Droid repo. Disabled sources are hidden
     *  from the External tab and the Updates tab, and skipped when checking for new releases. */
    fun setSourceEnabled(app: ExternalApp, enabled: Boolean) {
        viewModelScope.launch { repository.upsertApp(app.copy(enabled = enabled)) }
    }

    /** Applies edited per-source settings. Re-fetches the latest release when the pre-release setting
     *  or the APK filter changed (both affect which release/APK is picked). A blank name reverts to
     *  the auto-detected one; a blank filter reverts to automatic by-architecture selection. */
    fun updateSource(
        app: ExternalApp,
        customName: String,
        includePrereleases: Boolean,
        muteUpdates: Boolean,
        apkFilter: String,
        iconUrl: String?,
    ) {
        viewModelScope.launch {
            val trimmedName = customName.trim()
            val overridden = trimmedName.isNotEmpty()
            val label = when {
                overridden -> trimmedName
                app.packageName != null -> installedLabel(app.packageName) ?: app.repo
                else -> app.repo
            }
            val trimmedFilter = apkFilter.trim().ifEmpty { null }
            // A different icon than the stored one means the user picked it; mark it overridden so the
            // refresh backfill won't replace their choice. The edit dialog has already scanned the repo,
            // so mark it checked regardless (a vector-only repo won't be re-scanned on refresh).
            val iconChanged = iconUrl != app.repoIconUrl
            var updated = app.copy(
                label = label,
                nameOverridden = overridden,
                muteUpdates = muteUpdates,
                includePrereleases = includePrereleases,
                apkFilter = trimmedFilter,
                repoIconUrl = iconUrl,
                iconOverridden = iconChanged || app.iconOverridden,
                iconChecked = true,
            )
            // The release to offer (and its APK) can change when either the pre-release setting or the
            // APK filter changes, so re-resolve it in that case.
            if (includePrereleases != app.includePrereleases || trimmedFilter != app.apkFilter) {
                externalApi.latestReleaseFor(updated)?.let { release ->
                    updated = updated.copy(
                        latestTag = release.tag,
                        latestApkToken = release.apkVersionToken(filter = updated.apkFilter),
                        latestApkName = release.apkFileName(filter = updated.apkFilter),
                    )
                }
            }
            repository.upsertApp(updated)
        }
    }

    /** Forces a re-query of which tracked apps are installed (e.g. after returning to the screen). */
    fun refreshInstalled() {
        installedRefresh.value++
    }

    /**
     * Replaces each installed app's stored label with the real on-device app name (e.g. "GlassKeep"
     * instead of the repo name "glasskeep-enhanced"). Reads the package manager off the main thread;
     * one upsert per changed label, so it converges and is safe to call on every screen open.
     */
    fun reconcileInstalledLabels() {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.Default) {
                apps.value.mapNotNull { app ->
                    if (app.nameOverridden) return@mapNotNull null
                    val pkg = app.packageName ?: return@mapNotNull null
                    val realLabel = installedLabel(pkg) ?: return@mapNotNull null
                    if (realLabel != app.label) app.copy(label = realLabel) else null
                }
            }
            updated.forEach { repository.upsertApp(it) }
        }
    }

    fun remove(key: String) {
        viewModelScope.launch { repository.removeApp(key) }
    }

    private suspend fun downloadAndInstall(app: ExternalApp) {
        updateDownload(app.key, DownloadStatus(read = 0, total = -1, bytesPerSecond = 0))
        try {
            val release = externalApi.latestReleaseFor(app)
            if (release == null) {
                val suggestToken = externalApi.shouldSuggestGithubToken()
                snack(
                    message = if (suggestToken) {
                        context.getString(R.string.external_rate_limited)
                    } else {
                        context.getString(R.string.external_unreachable, app.provider.label)
                    },
                    long = suggestToken,
                )
                return
            }
            val asset = selectApkAsset(release.assets, filter = app.apkFilter)
            if (asset == null) {
                snack(context.getString(R.string.external_no_apk, app.repo))
                return
            }
            val cacheFileName = "${app.provider.name}_${app.owner}_${app.repo}_${release.tag}.apk"
                .replace(UNSAFE_FILE_CHARS, "_")
            val releaseFile = Cache.getReleaseFile(context, cacheFileName)
            val response = withContext(Dispatchers.IO) {
                // Download to a partial file and promote it on success. The Downloader resumes by
                // Range against the target's current size, so a previously-completed file would make
                // it request past EOF -> HTTP 416 -> failure. Start each download fresh (asset URLs
                // are one-shot CDN links anyway, so resuming wouldn't help).
                val partial = Cache.getPartialReleaseFile(context, cacheFileName)
                partial.delete()
                // Sliding-window speed estimate + throttled UI updates (the callback fires very
                // frequently; we only push a new state a few times per second).
                var windowStart = SystemClock.elapsedRealtime()
                var windowStartBytes = 0L
                var speed = 0L
                var lastEmit = 0L
                val result = downloader.downloadToFile(
                    url = asset.downloadUrl,
                    target = partial,
                ) { read, total ->
                    val now = SystemClock.elapsedRealtime()
                    val windowMs = now - windowStart
                    if (windowMs >= SPEED_WINDOW_MS) {
                        speed = (read.value - windowStartBytes) * 1000L / windowMs
                        windowStart = now
                        windowStartBytes = read.value
                    }
                    val complete = total != null && read.value >= total.value
                    if (now - lastEmit >= EMIT_INTERVAL_MS || complete) {
                        lastEmit = now
                        updateDownload(app.key, DownloadStatus(read.value, total?.value ?: -1L, speed))
                    }
                }
                if (result is NetworkResponse.Success) {
                    partial.copyTo(releaseFile, overwrite = true)
                    partial.delete()
                }
                result
            }
            if (response !is NetworkResponse.Success) {
                snack(context.getString(R.string.external_download_failed, app.repo))
                return
            }
            // External APKs aren't pre-registered like F-Droid ones, so read the package name from
            // the downloaded file to drive the installer and detect future updates.
            val packageName = context.packageManager
                .getPackageArchiveInfo(releaseFile.absolutePath, 0)
                ?.packageName
            if (packageName == null) {
                snack(context.getString(R.string.external_invalid_apk))
                return
            }
            // Read the real icon + app name from the APK we just downloaded (releases carry neither).
            val realLabel = cacheIconAndReadLabel(releaseFile.absolutePath, app.key)
            installManager.install(InstallItem(PackageName(packageName), cacheFileName))
            // Record which APK file this is (its identity), so future update checks compare the APK,
            // not the tag. We just installed the latest release, so installed and latest match.
            val token = release.apkVersionToken(filter = app.apkFilter)
            repository.upsertApp(
                app.copy(
                    packageName = packageName,
                    label = realLabel ?: app.label,
                    installedTag = release.tag,
                    latestTag = release.tag,
                    installedApkToken = token,
                    latestApkToken = token,
                    latestApkName = release.apkFileName(filter = app.apkFilter),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            snack(context.getString(R.string.external_download_failed, app.repo))
        } finally {
            clearDownload(app.key)
        }
    }

    /** Reads the APK at [apkPath]: caches its real launcher icon (best-effort) and returns its real
     *  app label (e.g. "GlassKeep"), so the UI isn't stuck with the repo name. Null on failure. */
    private fun cacheIconAndReadLabel(apkPath: String, key: String): String? {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(apkPath, 0) }.getOrNull() ?: return null
        val appInfo = info.applicationInfo?.apply {
            sourceDir = apkPath
            publicSourceDir = apkPath
        } ?: return null
        runCatching { appInfo.loadIcon(pm).toBitmap() }.getOrNull()?.let {
            ExternalIconCache.save(context, key, it)
        }
        return runCatching { appInfo.loadLabel(pm).toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun installedVersionName(packageName: String): String? = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull()

    private fun installedLabel(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun updateDownload(key: String, status: DownloadStatus) {
        _downloads.value = _downloads.value + (key to status)
    }

    private fun clearDownload(key: String) {
        _downloads.value = _downloads.value - key
        downloadJobs.remove(key)
    }

    private fun setBusy(key: String, busy: Boolean) {
        _busy.value = if (busy) _busy.value + key else _busy.value - key
    }

    private suspend inline fun withBusy(key: String, block: () -> Unit) {
        setBusy(key, true)
        try {
            block()
        } finally {
            setBusy(key, false)
        }
    }

    private fun snack(message: String, long: Boolean = false) {
        viewModelScope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        }
    }
}

/** State of an in-progress "add external source" action, driving the dialog's loading UI. */
enum class AddSourceState { IDLE, LOADING, SUCCESS }

private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

/** How often the download speed is recomputed (sliding window length). */
private const val SPEED_WINDOW_MS = 500L

/** Minimum delay between progress UI updates, to avoid flooding recompositions. */
private const val EMIT_INTERVAL_MS = 150L

/** Minimum gap between automatic network refreshes of external sources (they fire on every screen
 *  entry and each enabled source is one GitHub API call, so this protects the rate-limit budget). */
private const val REFRESH_THROTTLE_MS = 10 * 60 * 1000L
