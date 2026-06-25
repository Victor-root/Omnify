package com.looker.droidify.compose.appDetail

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.App
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.datastore.CustomButtonRepository
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.installer.model.installFrom
import com.looker.droidify.network.DataSize
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.network.percentBy
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

@HiltViewModel
class AppDetailViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val repoRepository: RepoRepository,
    private val customButtonRepository: CustomButtonRepository,
    private val settingsRepository: SettingsRepository,
    private val installManager: InstallManager,
    private val downloader: Downloader,
    @param:ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val packageName: String = requireNotNull(savedStateHandle["packageName"]) {
        "Required argument 'packageName' was not found in SavedStateHandle"
    }

    val customButtons: StateFlow<List<CustomButton>> = customButtonRepository.buttons
        .asStateFlow(emptyList())

    /** Current install state of this package (null when nothing is in progress). */
    val installState: StateFlow<InstallState?> = installManager.state
        .map { it[PackageName(packageName)] }
        .asStateFlow(null)

    /** Whether this app is in the user's favourites. */
    val isFavourite: StateFlow<Boolean> = settingsRepository.get { favouriteApps }
        .map { packageName in it }
        .asStateFlow(false)

    /** Adds or removes this app from the user's favourites. */
    fun toggleFavourite() {
        viewModelScope.launch { appRepository.addToFavourite(PackageName(packageName)) }
    }

    private val _downloadStatus = MutableStateFlow<DownloadStatus?>(null)

    /**
     * Live download progress (bytes received, total, speed), or null when no download is
     * running. Drives the progress bar shown before the system install starts.
     */
    val downloadStatus: StateFlow<DownloadStatus?> = _downloadStatus

    private var downloadJob: Job? = null

    val state: StateFlow<AppDetailState> = appRepository
        .getApp(PackageName(packageName))
        .map { apps ->
            when {
                apps.isEmpty() -> AppDetailState.Error("No app found for $packageName")
                else -> AppDetailState.Success(
                    app = apps.first(),
                    packages = apps.flatMap {
                        val repo = repoRepository.getRepo(it.repoId.toInt())
                        if (repo != null && it.packages != null) {
                            it.packages.map { pkg -> pkg to repo }
                        } else {
                            emptyList()
                        }
                    }.sortedByDescending { (pkg, _) -> pkg.manifest.versionCode },
                )
            }
        }
        .onStart { emit(AppDetailState.Loading) }
        // The map above resolves each repo and rebuilds the package list; keep that off the main
        // thread so opening a detail page (and the Room re-emissions during a sync) never ANRs.
        .flowOn(Dispatchers.Default)
        .asStateFlow(AppDetailState.Loading)

    /** Launches the installed app, if it exposes a launcher activity. */
    fun launch() {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Uninstalls the app. */
    fun uninstall() {
        viewModelScope.launch {
            installManager.uninstall(PackageName(packageName))
        }
    }

    /** Cancels an in-progress download or install. */
    fun cancel() {
        val job = downloadJob
        if (job?.isActive == true) {
            job.cancel()
        } else {
            installManager.cancel(PackageName(packageName))
        }
    }

    /** Downloads the best release for *this* device (verifying its hash) and installs it. */
    fun installOrUpdate() {
        if (_downloadStatus.value != null) return
        val current = state.value as? AppDetailState.Success ?: return
        // Pick the release that actually runs on this device's CPU/SDK (see [selectForDevice]):
        // installing e.g. the arm64 VLC APK on an x86 device fails with NO_MATCHING_ABIS.
        val target = current.packages.selectForDevice(current.app.metadata.suggestedVersionCode)
        if (target == null) {
            toast("No version of this app is compatible with your device")
            return
        }
        downloadJob = viewModelScope.launch { downloadAndInstall(target.first, target.second) }
    }

    private suspend fun downloadAndInstall(pkg: Package, repo: Repo) {
        // Non-null status = a download is in progress; start at 0 so the bar shows immediately.
        _downloadStatus.value = DownloadStatus(read = 0, total = -1, bytesPerSecond = 0)
        try {
            val cacheFileName = pkg.apk.hash.replace('/', '-') + ".apk"
            // V2 index file names already start with a slash, e.g. "/An.stop_10.apk".
            // Build the URL the same way the sync layer does (see EntrySyncable): join the
            // repo address (without a trailing slash) to the file name. Using
            // Uri.appendPath() here would percent-encode that leading slash to "%2F" and
            // the server would return an error instead of the APK.
            val url = repo.address.removeSuffix("/") + "/" + pkg.apk.name.removePrefix("/")
            val result = withContext(Dispatchers.IO) {
                val partialFile = Cache.getPartialReleaseFile(context, cacheFileName)
                // Sliding-window speed estimate + throttled UI updates (the callback fires
                // very frequently; we only push a new state a few times per second).
                var windowStart = SystemClock.elapsedRealtime()
                var windowStartBytes = 0L
                var speed = 0L
                var lastEmit = 0L
                val response = downloader.downloadToFile(
                    url = url,
                    target = partialFile,
                    headers = {
                        repo.authentication?.let { authentication(it.username, it.password) }
                    },
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
                        _downloadStatus.value =
                            DownloadStatus(read.value, total?.value ?: -1L, speed)
                    }
                }
                if (response !is NetworkResponse.Success) {
                    partialFile.delete()
                    return@withContext DownloadResult.Failed("Download failed: ${response.describe()}")
                }
                // Integrity gate: the index is fetched + signature-verified during sync, so its
                // hash is trusted. Only install if the downloaded APK matches it.
                if (!partialFile.sha256Hex().equals(pkg.apk.hash, ignoreCase = true)) {
                    partialFile.delete()
                    return@withContext DownloadResult.Failed("APK verification failed (hash mismatch)")
                }
                partialFile.copyTo(Cache.getReleaseFile(context, cacheFileName), overwrite = true)
                partialFile.delete()
                DownloadResult.Ready
            }
            when (result) {
                DownloadResult.Ready -> installManager.install(packageName installFrom cacheFileName)
                is DownloadResult.Failed -> {
                    Log.w(TAG, "${result.message} (url=$url)")
                    toast(result.message)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Install failed for $packageName", e)
            toast("Install failed: ${e.message}")
        } finally {
            _downloadStatus.value = null
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

private const val TAG = "AppDetailViewModel"

/** How often the download speed is recomputed (sliding window length). */
private const val SPEED_WINDOW_MS = 500L

/** Minimum delay between progress UI updates, to avoid flooding recompositions. */
private const val EMIT_INTERVAL_MS = 150L

/** Outcome of [AppDetailViewModel.downloadAndInstall]'s download + verification step. */
private sealed interface DownloadResult {
    object Ready : DownloadResult
    data class Failed(val message: String) : DownloadResult
}

/** Short, human-readable summary of a network response for toasts and logs. */
private fun NetworkResponse.describe(): String = when (this) {
    is NetworkResponse.Success -> "HTTP $statusCode"
    is NetworkResponse.Error.Http -> "HTTP $statusCode"
    is NetworkResponse.Error.ConnectionTimeout -> "connection timeout"
    is NetworkResponse.Error.SocketTimeout -> "socket timeout"
    is NetworkResponse.Error.IO -> "IO error: ${exception.message}"
    is NetworkResponse.Error.Unknown -> "unknown error: ${exception.message}"
}

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Snapshot of an in-progress APK download.
 *
 * @param read bytes downloaded so far
 * @param total total size in bytes, or -1 when the server didn't report one
 * @param bytesPerSecond current download speed estimate (0 until the first measurement)
 */
data class DownloadStatus(
    val read: Long,
    val total: Long,
    val bytesPerSecond: Long,
) {
    /** Whether the total size is known (determinate vs indeterminate progress bar). */
    val hasTotal: Boolean get() = total > 0

    /** Progress as 0f..1f, or null when the total size is unknown. */
    val fraction: Float? get() = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null

    /** Downloaded amount, e.g. "12.3 MB". */
    val readLabel: String get() = DataSize(read).toString()

    /** Total amount, e.g. "45.6 MB". */
    val totalLabel: String get() = DataSize(total).toString()

    /** Speed, e.g. "2.3 MB/s", or null before the first measurement. */
    val speedLabel: String? get() = if (bytesPerSecond > 0) "${DataSize(bytesPerSecond)}/s" else null

    /** Percentage 0..100, or -1 when the total size is unknown. */
    val percent: Int get() = read percentBy total.takeIf { it > 0 }
}

sealed interface AppDetailState {
    object Loading : AppDetailState
    data class Error(val message: String) : AppDetailState
    data class Success(
        val app: App,
        val packages: List<Pair<Package, Repo>>,
    ) : AppDetailState
}
