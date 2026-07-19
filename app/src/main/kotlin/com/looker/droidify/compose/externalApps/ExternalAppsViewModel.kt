package com.looker.droidify.compose.externalApps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.appDetail.SignatureConflict
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.SupportedLanguages
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.data.signerMismatch
import com.looker.droidify.external.ExternalAccount
import com.looker.droidify.external.ExternalApi
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.ExternalAccountRef
import com.looker.droidify.external.Release
import com.looker.droidify.external.ReleaseLookup
import com.looker.droidify.external.RepoRef
import com.looker.droidify.external.apkDownloadUrl
import com.looker.droidify.external.apkFileName
import com.looker.droidify.external.apkFileSize
import com.looker.droidify.external.apkVersionToken
import com.looker.droidify.external.SourceProvider
import com.looker.droidify.external.parseAccountSource
import com.looker.droidify.external.parseExternalSource
import com.looker.droidify.external.selectApkAsset
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.installers.shizuku.ShizukuState
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.TranslationEngine
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.translation.TranslationManager
import com.looker.droidify.utility.apk.ApkBinaryManifest
import com.looker.droidify.utility.apk.ApkSigningBlockReader
import com.looker.droidify.utility.apk.RemoteApkLocaleReader
import com.looker.droidify.utility.apk.RemoteApkManifestReader
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.utility.common.extension.calculateHash
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.installedWithDifferentSignature
import com.looker.droidify.utility.common.extension.installerSourceLabel
import com.looker.droidify.utility.common.extension.singleSignature
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ExternalAppsViewModel @Inject constructor(
    private val externalApi: ExternalApi,
    private val repository: ExternalAppRepository,
    private val downloader: Downloader,
    private val installManager: InstallManager,
    private val translationManager: TranslationManager,
    private val settingsRepository: SettingsRepository,
    private val installedRepository: InstalledRepository,
    // The F-Droid catalogue's repository, reused here only for its generic (provider-agnostic)
    // APK-locale cache — see loadSupportedLanguages().
    private val appRepository: AppRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val apps: StateFlow<List<ExternalApp>> = repository.apps.asStateFlow(emptyList())

    /** Whether the user picked a translation engine. The Translate button is hidden when off. */
    val translationEnabled: StateFlow<Boolean> = settingsRepository.data
        .map { it.translationEngine != TranslationEngine.NONE }
        .asStateFlow(false)

    /** Whether a GitHub token is configured. When false, the add-source/add-account dialogs show a hint
     *  that the anonymous 60-requests/hour limit applies (see [githubRateLimitRemaining]); a token
     *  raises it to 5000, so the hint is unnecessary once one is set. */
    val hasGithubToken: StateFlow<Boolean> = settingsRepository.data
        .map { it.githubToken.trim().isNotEmpty() }
        .asStateFlow(false)

    /** Remaining anonymous GitHub API quota for the current hour, once known (see
     *  [com.looker.droidify.external.ExternalApi.rateLimitRemaining]). Lets the add dialogs' hint become
     *  concrete ("N requests left") instead of only ever repeating the generic "60/hour" figure. */
    val githubRateLimitRemaining: StateFlow<Int?> = externalApi.rateLimitRemaining

    /** Whether the README WebView on the external detail screen may run embedded JavaScript. On by
     *  default; the Settings › External sources toggle lets a user turn it off. */
    val readmeJavaScriptEnabled: StateFlow<Boolean> = settingsRepository.data
        .map { it.readmeJavaScriptEnabled }
        .asStateFlow(true)

    /** Whether the tablet-landscape two-pane detail layout is allowed at all (the Settings toggle) — a
     *  screen still only actually shows it when it's also tablet-width and landscape. */
    val splitViewEnabled: StateFlow<Boolean> = settingsRepository.data
        .map { it.splitViewEnabled }
        .asStateFlow(true)

    /** Tracked whole-account sources (each expands to several entries in [apps]). */
    val accounts: StateFlow<List<ExternalAccount>> = repository.accounts.asStateFlow(emptyList())

    /** Account keys whose discovery is currently running, so the watcher below never launches a second
     *  scan for the same account. */
    private val scanningAccounts = MutableStateFlow<Set<String>>(emptySet())

    init {
        // Discover the apps of any enabled account that has never been scanned (lastScan == 0) as soon
        // as it appears (one added/enabled by the user) without waiting for the throttled refresh, so
        // its apps show up promptly. A manually added account is already scanned at add time; a disabled
        // account (e.g. the opt-in account seeded on first run) is left inert until the user enables it.
        viewModelScope.launch {
            repository.accounts.collect { list ->
                list.forEach { account ->
                    if (!account.enabled ||
                        account.lastScan != 0L ||
                        account.key in scanningAccounts.value
                    ) {
                        return@forEach
                    }
                    scanningAccounts.update { it + account.key }
                    launch {
                        try {
                            rescanAccountNow(account)
                        } finally {
                            scanningAccounts.update { it - account.key }
                        }
                    }
                }
            }
        }
    }

    /** Bumped to re-query the package manager (e.g. when the screen is reopened). */
    private val installedRefresh = MutableStateFlow(0)

    // Emits on any install/uninstall on the device (kept up to date by InstalledAppReceiver). Using it
    // as a trigger makes install-state react to the authoritative package-change broadcast, so an
    // uninstall from this very screen updates the button without a resume-timing race.
    private val installedChanges = installedRepository.getAllStream()

    /** Per-app real installed versionName (read from the package manager), keyed by
     *  [ExternalApp.key] — so the detail shows the version actually on the device vs. the repo's. */
    val installedVersions: StateFlow<Map<String, String>> = combine(
        repository.apps,
        installManager.state,
        installedRefresh,
        installedChanges,
    ) { apps, _, _, _ ->
        apps.mapNotNull { app ->
            val pkg = app.packageName ?: return@mapNotNull null
            val version = installedVersionName(pkg) ?: return@mapNotNull null
            app.key to version
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyMap())

    /** Where each installed tracked app actually came from (Play, F-Droid, this app…), keyed by
     *  [ExternalApp.key] — shown next to the installed version so a mismatch with what Omnify expects
     *  (e.g. a copy installed by another client, which can't be updated across signing keys in place)
     *  is visible instead of silently offering "Launch" with no explanation. Derived from
     *  [installedVersions] so a key only ever appears here once it's confirmed installed. */
    val installSources: StateFlow<Map<String, String>> = combine(
        installedVersions,
        repository.apps,
    ) { versions, apps ->
        val byKey = apps.associateBy { it.key }
        versions.keys.mapNotNull { key ->
            val pkg = byKey[key]?.packageName ?: return@mapNotNull null
            key to context.installerSourceLabel(pkg)
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyMap())

    /**
     * Keys ([ExternalApp.key]) whose installed app's real signing certificate doesn't match any signer
     * declared by the latest known release's own APK — read via [ApkSigningBlockReader], a cheap HTTP
     * range read, never a full download. A package name alone isn't proof of identity: Android lets a
     * completely different app claim the same package name a tracked source uses (a de-Googled fork
     * sharing an app's real package id, say) as long as it got there first — the same collision risk
     * [com.looker.droidify.data.local.model.toPackages] documents for the F-Droid catalogue, just without
     * an index to cross-check against ahead of time here. A key simply absent from this set (rather than
     * present with false) means either there's nothing to compare yet or the check hasn't completed —
     * treat "absent" the same as "no mismatch", never block on it. See [trackSignatureMismatches].
     */
    private val _signatureMismatches = MutableStateFlow<Set<String>>(emptySet())
    val signatureMismatches: StateFlow<Set<String>> = _signatureMismatches

    /** Keys of tracked apps that are currently installed on the device *and* really are that tracked
     *  app (not a different one that merely reused its package name — see [signatureMismatches]).
     *  Derived from [installedVersions] itself (rather than its own independent package-manager scan)
     *  so the two can never disagree — a key can't appear "installed" here without an entry there,
     *  which used to be possible for a moment since each was its own independently re-subscribed
     *  StateFlow and could refresh out of step. Drives the Explorer/account grid tiles' "installed"
     *  badge, the same signer check the detail screen's own primary-action button applies locally. */
    val installedKeys: StateFlow<Set<String>> = combine(
        installedVersions,
        signatureMismatches,
    ) { versions, mismatches -> versions.keys - mismatches }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptySet())

    /** In-memory cache of [ApkSigningBlockReader] results, keyed by APK URL — a source's latestApkUrl
     *  rarely changes between refreshes, so this avoids re-reading the signing block over the network
     *  every time the installed set is merely re-evaluated (e.g. on screen resume). Not persisted:
     *  losing it on process death just means the next check re-reads once, the same cost as the very
     *  first check ever. */
    private val signerHashCache = mutableMapOf<String, Set<String>?>()

    init {
        viewModelScope.launch { trackSignatureMismatches() }
    }

    /**
     * Keeps [signatureMismatches] current: for every tracked app that's both installed and has a known
     * latest-release APK URL, compares the installed signing certificate (read live from the package
     * manager, same as everywhere else this comparison is made) against [ApkSigningBlockReader]'s
     * reading of that URL. Runs sequentially and in the background — this is a bonus safety signal, not
     * something any button waits on — and never flags a mismatch when either side can't be determined
     * (don't warn on uncertainty, same philosophy as the F-Droid catalogue's own version of this check).
     */
    private suspend fun trackSignatureMismatches() {
        combine(repository.apps, installedVersions) { apps, installed -> apps to installed }
            .distinctUntilChanged()
            .collectLatest { (apps, installed) ->
                val candidates = apps.filter { it.key in installed.keys && it.latestApkUrl != null }
                if (candidates.isEmpty()) {
                    _signatureMismatches.value = emptySet()
                    return@collectLatest
                }
                val mismatches = mutableSetOf<String>()
                candidates.forEach { app ->
                    val pkg = app.packageName ?: return@forEach
                    val apkUrl = app.latestApkUrl ?: return@forEach
                    val installedSigner = context.packageManager
                        .getPackageInfoCompat(pkg)
                        ?.singleSignature
                        ?.calculateHash()
                        ?.lowercase()
                        ?: return@forEach
                    // Deliberately not signerHashCache.getOrPut(apkUrl) { ... }: getOrPut only treats a
                    // *missing* key as a cache miss, but a failed read is cached as a null *value* under
                    // an already-present key — indistinguishable from "missing" to getOrPut, so it would
                    // silently re-fetch over the network on every single re-evaluation instead of caching
                    // the failure once, defeating the whole point of this cache for exactly the hosts most
                    // likely to need it (ones that don't support range requests at all).
                    val expectedSigners = if (signerHashCache.containsKey(apkUrl)) {
                        signerHashCache.getValue(apkUrl)
                    } else {
                        ApkSigningBlockReader.fetchSignerHashes(downloader, apkUrl).also {
                            signerHashCache[apkUrl] = it
                        }
                    } ?: return@forEach
                    // signerMismatch: the one shared definition of this comparison (see
                    // InstalledIdentityRepository) — same rule as the catalogue side, different source
                    // for the expected signers (the release APK's own signing block; there's no index
                    // declaring them ahead of time here).
                    if (signerMismatch(installedSigner, expectedSigners)) {
                        mismatches += app.key
                    }
                }
                _signatureMismatches.value = mismatches
            }
    }

    /** Keys of tracked apps the user has favourited — the same store the F-Droid catalogue's own
     *  favourites use ([SettingsRepository.toggleFavourites]/`favouriteApps`), keyed by [ExternalApp.key]
     *  instead of a package name so a source can be favourited before it's even installed (unlike a
     *  package name, [ExternalApp.key] never collides with a real Android package id). */
    val favourites: StateFlow<Set<String>> = settingsRepository.get { favouriteApps }.asStateFlow(emptySet())

    /** Adds or removes [app] from the user's favourites. */
    fun toggleFavourite(app: ExternalApp) {
        viewModelScope.launch { settingsRepository.toggleFavourites(app.key) }
    }

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

    /** Live download progress per app (drives the per-card progress bar). */
    private val _downloads = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadStatus>> = _downloads

    /**
     * Release tag (app.key -> tag) that [downloads]/[installStates] currently applies to for that app —
     * set the moment a download starts and left alone afterwards (there's always at most one
     * download/install per app in flight, guarded below, so a stale entry is harmless: it's only ever
     * read together with that app's download/install actually being active). Lets the version list show
     * progress on the specific row the user tapped instead of only in the hero card, which stays out of
     * view once the user has scrolled down to the list.
     */
    private val _downloadTargetTag = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadTargetTag: StateFlow<Map<String, String>> = _downloadTargetTag

    /** Keys with a non-download network op in flight (add / update check). */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy

    /** Set when the freshly-downloaded APK is signed by a different key than the copy already
     *  installed on the device — same conflict, same dialog, as the F-Droid catalogue's own
     *  [com.looker.droidify.compose.appDetail.AppDetailViewModel.signatureConflict]. Android can't
     *  update across signers, so the UI asks the user to uninstall the existing app first instead of
     *  firing a doomed system install (which would otherwise leave the tracked record silently
     *  claiming a version that was never actually applied). */
    private val _signatureConflict = MutableStateFlow<SignatureConflict?>(null)
    val signatureConflict: StateFlow<SignatureConflict?> = _signatureConflict

    fun dismissSignatureConflict() {
        _signatureConflict.value = null
    }

    /** True when [packageName] is a system app (or an update to one). Those can't be uninstalled, so a
     *  differently-signed release can never replace them — there's no point offering to. */
    private fun isSystemApp(packageName: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    /** Drives the Add-source dialog: it stays open with a spinner while the (network) add runs, then
     *  closes itself on success. */
    private val _addState = MutableStateFlow(AddSourceState.IDLE)
    val addState: StateFlow<AddSourceState> = _addState

    /** Error from the last add attempt, shown *inside* the Add dialog (a snackbar would be hidden behind
     *  the dialog's scrim). Null when there's nothing to show. */
    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError

    /** Acknowledge a finished add so the dialog state resets (called once the dialog has closed). If the
     *  dialog was dismissed while still adding, cancel the in-flight work so a late success can't leave a
     *  stale state that would auto-close the next dialog. */
    fun consumeAddState() {
        if (_addState.value == AddSourceState.LOADING) addJob?.cancel()
        _addState.value = AddSourceState.IDLE
        _addError.value = null
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

    /** Set once loading the README has genuinely failed (nothing cached, nothing fresh), so the screen
     *  can explain why instead of spinning forever — most often the GitHub anonymous rate limit, which a
     *  token in Settings lifts. Null while still loading or once a README is showing. */
    private val _readmeError = MutableStateFlow<String?>(null)
    val readmeError: StateFlow<String?> = _readmeError

    /** State of the README "Translate" toggle on the external detail screen. */
    private val _readmeTranslation =
        MutableStateFlow<DescriptionTranslation>(DescriptionTranslation.Original)
    val readmeTranslation: StateFlow<DescriptionTranslation> = _readmeTranslation

    /** Recent releases of the app shown on the detail screen, for the "choose a version to install"
     *  list — the external-app equivalent of the F-Droid catalogue's version list. Null while loading;
     *  empty once loaded if the source has no installable release. */
    private val _releaseHistory = MutableStateFlow<List<Release>?>(null)
    val releaseHistory: StateFlow<List<Release>?> = _releaseHistory

    /** Per-app (elapsedRealtime fetched-at, releases) — an in-memory cache so re-opening the same app's
     *  detail screen doesn't burn a fresh GitHub API call every time (mirrors [ReadmeCache]'s freshness
     *  window, just kept in memory since a stale version list is harmless to lose on process death). */
    private val releaseHistoryCache = mutableMapOf<String, Pair<Long, List<Release>>>()

    fun loadReleaseHistory(app: ExternalApp) {
        val cached = releaseHistoryCache[app.key]
        _releaseHistory.value = cached?.second
        if (cached != null && SystemClock.elapsedRealtime() - cached.first < README_FRESHNESS_MS) return
        viewModelScope.launch {
            val releases = externalApi.releaseHistory(app)
            releaseHistoryCache[app.key] = SystemClock.elapsedRealtime() to releases
            _releaseHistory.value = releases
        }
    }

    /** [ApkBinaryManifest.usesSdk] results, keyed by APK download URL — populated lazily as version rows
     *  become visible (see [loadSdkInfo]), not eagerly for the whole release history at once: unlike a
     *  release's own date/size (already part of the release API response — see [Release.apkFileSize]),
     *  min/target SDK lives inside the APK's own manifest, so getting it costs a dedicated range-request
     *  fetch. A key present with a null value means the fetch genuinely found nothing (GitLab exposes no
     *  size either — see [Release.apkFileSize] — an unsupported host, or an unparseable manifest); a key
     *  absent means it hasn't been requested yet. */
    private val _sdkInfoByApkUrl = MutableStateFlow<Map<String, ApkBinaryManifest.UsesSdk?>>(emptyMap())
    val sdkInfoByApkUrl: StateFlow<Map<String, ApkBinaryManifest.UsesSdk?>> = _sdkInfoByApkUrl

    /** URLs already requested (in flight or done) — checked synchronously before launching a fetch so a
     *  row recomposing while its own fetch is still in flight never starts a second one. */
    private val sdkInfoRequested = mutableSetOf<String>()

    /** Fetches and caches [apkUrl]'s declared min/target SDK; a no-op if already requested (or done).
     *  Called from the version list as each row is shown, one fetch per distinct APK ever. */
    fun loadSdkInfo(apkUrl: String) {
        if (!sdkInfoRequested.add(apkUrl)) return
        viewModelScope.launch {
            val manifest = RemoteApkManifestReader.fetchManifestBytes(downloader, apkUrl)
            val sdk = manifest?.let(ApkBinaryManifest::usesSdk)
            _sdkInfoByApkUrl.update { it + (apkUrl to sdk) }
        }
    }

    /** The detail screen's "Issue tracker" and "Changelog" links, resolved from the provider itself
     *  (an external source has no index metadata to read these from, unlike the F-Droid catalogue).
     *  Null while still checking; once checked, [LinkCheckState.url] is null when the repo genuinely
     *  has no issue tracker / no changelog file, so the screen can say so instead of hiding the row. */
    private val _issueTrackerLink = MutableStateFlow<LinkCheckState?>(null)
    val issueTrackerLink: StateFlow<LinkCheckState?> = _issueTrackerLink
    private val _changelogLink = MutableStateFlow<LinkCheckState?>(null)
    val changelogLink: StateFlow<LinkCheckState?> = _changelogLink

    private val issueTrackerCache = mutableMapOf<String, Pair<Long, String?>>()
    private val changelogCache = mutableMapOf<String, Pair<Long, String?>>()

    fun loadIssueTrackerAndChangelog(app: ExternalApp) {
        val now = SystemClock.elapsedRealtime()
        val cachedIssues = issueTrackerCache[app.key]
        _issueTrackerLink.value = cachedIssues?.let { LinkCheckState(it.second) }
        if (cachedIssues == null || now - cachedIssues.first >= README_FRESHNESS_MS) {
            viewModelScope.launch {
                val url = externalApi.fetchIssueTrackerUrl(app)
                issueTrackerCache[app.key] = SystemClock.elapsedRealtime() to url
                _issueTrackerLink.value = LinkCheckState(url)
            }
        }
        val cachedChangelog = changelogCache[app.key]
        _changelogLink.value = cachedChangelog?.let { LinkCheckState(it.second) }
        if (cachedChangelog == null || now - cachedChangelog.first >= README_FRESHNESS_MS) {
            viewModelScope.launch {
                val url = externalApi.fetchChangelogUrl(app)
                changelogCache[app.key] = SystemClock.elapsedRealtime() to url
                _changelogLink.value = LinkCheckState(url)
            }
        }
    }

    /** The changelog dialog's content: rendered HTML once loaded, an explanatory message if the repo
     *  genuinely has none, or both null while still loading. Reset by [dismissChangelog]. */
    private val _changelogHtml = MutableStateFlow<String?>(null)
    val changelogHtml: StateFlow<String?> = _changelogHtml
    private val _changelogUnavailable = MutableStateFlow(false)
    val changelogUnavailable: StateFlow<Boolean> = _changelogUnavailable

    private val changelogHtmlCache = mutableMapOf<String, Pair<Long, String?>>()

    /** Opens the changelog dialog and loads its content — rendered in-app exactly like the README,
     *  instead of sending the user to the browser and out of the app to read what's new. */
    fun loadChangelogHtml(app: ExternalApp) {
        _changelogHtml.value = null
        _changelogUnavailable.value = false
        val cached = changelogHtmlCache[app.key]
        if (cached != null && SystemClock.elapsedRealtime() - cached.first < README_FRESHNESS_MS) {
            _changelogHtml.value = cached.second
            _changelogUnavailable.value = cached.second == null
            return
        }
        viewModelScope.launch {
            val html = externalApi.fetchChangelogHtml(app)
            changelogHtmlCache[app.key] = SystemClock.elapsedRealtime() to html
            _changelogHtml.value = html
            _changelogUnavailable.value = html == null
        }
    }

    /** Closes the changelog dialog. */
    fun dismissChangelog() {
        _changelogHtml.value = null
        _changelogUnavailable.value = false
    }

    /**
     * The detail screen's "supported languages" section — the same reliable, real-UI-language check
     * as the F-Droid catalogue's (see [com.looker.droidify.compose.appDetail.AppDetailViewModel]), but
     * an external source has no store-listing metadata to fall back to, so this is either a confirmed
     * answer or nothing at all: null while unresolved or if the check genuinely can't be done (no
     * installable release, the source's host doesn't support range requests, ...), in which case the
     * screen simply doesn't show the section rather than showing an unreliable guess.
     */
    private val _supportedLanguages = MutableStateFlow<SupportedLanguages?>(null)
    val supportedLanguages: StateFlow<SupportedLanguages?> = _supportedLanguages

    /** Per-app (elapsedRealtime fetched-at, locales) cache for [ExternalApi.fetchSourceLocales], so
     *  re-opening the same app's detail screen doesn't burn a fresh repo-tree API call every time
     *  (mirrors [issueTrackerCache]'s freshness window). */
    private val sourceLocalesCache = mutableMapOf<String, Pair<Long, List<String>>>()

    fun loadSupportedLanguages(app: ExternalApp, isInstalled: Boolean) {
        _supportedLanguages.value = null
        if (isInstalled) {
            val installedLocales = installedApkLocales(app.packageName)
            if (installedLocales.isNotEmpty()) {
                _supportedLanguages.value = SupportedLanguages(installedLocales, reliable = true)
                return
            }
        }
        viewModelScope.launch {
            // Tried first: the source repo's own res/values-xx/ folders — ground truth independent of
            // whether the release build/download the APK check below relies on behaves (see the comment
            // on ExternalApi.fetchSourceLocales). Falls through to that APK check when the repo doesn't
            // use standard Android resource folders for its translations (or the tree fetch itself fails).
            val cachedSource = sourceLocalesCache[app.key]
            val sourceLocales = if (cachedSource != null &&
                SystemClock.elapsedRealtime() - cachedSource.first < README_FRESHNESS_MS
            ) {
                cachedSource.second
            } else {
                externalApi.fetchSourceLocales(app)?.also {
                    sourceLocalesCache[app.key] = SystemClock.elapsedRealtime() to it
                }
            }
            if (!sourceLocales.isNullOrEmpty()) {
                _supportedLanguages.value = SupportedLanguages(sourceLocales, reliable = true)
                return@launch
            }
            val release = externalApi.latestReleaseFor(app) ?: return@launch
            val asset = selectApkAsset(release.assets, filter = app.apkFilter, releaseTag = release.tag)
                ?: return@launch
            // The asset's own update timestamp/id (already used to detect updates — see
            // ExternalApp.hasUpdate) doubles as a stable cache key for this specific build; falls back
            // to the download URL for providers that expose neither.
            val cacheKey = release.apkVersionToken(filter = app.apkFilter) ?: asset.downloadUrl
            val cached = appRepository.cachedApkLocales(cacheKey)
            if (!cached.isNullOrEmpty()) {
                _supportedLanguages.value = SupportedLanguages(cached, reliable = true)
                return@launch
            }
            val locales = RemoteApkLocaleReader.fetchLocales(downloader, asset.downloadUrl) ?: return@launch
            // An empty result is the one answer this check can't fully trust: unlike a real installed
            // APK (read straight from AssetManager), a *download* coming back with zero locale-specific
            // resource configs can just as easily mean the fetch/parse silently missed something (a CDN
            // that mishandles range requests, a resource-shrunk build, …) as it can mean a genuinely
            // unlocalized app. A false "not translated" is worse than showing nothing, so it's treated as
            // inconclusive here — not cached, not shown — rather than asserted as ground truth.
            if (locales.isEmpty()) return@launch
            appRepository.cacheApkLocales(cacheKey, locales)
            _supportedLanguages.value = SupportedLanguages(locales, reliable = true)
        }
    }

    /** The locale codes the installed APK actually ships resources for (its real UI languages), read
     *  from the package's AssetManager. Empty if [packageName] is null or it can't be read. */
    private fun installedApkLocales(packageName: String?): List<String> = runCatching {
        if (packageName == null) return emptyList()
        context.packageManager.getResourcesForApplication(packageName)
            .assets.locales
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun loadReadme(app: ExternalApp) {
        // A different app's README is about to load, so drop any translation left on the previous one.
        _readmeTranslation.value = DescriptionTranslation.Original
        _readmeError.value = null
        viewModelScope.launch {
            // Show the cached README instantly (if any) so a re-open isn't blocked on the network.
            val cached = withContext(Dispatchers.IO) { ReadmeCache.load(context, app.key) }
            _readme.value = cached
            // A README changes far less often than its detail screen gets opened: once the cache is
            // reasonably fresh, skip the network call entirely instead of refetching identical content
            // on every re-open (this used to burn a request — and, before the GitHub README fetch moved
            // off api.github.com, quota — every single time).
            val isFresh = cached != null &&
                withContext(Dispatchers.IO) { ReadmeCache.isFresh(context, app.key, README_FRESHNESS_MS) }
            if (isFresh) return@launch
            val fresh = externalApi.readmeHtml(app)
            if (fresh != null) {
                _readme.value = fresh
                withContext(Dispatchers.IO) { ReadmeCache.save(context, app.key, fresh) }
            } else if (cached == null) {
                // Nothing to show at all: without this, the screen would spin forever with no hint that
                // the fetch already failed — this is what a rate-limited anonymous GitHub call looks like.
                _readmeError.value = if (externalApi.shouldSuggestGithubToken()) {
                    context.getString(R.string.external_rate_limited)
                } else {
                    context.getString(R.string.external_readme_unavailable)
                }
            }
        }
    }

    /** Translates the README's plain text into the device language. Never throws: on failure it shows a
     *  snackbar and leaves the toggle in the "failed" state (tapping again retries). */
    fun translateReadme(html: String) {
        if (html.isBlank()) return
        viewModelScope.launch {
            _readmeTranslation.value = DescriptionTranslation.Loading
            val target = java.util.Locale.getDefault().language
            val result = runCatching {
                withContext(Dispatchers.Default) { translateHtml(html, target) }
            }
            _readmeTranslation.value = result.fold(
                onSuccess = { DescriptionTranslation.Translated(summary = "", description = it) },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    snack(context.getString(R.string.translation_failed))
                    DescriptionTranslation.Failed
                },
            )
        }
    }

    fun showOriginalReadme() {
        _readmeTranslation.value = DescriptionTranslation.Original
    }

    /** Translates the README while preserving its HTML structure: only the visible text between tags is
     *  translated (code blocks are left alone), so the rendered result keeps the original's images,
     *  headings, lists and links. Any segment that can't be mapped keeps its original text. */
    private suspend fun translateHtml(html: String, target: String): String {
        // Tokenize into tags (kept verbatim) and the text runs between them.
        val tokens = mutableListOf<String>()
        val isTag = mutableListOf<Boolean>()
        var last = 0
        for (match in TAG_REGEX.findAll(html)) {
            if (match.range.first > last) {
                tokens += html.substring(last, match.range.first)
                isTag += false
            }
            tokens += match.value
            isTag += true
            last = match.range.last + 1
        }
        if (last < html.length) {
            tokens += html.substring(last)
            isTag += false
        }

        // Choose the text runs to translate: outside code/pre/script/style and containing a letter.
        val indices = mutableListOf<Int>()
        var skipDepth = 0
        for (i in tokens.indices) {
            if (isTag[i]) {
                val name = tagName(tokens[i])
                if (name != null && name in SKIP_TEXT_TAGS) {
                    when {
                        tokens[i].startsWith("</") -> if (skipDepth > 0) skipDepth--
                        !tokens[i].endsWith("/>") -> skipDepth++
                    }
                }
            } else if (skipDepth == 0 && tokens[i].any(Char::isLetter)) {
                indices += i
            }
        }
        if (indices.isEmpty()) return html

        val translated = translateSegments(indices.map { tokens[it].trim() }, target)

        // Splice the translations back in, keeping each run's surrounding whitespace.
        val builder = StringBuilder(html.length)
        val translatableSet = indices.toHashSet()
        var t = 0
        for (i in tokens.indices) {
            if (i in translatableSet) {
                val original = tokens[i]
                builder.append(original.takeWhile(Char::isWhitespace))
                builder.append(translated[t])
                builder.append(original.takeLastWhile(Char::isWhitespace))
                t++
            } else {
                builder.append(tokens[i])
            }
        }
        return builder.toString()
    }

    /** Translates [segments] in newline-joined batches (each <= [MAX_TRANSLATE_CHUNK] chars), which the
     *  engines return line-for-line. On the rare batch that doesn't map 1:1, its segments are translated
     *  one by one, and any segment that still fails keeps its original text. */
    private suspend fun translateSegments(segments: List<String>, target: String): List<String> {
        val out = arrayOfNulls<String>(segments.size)
        var i = 0
        while (i < segments.size) {
            val start = i
            val batch = StringBuilder()
            while (i < segments.size) {
                val seg = segments[i]
                if (batch.isNotEmpty() && batch.length + 1 + seg.length > MAX_TRANSLATE_CHUNK) break
                if (batch.isNotEmpty()) batch.append('\n')
                batch.append(seg)
                i++
                if (seg.length >= MAX_TRANSLATE_CHUNK) break
            }
            val count = i - start
            val parts = translationManager.translate(batch.toString(), target).split('\n')
            if (parts.size == count) {
                for (j in 0 until count) out[start + j] = parts[j]
            } else {
                for (j in 0 until count) {
                    out[start + j] = runCatching {
                        translationManager.translate(segments[start + j], target)
                    }.getOrDefault(segments[start + j])
                }
            }
        }
        return out.map { it ?: "" }
    }

    /** Launcher-icon candidates found in the source repo, for the icon picker (best first). Empty when
     *  none were found or the provider isn't supported (then the card uses the account avatar). */
    suspend fun loadIconCandidates(app: ExternalApp): List<String> =
        externalApi.fetchIconCandidates(app)

    /** Adds a project from a GitHub, GitLab, Codeberg or self-hosted Gitea/Forgejo URL after
     *  confirming it has a release. */
    fun addSource(
        url: String,
        includePrereleases: Boolean,
        customName: String = "",
        muteUpdates: Boolean = false,
        apkFilter: String = "",
    ) {
        _addError.value = null
        val ref = parseExternalSource(url)
        if (ref == null) {
            _addError.value = context.getString(R.string.external_invalid_url)
            return
        }
        val trimmedName = customName.trim()
        addJob = viewModelScope.launch {
            _addState.value = AddSourceState.LOADING
            var added = false
            try {
                // Known public hosts already carry their provider; any other host is probed to see
                // whether it's a self-hosted Gitea/Forgejo instance.
                val provider = ref.provider ?: when {
                    externalApi.isGiteaInstance(ref.host, ref.owner, ref.repo) -> SourceProvider.CODEBERG
                    else -> null
                }
                if (provider == null) {
                    _addError.value = context.getString(R.string.external_unsupported_host)
                    return@launch
                }
                val app = ExternalApp(
                    provider = provider,
                    host = ref.host,
                    owner = ref.owner,
                    repo = ref.repo,
                    includePrereleases = includePrereleases,
                    muteUpdates = muteUpdates,
                    apkFilter = apkFilter.trim().ifEmpty { null },
                    label = trimmedName.ifEmpty { ref.repo },
                    nameOverridden = trimmedName.isNotEmpty(),
                )
                if (apps.value.any { it.key == app.key }) {
                    _addError.value = context.getString(R.string.external_already_added, app.path)
                    return@launch
                }
                withBusy(app.key) {
                    // A plain null release here used to collapse every failure — the GitHub rate limit,
                    // a repo that only publishes pre-releases (e.g. ReVanced Manager) with the option
                    // off, or genuinely nothing installable — into one misleading "no release" message.
                    // latestReleaseLookup reports which one it actually was.
                    val release = when (val lookup = externalApi.latestReleaseLookup(app)) {
                        is ReleaseLookup.Found -> lookup.release
                        ReleaseLookup.FetchFailed -> {
                            val suggestToken = externalApi.shouldSuggestGithubToken()
                            _addError.value = if (suggestToken) {
                                context.getString(R.string.external_rate_limited)
                            } else {
                                context.getString(R.string.external_no_release, app.path)
                            }
                            return@withBusy
                        }
                        ReleaseLookup.OnlyPrereleasesExcluded -> {
                            _addError.value =
                                context.getString(R.string.external_only_prereleases, app.path)
                            return@withBusy
                        }
                        ReleaseLookup.NoCompatibleApk -> {
                            _addError.value = context.getString(R.string.external_no_release, app.path)
                            return@withBusy
                        }
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
                        else -> packageId?.let { installedLabel(it) } ?: meta?.appName ?: app.label
                    }
                    val addApkSize = release.apkFileSize(filter = app.apkFilter)
                    Log.d(
                        TAG,
                        "addApp ${app.key}: asset=" +
                            "${selectApkAsset(release.assets, filter = app.apkFilter, releaseTag = release.tag)?.name} " +
                            "size=$addApkSize rawAssetSizes=${release.assets.map { it.name to it.size }}",
                    )
                    repository.addApp(
                        app.copy(
                            packageName = packageId,
                            label = resolvedLabel,
                            repoIconUrl = meta?.iconCandidates?.firstOrNull(),
                            // Only mark scanned when the repo was actually read, so a transient failure
                            // re-scans on a later refresh instead of caching an empty / non-TV result.
                            iconChecked = meta != null,
                            supportsTelevision = meta?.supportsTelevision ?: false,
                            tvChecked = meta != null,
                            latestTag = release.tag,
                            latestApkToken = release.apkVersionToken(filter = app.apkFilter),
                            latestApkName = release.apkFileName(filter = app.apkFilter),
                            latestApkSize = addApkSize,
                            latestApkUrl = release.apkDownloadUrl(filter = app.apkFilter),
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

    /**
     * Adds a whole-account source from a pasted account URL (owner only). See [addAccountSource].
     */
    fun addAccount(
        url: String,
        customName: String,
        includeForks: Boolean,
        includePrereleases: Boolean,
        muteUpdates: Boolean,
        apkFilter: String,
    ) {
        val ref = parseAccountSource(url)
        if (ref == null) {
            snack(context.getString(R.string.external_invalid_url))
            return
        }
        addAccountSource(ref, customName, includeForks, includePrereleases, muteUpdates, apkFilter)
    }

    /**
     * Adds a whole-account source: discovers the account's repos that ship an installable APK release
     * and tracks each as its own [ExternalApp] tagged with the account, while the account itself is one
     * row in the sources list. The dialog options ([includeForks]/[includePrereleases]/[muteUpdates]/
     * [apkFilter]) drive the discovery and become the defaults applied to every discovered app;
     * [label] (if any) names the account.
     */
    private fun addAccountSource(
        ref: ExternalAccountRef,
        label: String,
        includeForks: Boolean,
        includePrereleases: Boolean,
        muteUpdates: Boolean,
        apkFilter: String,
    ) {
        val trimmedName = label.trim()
        addJob = viewModelScope.launch {
            _addState.value = AddSourceState.LOADING
            var added = false
            try {
                // Known public hosts carry their provider; for an unknown self-hosted host we don't have
                // a repo to probe, so we try the Gitea/Forgejo then the GitLab account API and keep
                // whichever lists repos.
                val candidates = ref.provider?.let { listOf(it) }
                    ?: listOf(SourceProvider.CODEBERG, SourceProvider.GITLAB)
                var provider: SourceProvider? = null
                var repos: List<RepoRef> = emptyList()
                for (candidate in candidates) {
                    val host = ref.host.ifEmpty { publicHost(candidate) }
                    val listed = externalApi.listAccountRepos(candidate, host, ref.owner, includeForks)
                    if (listed.isNotEmpty()) {
                        provider = candidate
                        repos = listed
                        break
                    }
                }
                if (provider == null) {
                    val suggestToken = externalApi.shouldSuggestGithubToken()
                    snack(
                        message = if (suggestToken) {
                            context.getString(R.string.external_rate_limited)
                        } else {
                            context.getString(R.string.external_account_no_repos, ref.owner)
                        },
                        long = suggestToken,
                    )
                    return@launch
                }
                val account = ExternalAccount(
                    provider = provider,
                    owner = ref.owner,
                    host = ref.host,
                    label = trimmedName.ifEmpty { ref.owner },
                    enabled = true,
                    includeForks = includeForks,
                    lastScan = System.currentTimeMillis(),
                )
                if (accounts.value.any { it.key == account.key }) {
                    snack(context.getString(R.string.external_account_already_added, account.label))
                    return@launch
                }
                // Don't absorb a repo the user already tracks as its own single-repo source (e.g. the
                // built-in Omnify repo): leave it standalone.
                val standaloneKeys = apps.value.filter { it.accountKey == null }.map { it.key }.toSet()
                val discovered = discoverAccountApps(
                    account = account,
                    repos = repos,
                    skipKeys = standaloneKeys,
                    includePrereleases = includePrereleases,
                    muteUpdates = muteUpdates,
                    apkFilter = apkFilter,
                )
                if (discovered.isEmpty()) {
                    // Distinguish "really nothing to install" from "the API rate limit cut the per-repo
                    // release checks short" (which would also yield nothing), so the user knows to add a
                    // token rather than think their account has no apps.
                    val suggestToken = externalApi.shouldSuggestGithubToken()
                    snack(
                        message = if (suggestToken) {
                            context.getString(R.string.external_rate_limited)
                        } else {
                            context.getString(R.string.external_account_no_apps, ref.owner)
                        },
                        long = true,
                    )
                    return@launch
                }
                repository.upsertApps(discovered)
                repository.upsertAccount(account)
                snack(context.getString(R.string.external_account_added, account.label, discovered.size))
                added = true
            } finally {
                _addState.value = if (added) AddSourceState.SUCCESS else AddSourceState.IDLE
            }
        }
    }

    /**
     * For each repo of [account] not already tracked ([skipKeys]), keeps those that ship an installable
     * APK release and builds an [ExternalApp] for them (with package id, icon, name and TV support read
     * from the repo, like a single-repo source). Sequential to pace the provider's rate limit.
     */
    private suspend fun discoverAccountApps(
        account: ExternalAccount,
        repos: List<RepoRef>,
        skipKeys: Set<String>,
        includePrereleases: Boolean,
        muteUpdates: Boolean,
        apkFilter: String,
    ): List<ExternalApp> {
        val filter = apkFilter.trim().ifEmpty { null }
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
                enabled = account.enabled,
                accountKey = account.key,
                label = ref.repo,
            )
            if (candidate.key in skipKeys) continue
            val release = externalApi.latestReleaseFor(candidate) ?: continue
            val packageId = externalApi.fetchPackageId(candidate)
            val meta = externalApi.fetchRepoMetadata(candidate)
            val resolvedLabel = packageId?.let { installedLabel(it) } ?: meta?.appName ?: candidate.repo
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
            )
        }
        return result
    }

    /** Re-scans [account]'s repos to pick up newly published apps (existing ones are left untouched;
     *  the normal [refresh] keeps their releases current). Updates the account's last-scan time. */
    fun rescanAccount(account: ExternalAccount) {
        viewModelScope.launch { rescanAccountNow(account) }
    }

    private suspend fun rescanAccountNow(account: ExternalAccount) {
        val host = account.host.ifEmpty { publicHost(account.provider) }
        val repos = externalApi.listAccountRepos(
            account.provider,
            host,
            account.owner,
            account.includeForks,
        )
        // Bump the last-scan time even when the listing fails/empties, so a transient failure doesn't make
        // every refresh hammer the API; a real new app shows up at the next daily scan.
        if (repos.isNotEmpty()) {
            // Skip repos already tracked: this account's existing apps, plus any standalone single-repo
            // source (so the account never absorbs e.g. the built-in Omnify repo).
            val skipKeys = apps.value
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
            )
            if (discovered.isNotEmpty()) repository.upsertApps(discovered)
        }
        repository.upsertAccount(account.copy(lastScan = System.currentTimeMillis()))
    }

    /** Enables/disables a whole account, cascading to all of its discovered apps. */
    fun setAccountEnabled(account: ExternalAccount, enabled: Boolean) {
        viewModelScope.launch {
            repository.upsertAccount(account.copy(enabled = enabled))
            repository.setAccountAppsEnabled(account.key, enabled)
        }
    }

    /** Removes an account source and every app it discovered. */
    fun removeAccount(account: ExternalAccount) {
        viewModelScope.launch {
            repository.removeAppsByAccount(account.key)
            repository.removeAccount(account.key)
        }
    }

    private fun publicHost(provider: SourceProvider): String = when (provider) {
        SourceProvider.GITHUB -> "github.com"
        SourceProvider.GITLAB -> "gitlab.com"
        SourceProvider.CODEBERG -> "codeberg.org"
    }

    /** Downloads the latest release's APK (with live progress) and installs it. */
    fun installOrUpdate(app: ExternalApp) {
        if (_downloads.value.containsKey(app.key)) return
        downloadJobs[app.key] = viewModelScope.launch { downloadAndInstall(app) }
    }

    /** Downloads and installs a specific release the user picked from the version list, instead of
     *  whatever [installOrUpdate] would offer. */
    fun installVersion(app: ExternalApp, release: Release) {
        if (_downloads.value.containsKey(app.key)) return
        _downloadTargetTag.value = _downloadTargetTag.value + (app.key to release.tag)
        downloadJobs[app.key] = viewModelScope.launch { downloadAndInstall(app, release) }
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

    /** Re-checks every app for a newer release tag (e.g. on opening the screen) and backfills icon/
     *  name/TV-support metadata. The release check itself is enabled-only, like a disabled repository —
     *  each one costs a GitHub API call, so a source nobody's opted into yet shouldn't burn the anonymous
     *  60-requests/hour budget on updates nobody's watching for. The metadata backfill runs for every
     *  tracked app regardless of enabled state, though: it's what the pre-install browsing UI (the
     *  sources list, "Choix d'Omnify") shows *before* the user ever opts in, so a still-disabled source
     *  deserves its real icon too — previously it was gated behind the same enabled-only filter as the
     *  release check, so a source seeded disabled (the curated pack) never got its repoIconUrl backfilled
     *  and stayed on the owner-avatar fallback (see ExternalAppIcon.kt) indefinitely, even after the
     *  source had been sitting there for months. It's still genuinely one-time per repo either way (the
     *  *Checked flags below), so this doesn't add ongoing cost, only a one-off scan the first time each
     *  source is ever seen. Throttled: this fires on every screen entry. */
    fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNetworkRefreshAt < REFRESH_THROTTLE_MS) return
        lastNetworkRefreshAt = now
        viewModelScope.launch {
            apps.value.forEach { app ->
                // A release may not exist yet (e.g. the seeded Omnify source has no published release),
                // or the source may simply not be enabled yet — either way we still scan the repo below
                // for its icon / name / TV support, which don't depend on a downloadable APK, and simply
                // keep the existing release fields when there's none.
                val release = if (app.enabled) externalApi.latestReleaseFor(app) else null
                // Track the APK file's identity, not just the tag, so updates are detected from the
                // actual APK (see ExternalApp.hasUpdate); keep its file name for the "latest APK" line.
                val tag = release?.tag ?: app.latestTag
                val token = release?.apkVersionToken(filter = app.apkFilter) ?: app.latestApkToken
                val apkName = release?.apkFileName(filter = app.apkFilter) ?: app.latestApkName
                val apkSize = release?.apkFileSize(filter = app.apkFilter) ?: app.latestApkSize
                val apkUrl = release?.apkDownloadUrl(filter = app.apkFilter) ?: app.latestApkUrl
                if (release != null) {
                    Log.d(
                        TAG,
                        "refresh ${app.key}: fetchedSize=${release.apkFileSize(filter = app.apkFilter)} " +
                            "storedSize=${app.latestApkSize} -> $apkSize",
                    )
                }
                // Backfill the package id (from build.gradle) for sources added before this existed, so
                // an installed app starts showing its real name + icon; the existing label reconcile
                // then fills in the on-device name. Never overwrites an id already learned from install.
                val packageId = app.packageName ?: externalApi.fetchPackageId(app)
                // One-time backfill of the repo icon + real app name + TV support for sources added
                // before these existed. Gated by the *Checked flags so a repo is scanned at most once
                // (spares the API rate limit), and never overrides a user-picked icon or name.
                val needsIcon = !app.iconChecked && !app.iconOverridden && app.repoIconUrl == null
                val needsTv = !app.tvChecked
                val needsMeta = needsIcon || needsTv
                val meta = if (needsMeta) externalApi.fetchRepoMetadata(app) else null
                // meta == null means the scan failed (couldn't read the tree); don't mark anything
                // "checked" then, so it retries on a later refresh.
                val scanned = meta != null
                // Only adopt a repo icon when we were actually looking for one — don't clobber a set or
                // user-picked icon just because we re-scanned for TV support.
                val repoIcon = if (needsIcon) meta?.iconCandidates?.firstOrNull() ?: app.repoIconUrl else app.repoIconUrl
                val supportsTv = if (needsTv) meta?.supportsTelevision ?: app.supportsTelevision else app.supportsTelevision
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
                    // Re-read the current record instead of copying from `app` (a snapshot taken before
                    // this loop's network calls, which can take several seconds): if the user installed
                    // or updated this very app while refresh() was still running, `app`'s installedTag/
                    // installedApkToken/packageName/label are already stale, and copying from it here
                    // would silently overwrite that fresh install state with the old pre-install values —
                    // flashing the Update button back on right after it correctly switched to Launch.
                    val current = apps.value.firstOrNull { it.key == app.key } ?: app
                    repository.upsertApp(
                        current.copy(
                            packageName = current.packageName ?: packageId,
                            label = if (resolvedLabel != app.label) resolvedLabel else current.label,
                            latestTag = tag,
                            latestApkToken = token,
                            latestApkName = apkName,
                            latestApkSize = apkSize,
                            latestApkUrl = apkUrl,
                            repoIconUrl = repoIcon,
                            iconChecked = current.iconChecked || (needsIcon && scanned),
                            supportsTelevision = supportsTv,
                            tvChecked = current.tvChecked || (needsTv && scanned),
                        ),
                    )
                }
            }
            // Once a day, re-scan each enabled account for newly published apps (the apps it already
            // found are refreshed by the per-app loop above). Disabled accounts and never-scanned ones
            // (handled by the init watcher) are skipped, so this barely adds to the API cost.
            accounts.value
                .filter {
                    it.enabled &&
                        it.lastScan != 0L &&
                        System.currentTimeMillis() - it.lastScan > ACCOUNT_RESCAN_INTERVAL_MS
                }
                .forEach { rescanAccountNow(it) }
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
            // APK filter changes, so re-resolve it in that case — and the version list must forget its
            // cached fetch too (releaseHistory() filters by these same two fields), or reopening the
            // detail screen within the cache's freshness window would keep showing the list as it looked
            // under the old settings.
            if (includePrereleases != app.includePrereleases || trimmedFilter != app.apkFilter) {
                releaseHistoryCache.remove(app.key)
                val release = externalApi.latestReleaseFor(updated)
                // Stale latest* fields must be cleared, not just left alone, when the new settings no
                // longer resolve to any release (e.g. turning pre-releases off for a source that only
                // publishes them) — otherwise the hero card kept showing the old "latest" version and
                // offering Update against a release that isn't actually offered under the new settings
                // any more.
                updated = updated.copy(
                    latestTag = release?.tag,
                    latestApkToken = release?.apkVersionToken(filter = updated.apkFilter),
                    latestApkName = release?.apkFileName(filter = updated.apkFilter),
                    latestApkSize = release?.apkFileSize(filter = updated.apkFilter),
                    latestApkUrl = release?.apkDownloadUrl(filter = updated.apkFilter),
                )
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

    /** [releaseOverride] installs a specific release picked from the version list instead of resolving
     *  the latest one — see [installVersion]. */
    private suspend fun downloadAndInstall(app: ExternalApp, releaseOverride: Release? = null) {
        // Fail fast before downloading: if the Shizuku installer is selected but not usable, tell the
        // user why instead of downloading an APK that could never be installed.
        ShizukuState.installBlockReason(context, settingsRepository.getInitial().installerType)?.let {
            snack(context.getString(it))
            return
        }
        updateDownload(app.key, DownloadStatus(read = 0, total = -1, bytesPerSecond = 0))
        try {
            val release = releaseOverride ?: when (val lookup = externalApi.latestReleaseLookup(app)) {
                is ReleaseLookup.Found -> lookup.release
                ReleaseLookup.FetchFailed -> {
                    val suggestToken = externalApi.shouldSuggestGithubToken()
                    snack(
                        message = if (suggestToken) {
                            context.getString(R.string.external_rate_limited)
                        } else {
                            context.getString(R.string.external_unreachable, app.sourceLabel)
                        },
                        long = suggestToken,
                    )
                    return
                }
                ReleaseLookup.OnlyPrereleasesExcluded -> {
                    snack(context.getString(R.string.external_only_prereleases, app.path))
                    return
                }
                ReleaseLookup.NoCompatibleApk -> {
                    snack(context.getString(R.string.external_no_apk, app.repo))
                    return
                }
            }
            val asset = selectApkAsset(release.assets, filter = app.apkFilter, releaseTag = release.tag)
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
            if (context.packageManager.installedWithDifferentSignature(packageName, releaseFile)) {
                // Different signer: Android can't update across keys. Ask the user to uninstall the
                // existing copy first, same as the F-Droid catalogue's own dialog — and don't touch the
                // tracked record at all, so it keeps correctly pointing at whatever's really installed.
                _signatureConflict.value = SignatureConflict(isSystemApp = isSystemApp(packageName))
                return
            }
            installManager.install(InstallItem(PackageName(packageName), cacheFileName))
            // Record which APK file this release would install (its identity), so future update checks
            // compare the APK, not the tag — and what the latest release now is. Deliberately installing
            // an older pick from the version list (releaseOverride) isn't a signal that it's now the
            // latest — leave latestTag/latestApkToken/latestApkName/latestApkSize as they were, so
            // hasUpdate still correctly flags that a newer release exists. Installing via the normal
            // Install/Update button (no override) installs exactly the release those fields already
            // point at, so adopting them here is a no-op for that case and just keeps the values in sync.
            val token = release.apkVersionToken(filter = app.apkFilter)
            repository.upsertApp(
                app.copy(
                    packageName = packageName,
                    label = realLabel ?: app.label,
                    latestTag = if (releaseOverride == null) release.tag else app.latestTag,
                    latestApkToken = if (releaseOverride == null) token else app.latestApkToken,
                    latestApkName = if (releaseOverride == null) {
                        release.apkFileName(filter = app.apkFilter)
                    } else {
                        app.latestApkName
                    },
                    latestApkSize = if (releaseOverride == null) {
                        release.apkFileSize(filter = app.apkFilter)
                    } else {
                        app.latestApkSize
                    },
                    latestApkUrl = if (releaseOverride == null) {
                        release.apkDownloadUrl(filter = app.apkFilter)
                    } else {
                        app.latestApkUrl
                    },
                ),
            )
            // installedTag/installedApkToken/installedVersionName are only recorded once the system
            // install actually reaches Installed — not right after merely enqueueing it above. Writing
            // them optimistically here used to leave a stale "installed" record (and a wrongly-hidden
            // update) whenever the install silently failed after this function had already returned,
            // e.g. exactly the signature conflict this same function now catches, or a cancelled/failed
            // system install dialog. This runs as its own job so it isn't cut short by this function's
            // own finally block below.
            viewModelScope.launch {
                val terminal = installManager.state
                    .map { it[PackageName(packageName)] }
                    // Wait to actually see this install start (Pending/Installing) before accepting a
                    // terminal value — otherwise a stale Installed/Failed already sitting in the map
                    // from an earlier, unrelated attempt on the same package could be mistaken for this
                    // one's result the instant this collector subscribes.
                    .dropWhile { it != InstallState.Pending && it != InstallState.Installing }
                    .first { it == InstallState.Installed || it == InstallState.Failed }
                if (terminal == InstallState.Installed) {
                    val current = repository.getApps().firstOrNull { it.key == app.key } ?: return@launch
                    repository.upsertApp(
                        current.copy(
                            installedTag = release.tag,
                            installedApkToken = token,
                            installedVersionName = installedVersionName(packageName),
                        ),
                    )
                }
            }
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

/** Max characters per translation request (keeps the Google endpoint's URL within limits). */
private const val TAG = "ExternalAppsViewModel"

private const val MAX_TRANSLATE_CHUNK = 1500

private val TAG_REGEX = Regex("<[^>]+>")

/** HTML tags whose inner text must not be translated, so code stays code. */
private val SKIP_TEXT_TAGS = setOf("code", "pre", "script", "style")

/** The lowercase element name of an HTML tag token (e.g. `<a href=…>` gives "a"), or null if unreadable. */
private fun tagName(tag: String): String? =
    Regex("^</?\\s*([A-Za-z0-9]+)").find(tag)?.groupValues?.get(1)?.lowercase()

/** How often the download speed is recomputed (sliding window length). */
private const val SPEED_WINDOW_MS = 500L

/** Minimum delay between progress UI updates, to avoid flooding recompositions. */
private const val EMIT_INTERVAL_MS = 150L

/** Minimum gap between automatic network refreshes of external sources (they fire on every screen
 *  entry and each enabled source is one GitHub API call, so this protects the rate-limit budget). */
private const val REFRESH_THROTTLE_MS = 10 * 60 * 1000L

/** How often an account source is re-scanned for newly published apps. Listing a whole account is
 *  several API calls, so it runs at most once a day rather than on every refresh. */
private const val ACCOUNT_RESCAN_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** How long a cached README is considered fresh enough to skip a network refetch on re-open. A README
 *  changes on the order of days/weeks, so re-opening the same app's detail screen repeatedly within
 *  this window shows the cached copy as-is instead of hitting the network again for identical content. */
private const val README_FRESHNESS_MS = 15 * 60 * 1000L

/** A resolved link check: the instance itself being null means "still checking"; once present,
 *  [url] is null when the thing genuinely doesn't exist (e.g. no issue tracker), distinguishing that
 *  from not having looked yet — a plain nullable String can't tell those two states apart. */
data class LinkCheckState(val url: String?)
