package com.looker.droidify.compose.appDetail

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import com.looker.droidify.data.signerMismatch
import com.looker.droidify.compose.components.DescriptionTranslation
import com.looker.droidify.compose.components.SupportedLanguages
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
import com.looker.droidify.external.ExternalApi
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.SourceProvider
import com.looker.droidify.external.parseExternalSource
import com.looker.droidify.translation.TranslationManager
import com.looker.droidify.utility.apk.InstalledApkLocaleReader
import com.looker.droidify.utility.apk.RangeCapableMirrors
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
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
    private val externalApi: ExternalApi,
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

    // Declared here, ahead of installedInfo below (which reads it inside a combine()), rather than in
    // its original position further down the file: a property initializer reading another val that's
    // still declared later in the class body sees that val's pre-initialization JVM default (null for
    // a StateFlow) rather than its real value, since class properties initialize top-to-bottom in
    // source order — the same "leaking this" hazard the trackSourceLocaleCrossCheck/init-block comment
    // below already documents for baseSupportedLanguages, hit again here for the same reason.
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
            // The catalogue's own declared signer(s) for this app, needed to tell whether whatever is
            // installed under this package name is actually a build of this catalogue entry — see
            // readInstalledInfo's signatureMismatch.
            state,
        ) { _, _, _, currentState -> currentState }
            .map { readInstalledInfo(it) }
            .flowOn(Dispatchers.Default)
            .asStateFlow(null)

    /** Whether this app is in the user's favourites. */
    val isFavourite: StateFlow<Boolean> = settingsRepository.get { favouriteApps }
        .map { packageName in it }
        .asStateFlow(false)

    /** Whether the tablet-landscape two-pane detail layout is allowed at all (the Settings toggle) — a
     *  screen still only actually shows it when it's also tablet-width and landscape. */
    val splitViewEnabled: StateFlow<Boolean> = settingsRepository.get { splitViewEnabled }
        .asStateFlow(true)

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

    private val _downloadTargetVersionCode = MutableStateFlow<Long?>(null)

    /**
     * versionCode of whichever release [downloadStatus]/[installState] currently applies to — set the
     * moment a download starts and left alone afterwards (there's always at most one download/install
     * in flight, guarded below, so a stale value is harmless: it's only ever read together with
     * [downloadStatus]/[installState] actually being active). Lets the version list show progress on
     * the specific row the user tapped instead of only in the hero card, which stays out of view once
     * the user has scrolled down to the list.
     */
    val downloadTargetVersionCode: StateFlow<Long?> = _downloadTargetVersionCode

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

    /**
     * The not-yet-installed app's real supported languages, read directly from its APK's compiled
     * resources (see [RemoteApkLocaleReader]) instead of the F-Droid store-listing approximation —
     * null until resolved (or if the app is installed, since tier 1 below wins then and this is never
     * needed). Kept updated by [trackRemoteApkLocales], started once in [init].
     */
    private val _remoteApkLocales = MutableStateFlow<List<String>?>(null)

    /** True while [trackRemoteApkLocales] has an actual fetch in flight — distinct from
     *  [_remoteApkLocales] staying null forever once that fetch gives up, which is *not* loading. */
    private val _remoteApkPending = MutableStateFlow(false)

    /**
     * True once a check has actively CONFIRMED that the app's real compiled manifest carries no
     * component reachable via a push (FCM/GCM) intent action, despite [declaresPushCapability] flagging
     * the permission from the index alone — i.e. the permission is a vestigial declaration for this
     * specific build, not a real dependency (see [RemoteApkManifestReader]'s own doc comment for a real
     * example: a de-Googled Signal fork whose push-receiving services are entirely removed from its
     * manifest while the permission itself was left declared). False is the safe default: nothing
     * checked yet, the app doesn't even declare the permission, or the check couldn't complete — the
     * Google-services card keeps showing the index-only signal until this actively contradicts it, never
     * the other way around. Kept updated by [trackPushComponentVerification], started once in [init].
     */
    private val _pushCapabilityConfirmedAbsent = MutableStateFlow(false)
    val pushCapabilityConfirmedAbsent: StateFlow<Boolean> = _pushCapabilityConfirmedAbsent

    /**
     * Result of cross-checking a default-English-only [baseSupportedLanguages] against the app's own
     * published source code — see [trackSourceLocaleCrossCheck]. Null until that check has run (or if
     * it never applies/can't run); a list once it has, "en" alone meaning it agrees nothing further
     * exists, anything more meaning it found a language the compiled-resource check above missed.
     */
    private val _sourceCrossCheck = MutableStateFlow<List<String>?>(null)

    /** True while [trackSourceLocaleCrossCheck] has an actual check in flight — same distinction as
     *  [_remoteApkPending], for the same reason. */
    private val _sourceCrossCheckPending = MutableStateFlow(false)

    /**
     * Locale codes the app is translated into (its supported languages), for the detail screen's
     * "supported languages" section, most reliable first:
     * 1. Installed: the real UI languages read from the installed APK (the truth — what the user
     *    actually sees in the app).
     * 2. Not installed: the real UI languages read directly from the not-yet-downloaded APK's own
     *    compiled resources ([_remoteApkLocales]) — equally reliable, just not yet confirmed by an
     *    actual install. Resolved in the background (see [trackRemoteApkLocales]); this section shows
     *    tier 3 until it resolves.
     * 3. The F-Droid store-listing translations from the index — an approximation, since an app can
     *    ship a translated UI without a translated store listing (and vice versa). Only actually shown
     *    while tier 2 is still resolving, or if it fails (network error, a repo host that doesn't
     *    support range requests, ...).
     */
    private val baseSupportedLanguages: StateFlow<SupportedLanguages> = combine(
        state.map { (it as? AppDetailState.Success)?.app?.appId }.distinctUntilChanged(),
        installedInfo,
        _remoteApkLocales,
    ) { appId, installed, remoteLocales ->
        val apkLocales = if (installed != null) installedApkLocales() else emptyList()
        when {
            // Installed: the real UI languages from the APK -> reliable, so the status can be definite.
            apkLocales.isNotEmpty() -> SupportedLanguages(apkLocales, reliable = true)
            // Not installed, but directly confirmed from the APK itself -> equally reliable. An empty
            // result is excluded here on purpose: unlike installedApkLocales() (read straight from the
            // installed APK's own resources.arsc, already on disk — a local file read essentially never
            // fails the way a remote fetch can), a *download* coming back with zero locale-specific
            // resource configs can just as easily mean the fetch/parse silently missed something as it
            // can mean a genuinely unlocalized app — a false "not translated" is worse than falling back
            // to the approximation below, so it's treated as inconclusive.
            !remoteLocales.isNullOrEmpty() -> SupportedLanguages(remoteLocales, reliable = true)
            // Not installed and not yet confirmed: only the store-listing translations, which may
            // differ from the app's UI -> present as approximate so we never wrongly claim a language
            // isn't translated.
            appId != null -> SupportedLanguages(appRepository.supportedLocales(appId), reliable = false)
            else -> SupportedLanguages(emptyList(), reliable = false)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(SupportedLanguages())

    init {
        // Both read state (declared above) directly; trackSourceLocaleCrossCheck also reads
        // baseSupportedLanguages, just declared above this block — a property is only assigned once its
        // own initializer line runs, so this init block must come after it, not before (a launch{} body
        // runs eagerly enough on viewModelScope's default dispatcher to hit that gap immediately: a real
        // NullPointerException from combine() reading baseSupportedLanguages while it was still null,
        // confirmed via a real crash log, when this init block was placed above it instead).
        viewModelScope.launch { trackRemoteApkLocales() }
        viewModelScope.launch { trackSourceLocaleCrossCheck() }
        viewModelScope.launch { trackPushComponentVerification() }
    }

    /**
     * [baseSupportedLanguages], cross-checked against the app's own published source code (see
     * [trackSourceLocaleCrossCheck]) whenever that compiled-resource-based check either never became
     * reliable at all (tier 3, the store-listing approximation) or found only the default English and
     * nothing else — the two cases it's known to sometimes fall short (see
     * [com.looker.droidify.compose.components.SupportedLanguagesSection]'s own doc comment on the
     * latter; a real example of the former — a Codeberg-hosted app whose compiled-resource check never
     * resolved at all — is what surfaced the need to also cover it here). A tier-3 result that the
     * cross-check actually resolves is replaced outright (a real source-code answer beats a guess,
     * whatever it finds); the English-only case behaves as before — a second, disagreeing opinion is
     * folded straight in as an additional language, a second, *agreeing* opinion upgrades the existing
     * caveat to an actual confirmation ([SupportedLanguages.sourceCrossChecked]) instead of just
     * repeating the same single-method answer a second time. Deliberately does not otherwise touch or
     * reorder the tiers above: this is a second opinion for the cases they're least sure of, not a
     * replacement for the whole system.
     */
    val supportedLanguages: StateFlow<SupportedLanguages> = combine(
        baseSupportedLanguages,
        _sourceCrossCheck,
        _remoteApkPending,
        _sourceCrossCheckPending,
    ) { base, sourceLocales, remoteApkPending, sourceCrossCheckPending ->
        val onlyDefaultEnglish = base.reliable && base.codes.size == 1 &&
            base.codes.single().equals("en", ignoreCase = true)
        val merged = when {
            sourceLocales == null -> base
            !base.reliable -> SupportedLanguages(codes = sourceLocales, reliable = true)
            onlyDefaultEnglish && sourceLocales.any { !it.equals("en", ignoreCase = true) } ->
                base.copy(codes = (base.codes + sourceLocales).distinct())
            onlyDefaultEnglish -> base.copy(sourceCrossChecked = true)
            else -> base
        }
        merged.copy(isLoading = remoteApkPending || sourceCrossCheckPending)
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(SupportedLanguages())

    /**
     * Runs the source-code cross-check (see [supportedLanguages]'s own doc comment) whenever
     * [baseSupportedLanguages] either never became reliable at all (tier 3, the store-listing
     * approximation — regardless of what it happens to show) or settled on the default-English-only
     * result, using the app's own `sourceCode` link from the F-Droid index — the same field the
     * "Source code" row links to. Skipped entirely (leaving [_sourceCrossCheck] at null, i.e. no change
     * to what's shown) when there's no such link, it doesn't parse as a supported host, or the repo
     * tree can't be read at all (private repo, network failure, self-hosted instance that turns out not
     * to be Gitea/Forgejo, …) — this is strictly a bonus second opinion, never a reason to block or
     * worsen what's already shown.
     */
    private suspend fun trackSourceLocaleCrossCheck() {
        combine(state, baseSupportedLanguages) { s, base -> s to base }
            .distinctUntilChanged()
            .collectLatest { (currentState, base) ->
                _sourceCrossCheck.value = null
                _sourceCrossCheckPending.value = false
                val onlyDefaultEnglish = base.reliable && base.codes.size == 1 &&
                    base.codes.single().equals("en", ignoreCase = true)
                if (base.reliable && !onlyDefaultEnglish) return@collectLatest
                _sourceCrossCheckPending.value = true
                try {
                    val success = currentState as? AppDetailState.Success ?: return@collectLatest
                    val sourceUrl = success.app.links?.sourceCode?.takeIf { it.isNotBlank() }
                        ?: return@collectLatest
                    val ref = parseExternalSource(sourceUrl) ?: return@collectLatest
                    val provider = ref.provider ?: run {
                        if (externalApi.isGiteaInstance(ref.host, ref.owner, ref.repo)) {
                            SourceProvider.CODEBERG
                        } else {
                            null
                        }
                    } ?: return@collectLatest
                    val probe =
                        ExternalApp(provider = provider, host = ref.host, owner = ref.owner, repo = ref.repo)
                    val sourceLocales = externalApi.fetchSourceLocales(probe)?.takeIf { it.isNotEmpty() }
                        ?: return@collectLatest
                    _sourceCrossCheck.value = sourceLocales
                } finally {
                    // Runs on every exit path (including the early returns above), so isLoading never
                    // gets stuck true when the check is skipped or fails partway through.
                    _sourceCrossCheckPending.value = false
                }
            }
    }

    /**
     * Keeps [pushCapabilityConfirmedAbsent] current. Runs only once the app's declared permissions
     * already match [declaresPushCapability] — nothing to verify otherwise. Installed: asks the OS
     * which components resolve the push intent actions in this exact package
     * ([hasAppOwnedPushComponent] — live, authoritative, and free, no network call at all). Not
     * installed: reads the real compiled manifest of the release this screen would install — from an
     * already-downloaded local copy when one exists, else via [RemoteApkManifestReader] — and runs the
     * same component-level analysis ([pushCapabilityIsVestigial]) on it. Either way, the question is
     * NOT "does any push component exist" but "does any component OUTSIDE a bundled Google library's
     * own namespace exist" — see [isGoogleLibraryComponent] for why, confirmed against a real
     * de-Googled fork whose merged manifest still carries the Firebase library's own generic push
     * components while every app-authored one is gone. Only an *absence* of app-authored components is
     * trusted (see [pushCapabilityConfirmedAbsent]'s "never act on uncertainty" rule) — presence
     * changes nothing, since the index-only signal already shows the warning either way.
     *
     * [downloadStatus] is part of the trigger (its non-null -> null transition at the end of a
     * download re-fires the collector) so that a download that just landed in the cache — e.g. an
     * install attempt a signature conflict then blocked — is re-checked immediately: the state/
     * installedInfo pair alone doesn't change in that case, so nothing else would re-run this.
     */
    private suspend fun trackPushComponentVerification() {
        combine(state, installedInfo, downloadStatus) { s, installed, download ->
            Triple(s, installed, download != null)
        }
            .distinctUntilChanged()
            .collectLatest { (currentState, installed, downloadActive) ->
                if (downloadActive) return@collectLatest
                _pushCapabilityConfirmedAbsent.value = false
                val success = currentState as? AppDetailState.Success ?: return@collectLatest
                val permissionNames = success.packages
                    .flatMap { (pkg, _) -> pkg.manifest.permissions }
                    .mapTo(hashSetOf()) { it.name }
                if (!declaresPushCapability(permissionNames)) return@collectLatest
                // A signer mismatch means whatever's installed under this package name isn't actually a
                // build of this app (see InstalledInfo.signatureMismatch) — querying the OS here would
                // check that *other* app's components, not this one's, so this falls through to the
                // manifest-based path exactly as if nothing were installed at all.
                val confirmedAbsent = if (installed != null && !installed.signatureMismatch) {
                    !hasAppOwnedPushComponent()
                } else {
                    val installable = success.packages
                        .selectForDevice(success.app.metadata.suggestedVersionCode)
                        ?: return@collectLatest
                    val (pkg, repo) = installable
                    // A local, already-downloaded copy (e.g. left over from an install attempt that a
                    // signature conflict blocked) beats any remote read: no network cost at all. Then
                    // the repo's own address; and if THAT host doesn't support range requests
                    // (confirmed against a real one: a GitLab Pages-hosted repo that ignores the Range
                    // header entirely and returns the full, 100+MB file every time), derived mirrors of
                    // the same file on its backing forge (see RangeCapableMirrors) — identity-checked
                    // against the index's declared byte size, and never sent the repo's own
                    // credentials, which belong to the origin host alone. The whole file is never
                    // silently downloaded just to check a warning label.
                    val directUrl = repo.address.removeSuffix("/") + "/" + pkg.apk.name.removePrefix("/")
                    val manifestBytes = cachedManifestBytes(pkg)
                        ?: RemoteApkManifestReader.fetchManifestBytes(downloader, directUrl) {
                            repo.authentication?.let { authentication(it.username, it.password) }
                        }
                        ?: RangeCapableMirrors.candidates(directUrl).firstNotNullOfOrNull { mirrorUrl ->
                            RemoteApkManifestReader.fetchManifestBytes(
                                downloader,
                                mirrorUrl,
                                expectedTotalSize = pkg.apk.size.value,
                            )
                        }
                        ?: return@collectLatest
                    pushCapabilityIsVestigial(manifestBytes) ?: return@collectLatest
                }
                _pushCapabilityConfirmedAbsent.value = confirmedAbsent
            }
    }

    /**
     * The compiled manifest bytes from a fully downloaded, hash-verified local copy of [pkg]'s APK
     * (see [downloadAndInstall]'s own cache, keyed the same way) — null when there's no such cached
     * copy, or it can't be read as a ZIP; the caller then falls back to a remote read. A plain local
     * file read, so unlike [RemoteApkManifestReader] it works regardless of whether the host supports
     * HTTP range requests.
     */
    private fun cachedManifestBytes(pkg: Package): ByteArray? {
        val cacheFileName = pkg.apk.hash.replace('/', '-') + ".apk"
        val file = Cache.getReleaseFile(context, cacheFileName)
        if (!file.exists()) return null
        return runCatching {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return@use null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()
    }

    /**
     * True when the OS resolves a push intent action to a component of this exact installed package
     * whose class is the app's own — not a bundled Google library's (see [isGoogleLibraryComponent]:
     * the Firebase library's own generic components are declared in every app that merely bundles it,
     * so their presence proves nothing about whether the app's own code can receive a push). The live,
     * on-device equivalent of [pushCapabilityIsVestigial]'s manifest analysis, for an app that's
     * already installed, where the real answer is available for free instead of needing a network read.
     */
    @Suppress("DEPRECATION")
    private fun hasAppOwnedPushComponent(): Boolean = PUSH_INTENT_ACTIONS.any { action ->
        val intent = Intent(action).setPackage(packageName)
        val resolved = context.packageManager.queryIntentServices(intent, 0) +
            context.packageManager.queryBroadcastReceivers(intent, 0)
        resolved.any { info ->
            val className = info.serviceInfo?.name ?: info.activityInfo?.name
            className != null && !isGoogleLibraryComponent(className)
        }
    }

    /** The locale codes the installed APK actually ships resources for (its real, boilerplate-filtered
     *  UI languages — see [InstalledApkLocaleReader]). Empty if it can't be read. */
    private fun installedApkLocales(): List<String> =
        InstalledApkLocaleReader.fetchLocales(context.packageManager, packageName).orEmpty()

    /**
     * Whenever the app isn't installed and a device-installable package is known, resolves its real
     * supported languages by inspecting the actual (not-yet-downloaded) APK — cached by APK hash
     * ([AppRepository.cachedApkLocales]/[AppRepository.cacheApkLocales]) so the same build is only
     * ever fetched once, even across app restarts. Runs for the lifetime of the ViewModel; each new
     * distinct (state, installed) pair supersedes whatever fetch was in flight for the previous one.
     */
    private suspend fun trackRemoteApkLocales() {
        combine(state, installedInfo) { s, installed -> s to installed }
            .distinctUntilChanged()
            .collectLatest { (currentState, installed) ->
                _remoteApkLocales.value = null
                _remoteApkPending.value = false
                if (installed != null) return@collectLatest
                val success = currentState as? AppDetailState.Success ?: return@collectLatest
                val installable = success.packages
                    .selectForDevice(success.app.metadata.suggestedVersionCode)
                    ?: return@collectLatest
                _remoteApkPending.value = true
                try {
                    val (pkg, repo) = installable
                    val cached = appRepository.cachedApkLocales(pkg.apk.hash)
                    if (!cached.isNullOrEmpty()) {
                        _remoteApkLocales.value = cached
                        return@collectLatest
                    }
                    val url = repo.address.removeSuffix("/") + "/" + pkg.apk.name.removePrefix("/")
                    val locales = RemoteApkLocaleReader.fetchLocales(downloader, url) {
                        repo.authentication?.let { authentication(it.username, it.password) }
                    } ?: return@collectLatest
                    // See the comment on the empty-check in supportedLanguages above: a genuinely empty
                    // result from a *download* isn't trusted enough to assert or cache — it falls through
                    // to the store-listing approximation instead of a possibly-wrong "not translated".
                    if (locales.isEmpty()) return@collectLatest
                    appRepository.cacheApkLocales(pkg.apk.hash, locales)
                    _remoteApkLocales.value = locales
                } finally {
                    // Runs on every exit path (including the early returns above), so isLoading never
                    // gets stuck true when the fetch fails or comes back empty.
                    _remoteApkPending.value = false
                }
            }
    }

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
        _downloadTargetVersionCode.value = target.first.manifest.versionCode
        downloadJob = viewModelScope.launch { downloadAndInstall(target.first, target.second) }
    }

    /** Downloads and installs a specific release the user picked from the versions list (verifying its
     *  hash), instead of the auto-selected best one. */
    fun installVersion(pkg: Package, repo: Repo) {
        if (_downloadStatus.value != null) return
        _downloadTargetVersionCode.value = pkg.manifest.versionCode
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
                // hash is trusted. Only install if the downloaded APK matches it — this alone
                // guarantees the APK is exactly the one the (signed) index vouches for.
                //
                // We deliberately do NOT additionally check the APK's signing cert against the index's
                // declared signer. The index sometimes records a signer that no hash of the actual APK
                // certificate reproduces (e.g. microG's GmsCore on repo.microg.org), so that check
                // rejects legitimate, hash-verified apps that F-Droid itself installs fine. The hash
                // match above already ties the APK to the trusted index; the separate cross-signer
                // update guard below still prevents installing over an app signed with a different key.
                if (!partialFile.sha256Hex().equals(pkg.apk.hash, ignoreCase = true)) {
                    partialFile.delete()
                    return@withContext DownloadResult.Failed("APK verification failed (hash mismatch)")
                }
                partialFile.copyTo(Cache.getReleaseFile(context, cacheFileName), overwrite = true)
                partialFile.delete()
                DownloadResult.Ready
            }
            when (result) {
                DownloadResult.Ready -> {
                    val releaseFile = Cache.getReleaseFile(context, cacheFileName)
                    if (context.packageManager.installedWithDifferentSignature(packageName, releaseFile)) {
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

    /** True when [packageName] is a system app (or an update to one). Those can't be uninstalled, so a
     *  differently-signed catalogue version can never replace them — there's no point offering it. */
    private fun isSystemApp(packageName: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    private fun readInstalledInfo(currentState: AppDetailState): InstalledInfo? {
        val info = runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
        }.getOrNull() ?: return null
        return InstalledInfo(
            version = info.versionName.orEmpty(),
            source = context.installerSourceLabel(packageName),
            signatureMismatch = isSignatureMismatch(currentState),
        )
    }

    /**
     * True when whatever is installed under [packageName] is signed by a key none of this catalogue
     * entry's known releases declare — i.e. a *different* app happens to share this package name (a
     * de-Googled Signal fork sharing Signal's real org.thoughtcrime.securesms, say), not an actual build
     * of this app. [signerMismatch] is the one shared definition of the comparison (see
     * [com.looker.droidify.data.InstalledIdentityRepository]); this call site only differs from the
     * shared verified-installed flow in scope — it compares against EVERY known version's signers (not
     * just the suggested one), since being a build of any version of this entry counts here.
     */
    private fun isSignatureMismatch(currentState: AppDetailState): Boolean {
        val success = currentState as? AppDetailState.Success ?: return false
        val expectedSigners = success.packages.flatMapTo(mutableSetOf()) { (pkg, _) -> pkg.manifest.signer }
        val installedSigner = context.packageManager
            .getPackageInfoCompat(packageName)
            ?.singleSignature
            ?.calculateHash()
        return signerMismatch(installedSigner, expectedSigners)
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}

/** The version of this app currently installed on the device, and where it was installed from.
 *  [signatureMismatch] true means this isn't actually a build of the catalogue entry showing it — see
 *  [AppDetailViewModel.isSignatureMismatch]. */
data class InstalledInfo(
    val version: String,
    val source: String,
    val signatureMismatch: Boolean = false,
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
