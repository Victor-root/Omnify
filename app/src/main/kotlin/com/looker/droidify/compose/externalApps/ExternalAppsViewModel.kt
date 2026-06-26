package com.looker.droidify.compose.externalApps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
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

    private val downloadJobs = mutableMapOf<String, Job>()

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

    /** Adds a project from any GitHub/GitLab/Codeberg URL after confirming it has a release. */
    fun addSource(url: String, includePrereleases: Boolean) {
        val ref = parseExternalSource(url)
        if (ref == null) {
            snack(context.getString(R.string.external_invalid_url))
            return
        }
        val app = ExternalApp(
            provider = ref.provider,
            owner = ref.owner,
            repo = ref.repo,
            includePrereleases = includePrereleases,
        )
        if (apps.value.any { it.key == app.key }) {
            snack(context.getString(R.string.external_already_added, app.path))
            return
        }
        viewModelScope.launch {
            withBusy(app.key) {
                val release = externalApi.latestReleaseFor(app)
                if (release == null) {
                    snack(context.getString(R.string.external_no_release, app.path))
                    return@withBusy
                }
                repository.addApp(app.copy(latestTag = release.tag))
                snack(context.getString(R.string.external_added, app.repo))
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
     *  sources are skipped, like a disabled repository. */
    fun refresh() {
        viewModelScope.launch {
            apps.value.filter { it.enabled }.forEach { app ->
                val release = externalApi.latestReleaseFor(app)
                if (release != null && release.tag != app.latestTag) {
                    repository.upsertApp(app.copy(latestTag = release.tag))
                }
            }
        }
    }

    /** Enables or disables a source — like toggling an F-Droid repo. Disabled sources are hidden
     *  from the External tab and the Updates tab, and skipped when checking for new releases. */
    fun setSourceEnabled(app: ExternalApp, enabled: Boolean) {
        viewModelScope.launch { repository.upsertApp(app.copy(enabled = enabled)) }
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
                snack(context.getString(R.string.external_unreachable, app.provider.label))
                return
            }
            val asset = selectApkAsset(release.assets)
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
            repository.upsertApp(
                app.copy(
                    packageName = packageName,
                    label = realLabel ?: app.label,
                    installedTag = release.tag,
                    latestTag = release.tag,
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

    private fun snack(message: String) {
        viewModelScope.launch { snackbarHostState.showSnackbar(message) }
    }
}

private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9._-]")

/** How often the download speed is recomputed (sliding window length). */
private const val SPEED_WINDOW_MS = 500L

/** Minimum delay between progress UI updates, to avoid flooding recompositions. */
private const val EMIT_INTERVAL_MS = 150L
