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
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.external.ExternalAccount
import com.looker.droidify.external.ExternalApi
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.ExternalAccountRef
import com.looker.droidify.external.RepoRef
import com.looker.droidify.external.apkFileName
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
import com.looker.droidify.datastore.model.TranslationEngine
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.translation.TranslationManager
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
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val apps: StateFlow<List<ExternalApp>> = repository.apps.asStateFlow(emptyList())

    /** Whether the user picked a translation engine. The Translate button is hidden when off. */
    val translationEnabled: StateFlow<Boolean> = settingsRepository.data
        .map { it.translationEngine != TranslationEngine.NONE }
        .asStateFlow(false)

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

    /** Keys of tracked apps that are currently installed on the device. */
    val installedKeys: StateFlow<Set<String>> = combine(
        repository.apps,
        installManager.state,
        installedRefresh,
        installedChanges,
    ) { apps, _, _, _ ->
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
        installedChanges,
    ) { apps, _, _, _ ->
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

    /** State of the README "Translate" toggle on the external detail screen. */
    private val _readmeTranslation =
        MutableStateFlow<DescriptionTranslation>(DescriptionTranslation.Original)
    val readmeTranslation: StateFlow<DescriptionTranslation> = _readmeTranslation

    fun loadReadme(app: ExternalApp) {
        // A different app's README is about to load, so drop any translation left on the previous one.
        _readmeTranslation.value = DescriptionTranslation.Original
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
                    val release = externalApi.latestReleaseFor(app)
                    if (release == null) {
                        // A null release here is often the GitHub rate limit; if so (and no token is
                        // set) nudge the user toward adding one instead of a generic "no release".
                        val suggestToken = externalApi.shouldSuggestGithubToken()
                        // Or the repo only publishes pre-releases (e.g. ReVanced Manager): with the
                        // option off they're all filtered out. Detect that so we can tell the user to
                        // enable it, instead of a misleading "no app".
                        val onlyPrereleases = !suggestToken && !includePrereleases &&
                            externalApi.latestReleaseFor(app.copy(includePrereleases = true)) != null
                        val message = when {
                            suggestToken -> context.getString(R.string.external_rate_limited)
                            onlyPrereleases ->
                                context.getString(R.string.external_only_prereleases, app.path)
                            else -> context.getString(R.string.external_no_release, app.path)
                        }
                        _addError.value = message
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
                        else -> packageId?.let { installedLabel(it) } ?: meta?.appName ?: app.label
                    }
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
                // A release may not exist yet (e.g. the seeded Omnify source has no published release).
                // We still scan the repo below — a source's icon / name / TV support don't depend on a
                // downloadable APK — and simply keep the existing release fields when there's none.
                val release = externalApi.latestReleaseFor(app)
                // Track the APK file's identity, not just the tag, so updates are detected from the
                // actual APK (see ExternalApp.hasUpdate); keep its file name for the "latest APK" line.
                val tag = release?.tag ?: app.latestTag
                val token = release?.apkVersionToken(filter = app.apkFilter) ?: app.latestApkToken
                val apkName = release?.apkFileName(filter = app.apkFilter) ?: app.latestApkName
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
                    packageId != app.packageName ||
                    repoIcon != app.repoIconUrl ||
                    resolvedLabel != app.label ||
                    supportsTv != app.supportsTelevision ||
                    (needsMeta && scanned)
                ) {
                    repository.upsertApp(
                        app.copy(
                            packageName = packageId,
                            label = resolvedLabel,
                            latestTag = tag,
                            latestApkToken = token,
                            latestApkName = apkName,
                            repoIconUrl = repoIcon,
                            iconChecked = app.iconChecked || (needsIcon && scanned),
                            supportsTelevision = supportsTv,
                            tvChecked = app.tvChecked || (needsTv && scanned),
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
        // Fail fast before downloading: if the Shizuku installer is selected but not usable, tell the
        // user why instead of downloading an APK that could never be installed.
        ShizukuState.installBlockReason(context, settingsRepository.getInitial().installerType)?.let {
            snack(context.getString(it))
            return
        }
        updateDownload(app.key, DownloadStatus(read = 0, total = -1, bytesPerSecond = 0))
        try {
            val release = externalApi.latestReleaseFor(app)
            if (release == null) {
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

/** Max characters per translation request (keeps the Google endpoint's URL within limits). */
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
