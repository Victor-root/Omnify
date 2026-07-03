package com.looker.droidify.compose.appDetail

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.R
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.App
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.datastore.CustomButtonRepository
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.installers.shizuku.ShizukuState
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.installer.model.installFrom
import com.looker.droidify.network.DataSize
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.network.percentBy
import com.looker.droidify.datastore.model.TranslationEngine
import com.looker.droidify.translation.TranslationManager
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.utility.common.extension.calculateHash
import com.looker.droidify.utility.common.extension.getPackageArchiveInfoCompat
import com.looker.droidify.utility.common.extension.singleSignature
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val installedRepository: InstalledRepository,
    private val downloader: Downloader,
    private val translationManager: TranslationManager,
    @param:ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** State of the description "Translate" toggle. */
    private val _descriptionTranslation =
        MutableStateFlow<DescriptionTranslation>(DescriptionTranslation.Original)
    val descriptionTranslation: StateFlow<DescriptionTranslation> = _descriptionTranslation

    /** Whether the user picked a translation engine. The Translate button is hidden when off. */
    val translationEnabled: StateFlow<Boolean> = settingsRepository.data
        .map { it.translationEngine != TranslationEngine.NONE }
        .asStateFlow(false)

    /** Translates the summary + description (HTML) + what's-new into the device language. Never throws. */
    fun translateDescription(summary: String, descriptionHtml: String, whatsNew: String) {
        if (summary.isBlank() && descriptionHtml.isBlank() && whatsNew.isBlank()) return
        viewModelScope.launch { translateBoth(summary, descriptionHtml, whatsNew, notifyError = true) }
    }

    /** When the auto-translate setting is on, translates on screen entry — but only if the description
     *  is actually in another language (detected on-device), so a description already in the user's
     *  language is left alone. */
    fun maybeAutoTranslate(summary: String, descriptionHtml: String, whatsNew: String) {
        if (_descriptionTranslation.value != DescriptionTranslation.Original) return
        if (descriptionHtml.isBlank()) return
        viewModelScope.launch {
            val settings = settingsRepository.getInitial()
            if (settings.translationEngine == TranslationEngine.NONE) return@launch
            if (!settings.autoTranslate) return@launch
            val target = java.util.Locale.getDefault().language
            val detected = runCatching {
                translationManager.detectLanguage(plainText(descriptionHtml))
            }.getOrNull()
            if (detected != null && detected != "und" && detected != target) {
                translateBoth(summary, descriptionHtml, whatsNew, notifyError = false)
            }
        }
    }

    private suspend fun translateBoth(
        summary: String,
        descriptionHtml: String,
        whatsNew: String,
        notifyError: Boolean,
    ) {
        _descriptionTranslation.value = DescriptionTranslation.Loading
        val target = java.util.Locale.getDefault().language
        val description = plainText(descriptionHtml)
        val result = runCatching {
            coroutineScope {
                val translatedSummary = async {
                    if (summary.isBlank()) "" else translationManager.translate(summary, target)
                }
                val translatedDescription = async {
                    if (description.isBlank()) "" else translationManager.translate(description, target)
                }
                val translatedWhatsNew = async {
                    if (whatsNew.isBlank()) "" else translationManager.translate(whatsNew, target)
                }
                DescriptionTranslation.Translated(
                    summary = translatedSummary.await(),
                    description = translatedDescription.await(),
                    whatsNew = translatedWhatsNew.await(),
                )
            }
        }
        _descriptionTranslation.value = result.getOrElse {
            if (notifyError) {
                Toast.makeText(context, R.string.translation_failed, Toast.LENGTH_SHORT).show()
            }
            DescriptionTranslation.Failed
        }
    }

    /** Strips the description's HTML to plain text for translation/detection. */
    private fun plainText(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

    fun showOriginalDescription() {
        _descriptionTranslation.value = DescriptionTranslation.Original
    }

    val packageName: String = requireNotNull(savedStateHandle["packageName"]) {
        "Required argument 'packageName' was not found in SavedStateHandle"
    }

    val customButtons: StateFlow<List<CustomButton>> = customButtonRepository.buttons
        .asStateFlow(emptyList())

    /** Current install state of this package (null when nothing is in progress). */
    val installState: StateFlow<InstallState?> = installManager.state
        .map { it[PackageName(packageName)] }
        .asStateFlow(null)

    /** Bumped to force [installedInfo] to re-read the package manager — notably on screen resume, since
     *  a system uninstall isn't always reported through [installManager]'s state alone. */
    private val installedRefresh = MutableStateFlow(0)

    fun refreshInstalled() {
        installedRefresh.value++
    }

    /**
     * The real installed version + where it was installed from, or null when not installed. Read
     * from the package manager (re-read on any install/uninstall, and on resume via [installedRefresh])
     * so the detail screen shows what's actually on the device — e.g. a fork installed over the upstream
     * package keeps its own version.
     */
    val installedInfo: StateFlow<InstalledInfo?> =
        combine(
            installManager.state,
            installedRefresh,
            // Authoritative package-change signal for this package (kept up to date by
            // InstalledAppReceiver), so an uninstall from this screen flips the button to Install
            // without waiting for a resume or hitting a resume-timing race.
            installedRepository.getStream(packageName),
        ) { _, _, _ -> }
            .map { readInstalledInfo() }
            .flowOn(Dispatchers.Default)
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

    /**
     * Set when the freshly-downloaded APK is signed by a different key than the copy already
     * installed on the device. Android can't update across signers, so the UI shows a dialog asking
     * the user to uninstall the existing app first, instead of firing a doomed system install.
     */
    private val _signatureConflict = MutableStateFlow<SignatureConflict?>(null)
    val signatureConflict: StateFlow<SignatureConflict?> = _signatureConflict

    fun dismissSignatureConflict() {
        _signatureConflict.value = null
    }

    private var downloadJob: Job? = null

    /** Repo the user picked to install from via the version tabs, or null to auto-pick across every
     *  repo that offers this app. */
    private val _preferredRepoId = MutableStateFlow<Int?>(null)

    /** Choose which repo the install/update action pulls from when several offer this app. */
    fun setPreferredRepo(repoId: Int) {
        _preferredRepoId.value = repoId
    }

    val state: StateFlow<AppDetailState> = appRepository
        .getApp(PackageName(packageName))
        .map { apps ->
            when {
                apps.isEmpty() -> AppDetailState.Error("No app found for $packageName")
                else -> {
                    val packages = apps.flatMap {
                        val repo = repoRepository.getRepo(it.repoId.toInt())
                        if (repo != null && it.packages != null) {
                            it.packages.map { pkg -> pkg to repo }
                        } else {
                            emptyList()
                        }
                    }.sortedByDescending { (pkg, _) -> pkg.manifest.versionCode }
                    // The same app can come from several repos (e.g. F-Droid + IzzyOnDroid) that ship
                    // different newest versions. apps.first() is just one of them, and its suggested code
                    // is only that repo's own max — so if the older repo wins, Install/Update would cap
                    // there and ignore a newer build elsewhere. Use the newest version across all repos as
                    // the suggested version, so the whole screen offers the most recent build wherever it
                    // lives (device-compatibility is still applied later by selectForDevice).
                    val base = apps.first()
                    val newest = packages.firstOrNull()?.first?.manifest
                    val app = if (newest != null) {
                        base.copy(
                            metadata = base.metadata.copy(
                                suggestedVersionCode = newest.versionCode,
                                suggestedVersionName = newest.versionName,
                            ),
                        )
                    } else {
                        base
                    }
                    AppDetailState.Success(app = app, packages = packages)
                }
            }
        }
        .onStart { emit(AppDetailState.Loading) }
        // The map above resolves each repo and rebuilds the package list; keep that off the main
        // thread so opening a detail page (and the Room re-emissions during a sync) never ANRs.
        .flowOn(Dispatchers.Default)
        .asStateFlow(AppDetailState.Loading)

    /**
     * Locale codes the app is translated into (its supported languages), for the detail screen's
     * "supported languages" section. When the app is installed we read the *actual* UI languages from
     * its APK resources (the truth: which res/values-XX it ships); that's what the user sees in the app.
     * Otherwise we fall back to the F-Droid store-listing translations from the index — an approximation,
     * since an app can ship a translated UI without a translated store listing (and vice versa).
     */
    val supportedLanguages: StateFlow<SupportedLanguages> = combine(
        state.map { (it as? AppDetailState.Success)?.app?.appId }.distinctUntilChanged(),
        installedInfo,
    ) { appId, installed ->
        val apkLocales = if (installed != null) installedApkLocales() else emptyList()
        when {
            // Installed: the real UI languages from the APK -> reliable, so the status can be definite.
            apkLocales.isNotEmpty() -> SupportedLanguages(apkLocales, fromInstalledApk = true)
            // Not installed: only the store-listing translations, which may differ from the app's UI ->
            // present as approximate so we never wrongly claim a language isn't translated.
            appId != null -> SupportedLanguages(appRepository.supportedLocales(appId), fromInstalledApk = false)
            else -> SupportedLanguages(emptyList(), fromInstalledApk = false)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(SupportedLanguages())

    /** The locale codes the installed APK actually ships resources for (its real UI languages), read
     *  from the package's AssetManager. Empty if it can't be read. */
    private fun installedApkLocales(): List<String> = runCatching {
        context.packageManager.getResourcesForApplication(packageName)
            .assets.locales
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

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
        // When several repos offer this app and the user picked one via the version tabs, install that
        // repo's best release; otherwise pick across all repos. Either way, [selectForDevice] picks the
        // release that actually runs on this device's CPU/SDK (installing e.g. the arm64 VLC APK on an
        // x86 device fails with NO_MATCHING_ABIS). Fall back to the full set if the chosen repo has
        // nothing installable here.
        val preferredRepoId = _preferredRepoId.value
        val pool = if (preferredRepoId == null) {
            current.packages
        } else {
            current.packages.filter { it.second.id == preferredRepoId }
        }
        val target = pool.selectForDevice(current.app.metadata.suggestedVersionCode)
            ?: current.packages.selectForDevice(current.app.metadata.suggestedVersionCode)
        if (target == null) {
            toast("No version of this app is compatible with your device")
            return
        }
        downloadJob = viewModelScope.launch { downloadAndInstall(target.first, target.second) }
    }

    /** Downloads and installs a specific release the user picked from the versions list (verifying its
     *  hash), instead of the auto-selected best one. */
    fun installVersion(pkg: Package, repo: Repo) {
        if (_downloadStatus.value != null) return
        downloadJob = viewModelScope.launch { downloadAndInstall(pkg, repo) }
    }

    private suspend fun downloadAndInstall(pkg: Package, repo: Repo) {
        // Fail fast before downloading: if the Shizuku installer is selected but not usable, tell the
        // user why instead of downloading an APK that could never be installed.
        ShizukuState.installBlockReason(context, settingsRepository.getInitial().installerType)?.let {
            toast(context.getString(it))
            return
        }
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
                // Reuse an already-downloaded, hash-verified APK instead of fetching it again — e.g.
                // after the user uninstalled a differently-signed copy and tapped install a second
                // time. The cache file is keyed by the APK hash, so a different version can't be
                // mistaken for this one.
                val cachedRelease = Cache.getReleaseFile(context, cacheFileName)
                if (cachedRelease.exists() &&
                    cachedRelease.sha256Hex().equals(pkg.apk.hash, ignoreCase = true)
                ) {
                    return@withContext DownloadResult.Ready
                }
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
                // Signature gate: the index declares which signing key each release is signed with.
                // Refuse an APK whose actual signer isn't one the index expects — the old Droidify did
                // this and the Compose rewrite had dropped it. Only block on a *proven* mismatch (the
                // index declares a signer, the APK has a single readable signer, and it differs), and
                // honour the "ignore signatures" setting as an escape hatch.
                val expectedSigners = pkg.manifest.signer
                if (expectedSigners.isNotEmpty() && !settingsRepository.get { ignoreSignature }.first()) {
                    val apkSigner = apkSignerHash(partialFile)
                    if (apkSigner != null && expectedSigners.none { it.equals(apkSigner, ignoreCase = true) }) {
                        Log.w(TAG, "Signer mismatch for $packageName: apk=$apkSigner expected=$expectedSigners")
                        partialFile.delete()
                        return@withContext DownloadResult.Failed(
                            context.getString(R.string.invalid_signature_error_DESC),
                        )
                    }
                }
                partialFile.copyTo(Cache.getReleaseFile(context, cacheFileName), overwrite = true)
                partialFile.delete()
                DownloadResult.Ready
            }
            when (result) {
                DownloadResult.Ready -> {
                    val releaseFile = Cache.getReleaseFile(context, cacheFileName)
                    if (installedWithDifferentSignature(packageName, releaseFile)) {
                        // Different signer: Android can't update across keys. The detail screen shows a
                        // dialog — offering to uninstall the existing copy first, unless it's a system
                        // app, which can't be removed (so there's nothing the user can do, and we must
                        // not keep telling them to uninstall in a loop).
                        _signatureConflict.value =
                            SignatureConflict(isSystemApp = isSystemApp(packageName))
                    } else {
                        installManager.install(packageName installFrom cacheFileName)
                    }
                }
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

    /**
     * The downloaded APK's signing-certificate fingerprint (lowercase SHA-256 hex — the same format
     * the index stores in [Package.manifest] signer), or null if it can't be read (e.g. the APK has
     * multiple signers, which we don't try to match against the index).
     */
    private fun apkSignerHash(apkFile: File): String? =
        context.packageManager
            .getPackageArchiveInfoCompat(apkFile.absolutePath)
            ?.singleSignature
            ?.calculateHash()

    /**
     * True when [packageName] is already installed but signed by a different key than [apkFile].
     * Android refuses to update an app across signers (INSTALL_FAILED_UPDATE_INCOMPATIBLE), so we
     * detect it here rather than letting the system installer fail. Returns false when the app isn't
     * installed or when either set of signatures can't be read (don't block on uncertainty).
     */
    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun installedWithDifferentSignature(packageName: String, apkFile: File): Boolean {
        val pm = context.packageManager
        val installedSignatures = signaturesOf { flags ->
            runCatching { pm.getPackageInfo(packageName, flags) }.getOrNull()
        }
        if (installedSignatures.isEmpty()) return false
        val apkSignatures = signaturesOf { flags ->
            runCatching { pm.getPackageArchiveInfo(apkFile.absolutePath, flags) }.getOrNull()
        }
        if (apkSignatures.isEmpty()) return false
        return installedSignatures.intersect(apkSignatures).isEmpty()
    }

    /** Signing certificates of a package, as hex strings, using the right API for the SDK level. */
    @Suppress("DEPRECATION", "PackageManagerGetSignatures", "NewApi")
    private fun signaturesOf(getInfo: (flags: Int) -> PackageInfo?): Set<String> {
        val usesNewApi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val flags = if (usesNewApi) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = getInfo(flags) ?: return emptySet()
        val signatures = if (usesNewApi) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.mapNotNull { it?.toCharsString() }?.toSet().orEmpty()
    }

    /** True when [packageName] is a system app (or an update to one). Those can't be uninstalled, so a
     *  differently-signed catalogue version can never replace them — there's no point offering it. */
    private fun isSystemApp(packageName: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    private fun readInstalledInfo(): InstalledInfo? {
        val info = runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.getOrNull() ?: return null
        return InstalledInfo(
            version = info.versionName.orEmpty(),
            source = installerSourceLabel(),
        )
    }

    /** Friendly name of the app that installed this package (Play, F-Droid, this app…), the raw
     *  installer id, or a generic label for a sideloaded app with no recorded installer. */
    private fun installerSourceLabel(): String {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageName)
            }
        }.getOrNull()
        return when (installer) {
            null, "" -> context.getString(R.string.installer_unknown)
            "com.android.vending" -> "Google Play"
            "org.fdroid.fdroid", "org.fdroid.basic" -> "F-Droid"
            context.packageName -> context.getString(R.string.application_name)
            else -> installer
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

/** The version of this app currently installed on the device, and where it was installed from. */
data class InstalledInfo(
    val version: String,
    val source: String,
)

/** A blocked update because the installed app is signed by a different key. [isSystemApp] means it
 *  can't be uninstalled, so the update can never be applied — the dialog says so instead of looping. */
data class SignatureConflict(
    val isSystemApp: Boolean,
)

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

/**
 * The app's supported languages for the detail screen. [fromInstalledApk] is true when [codes] are the
 * real UI locales read from the installed APK (reliable); false when they're the F-Droid store-listing
 * translations (an approximation, shown only until the app is installed).
 */
data class SupportedLanguages(
    val codes: List<String> = emptyList(),
    val fromInstalledApk: Boolean = false,
)
