package com.looker.droidify.compose.appList

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.InstalledIdentityRepository
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.SuggestedVersion
import com.looker.droidify.data.externalSourceOwns
import com.looker.droidify.data.hasCatalogueUpdate
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.data.model.CatalogCategory
import com.looker.droidify.data.model.excludingHidden
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.sync.v2.model.DefaultName
import com.looker.droidify.utility.common.device.isTelevision
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.work.BatchUpdateProgress
import com.looker.droidify.work.SyncWorker
import com.looker.droidify.work.UpdateAllWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One entry in the favourites full page: a catalogue app or an external one, unified so the page can
 *  sort and render either the same way (same shape as HiddenAppsViewModel's own HiddenApp). */
sealed interface FavouriteApp {
    val key: String
    val name: String

    data class Catalogue(val app: AppMinimal) : FavouriteApp {
        override val key get() = app.packageName.name
        override val name get() = app.name
    }

    data class External(val app: ExternalApp) : FavouriteApp {
        override val key get() = app.key
        override val name get() = app.label
    }
}

/** How the favourites full page orders [FavouriteApp] entries. The Discover home's own favourites
 *  carousel follows the same choice (see AppListScreen's sortedFavourites), so picking an order on
 *  the full page is reflected there too instead of the carousel keeping some order of its own. */
enum class FavouritesSortOrder { NAME, FAVOURITED_AT, INSTALLED_AT }

@HiltViewModel
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AppListViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val installedRepository: InstalledRepository,
    installedIdentityRepository: InstalledIdentityRepository,
    externalAppRepository: ExternalAppRepository,
    private val settingsRepository: SettingsRepository,
    batchProgress: BatchUpdateProgress,
    private val installManager: InstallManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    // Resolved once: the "Made for TV" carousel only queries/show on a television.
    private val isTelevisionDevice = context.isTelevision()

    val searchQuery = TextFieldState("")
    private val searchQueryStream = snapshotFlow { searchQuery.text.toString() }.debounce(300)

    val categories: StateFlow<List<CatalogCategory>> =
        appRepository.categories.asStateFlow(emptyList())

    private val _selectedCategories = MutableStateFlow<Set<DefaultName>>(emptySet())
    val selectedCategories: StateFlow<Set<DefaultName>> = _selectedCategories

    // Keys (a catalogue app's package name, or an external app's ExternalApp.key) the user has
    // favourited: the heart on any app's own detail screen, catalogue or external, writes here.
    private val favouriteKeys: Flow<Set<String>> = settingsRepository.get { favouriteApps }.distinctUntilChanged()

    /** Epoch millis each favourite (catalogue or external, same keys as [favouriteKeys]) was added,
     *  straight from the store [toggleFavourites][com.looker.droidify.datastore.SettingsRepository.toggleFavourites]
     *  writes. Drives [favouriteApps]'s own order below, and, combined with the external half from
     *  ExternalAppsViewModel, the "date favourited" option on the favourites full page. */
    val favouritedAt: StateFlow<Map<String, Long>> =
        settingsRepository.get { favouritedAt }.asStateFlow(emptyMap())

    // Apps hidden from every listing on this screen (Available/Installed/Updates, every Discover
    // carousel). See AppMinimal.excludingHidden, applied at each list-producing flow below.
    private val hiddenApps: Flow<Set<String>> = settingsRepository.get { hiddenApps }.distinctUntilChanged()

    // TV-only filter (Android TV only, see AppListScreen's header toggle). Session-only, not
    // persisted: it's a "just for this browsing session" narrowing, not a lasting preference.
    private val _tvOnly = MutableStateFlow(false)
    val tvOnly: StateFlow<Boolean> = _tvOnly

    /** Whether a left/right swipe on the home grid switches tab (user setting, off by default). */
    val homeScreenSwiping = settingsRepository.get { homeScreenSwiping }.asStateFlow(false)

    /** Whether the Discover home's favourites carousel is allowed to show at all (persisted, on by
     *  default). The carousel itself also needs a favourite to exist (see [favouriteApps] and
     *  AppListScreen's gate); this is only the user's own show/hide choice from the overflow menu, so
     *  hiding it once keeps it hidden until they show it again, even across a favourite being removed
     *  and a new one added later. */
    val showFavouritesCarousel: StateFlow<Boolean> =
        settingsRepository.get { showFavouritesCarousel }.asStateFlow(true)

    /** Flips [showFavouritesCarousel]. */
    fun toggleFavouritesCarousel() {
        viewModelScope.launch { settingsRepository.setShowFavouritesCarousel(!showFavouritesCarousel.value) }
    }

    // Emits whenever the catalogue (apps/versions) changes — e.g. after a sync — so every list
    // below re-queries automatically instead of waiting for the user to change a filter.
    //
    // The first sync grows the catalogue from 0 to thousands of rows, and Room re-emits the count on
    // every insert batch. Left un-throttled, that flood makes the list flows re-query and the grid
    // recompose continuously (and mapLatest keep cancelling/restarting the query), starving the main
    // thread — which freezes the sync spinner so the app looks crashed. We emit the first value
    // immediately (no startup delay), then sample the rest so the lists refresh a couple of times a
    // second during a sync: plenty to show progress while keeping the UI responsive.
    private val catalogChanges: StateFlow<Int> = merge(
        appRepository.catalogChanges.take(1),
        appRepository.catalogChanges.drop(1).sample(CATALOG_REFRESH_MS),
    ).distinctUntilChanged().asStateFlow(0)

    // Throttled trigger for the download-stats table — the stats worker inserts roughly one batch per
    // month, so this mirrors catalogChanges to refresh the "Most downloaded" carousel when stats land
    // without flooding the UI.
    private val statsChanges: StateFlow<Int> = merge(
        appRepository.downloadStatsChanges.take(1),
        appRepository.downloadStatsChanges.drop(1).sample(CATALOG_REFRESH_MS),
    ).distinctUntilChanged().asStateFlow(0)

    /** Package names of apps genuinely made for TV — refreshed once per catalogue change, not on every
     *  keystroke/filter change, since [appsState] below intersects it in on every recompute while [tvOnly]
     *  is on. Deliberately stricter than [tvApps]'s own carousel query: that carousel also folds in the
     *  loose [TV_RELEVANT_CATEGORIES] list ("Local Media Player", "Cast", etc.), which is a fine discovery
     *  heuristic when capped to a handful of results, but turned out far too broad ("openbar") once used
     *  uncapped as a strict hide-everything-else filter. Here we only trust the real technical signal: the
     *  app's manifest actually declaring the `android.software.leanback` feature. */
    private val tvPackageNames: StateFlow<Set<String>> = catalogChanges
        .mapLatest {
            appRepository.apps(
                // Order is irrelevant here — only used to build a membership set, never shown directly.
                sortOrder = SortOrder.UPDATED,
                featuresToInclude = listOf(LEANBACK_FEATURE),
            ).mapTo(mutableSetOf()) { it.packageName.name }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptySet())

    val appsState: StateFlow<List<AppMinimal>> = combine(
        searchQueryStream,
        selectedCategories,
        tvOnly,
    ) { searchQuery, categories, tvOnlyValue ->
        AppQuery(searchQuery, categories, tvOnlyValue)
    }
        .combine(catalogChanges) { query, _ -> query }
        .combine(tvPackageNames) { query, tvNames -> query to tvNames }
        .combine(hiddenApps) { pair, hidden -> Triple(pair.first, pair.second, hidden) }
        .mapLatest { (query, tvNames, hidden) ->
            val items = appRepository.apps(
                // The sort picker was removed; the main list always shows the freshest apps first.
                sortOrder = SortOrder.UPDATED,
                searchQuery = query.search,
                categoriesToInclude = query.categories.toList(),
            ).excludingHidden(hidden)
            if (query.tvOnly) items.filter { it.packageName.name in tvNames } else items
        }
        // Off the main thread, and skip re-emitting an identical list (the catalogue flow fires
        // repeatedly during a sync) so the grid doesn't needlessly re-diff and re-animate.
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    // ---- Tabs: Available / Installed / Updates ----

    private val _selectedTab = MutableStateFlow(AppTab.AVAILABLE)
    val selectedTab: StateFlow<AppTab> = _selectedTab

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
    }

    // A single snapshot of the installed apps (versionCode, versionName, signing fingerprint and
    // system-app status), reactive to install/uninstall. Everything the Installed/Updates tabs need,
    // derived from one package-table subscription.
    private val installedInfo: StateFlow<InstalledInfo> = installedRepository
        .getAllStream()
        .map { items ->
            InstalledInfo(
                versions = items.associate { it.packageName to it.versionCode },
                names = items.associate { it.packageName to it.version },
                signatures = items.associate { it.packageName to it.signature },
                systemApps = items.mapNotNullTo(mutableSetOf()) {
                    it.packageName.takeIf { pkg -> isSystemApp(pkg) }
                },
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(InstalledInfo())

    // packageName -> the catalogue version we'd install on this device (versionCode + signer
    // fingerprints), taken as the newest across all repos, re-queried whenever the catalogue changes.
    private val suggestedVersions: StateFlow<Map<String, SuggestedVersion>> = catalogChanges
        .mapLatest { appRepository.suggestedVersions() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyMap())

    /**
     * Installed packageName -> the real on-device versionName (e.g. "6.5.5-c"), for installed catalogue
     * apps — matched by package name via [InstalledIdentityRepository], the one shared source every
     * catalogue screen reads for its "installed" signal, so every tile/badge fed from here always
     * agrees. Deliberately not signer-verified: a differently-signed build (installed from a different
     * source than this repo) still counts as installed rather than looking uninstalled — see
     * [InstalledIdentityRepository]'s own doc comment for why, and the detail screens' own signer check
     * for where that distinction is actually surfaced (a warning, not an exclusion).
     */
    val installedVersionNames: StateFlow<Map<String, String>> = installedIdentityRepository
        .verifiedInstalled
        .map { verified -> verified.mapValues { (_, item) -> item.version } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyMap())

    /**
     * Installed packages a tracked external source, not the catalogue, is responsible for, mapped to
     * that source's [ExternalApp.key]. The rule is [externalSourceOwns], and it is that function's own
     * doc comment that explains what settles it.
     *
     * A package name is not an identity: a fork keeps the name of the app it forked, so a fork followed
     * as an external source and the original in the F-Droid catalogue are, to this app, the same
     * package. Two things read this, for that one reason. The Installed tab lists what is on the device,
     * and it built that list from the catalogue alone, so a fork installed from its own source opened
     * the original's page: the wrong version, the wrong changelog, and an update button offering a build
     * that isn't a newer version of what is actually installed (reported for an AdAway fork against
     * F-Droid's own AdAway). And the catalogue stands aside from updating such a package
     * ([com.looker.droidify.data.hasCatalogueUpdate]), since its entry describes a different build, made
     * somewhere else, numbered on its own terms.
     *
     * A copy that matches the catalogue and that no source claims is left alone, even when a source
     * happens to track the same package, since the catalogue entry then genuinely describes what is
     * installed.
     */
    val externallyInstalledPackages: StateFlow<Map<String, String>> = combine(
        externalAppRepository.apps,
        installedInfo,
        installedVersionNames,
        suggestedVersions,
    ) { externalApps, installed, installedNames, suggested ->
        externalApps.mapNotNull { app ->
            val pkg = app.packageName ?: return@mapNotNull null
            if (pkg !in installed.versions) return@mapNotNull null
            val owns = externalSourceOwns(
                ownsInstalled = app.ownsInstalled(installedNames[pkg]),
                installedSigner = installed.signatures[pkg],
                catalogueSigners = suggested[pkg]?.signers.orEmpty(),
            )
            if (!owns) return@mapNotNull null
            pkg to app.key
        }.toMap()
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyMap())

    /**
     * The Installed tab's list, kept precomputed in the background (recomputed only when the catalogue
     * or the installed set changes, NOT when the tab is selected). Switching to the tab then just reads
     * this — no filtering happens on the switch, so it's instant instead of lagging while it filters.
     * Membership comes from the same verified set as [installedVersionNames], so the tab and the tile
     * badges can never disagree.
     */
    val installedApps: StateFlow<List<AppMinimal>> = combine(
        appsState,
        installedVersionNames,
    ) { apps, installed ->
        apps.filter { it.packageName.name in installed }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyList())

    /** The Updates tab's list — installed apps with an available update. Precomputed like [installedApps];
     *  also drives the tab badge ([updatesCount]) so the work is done once. */
    val updatableApps: StateFlow<List<AppMinimal>> = combine(
        appsState,
        installedInfo,
        suggestedVersions,
        externallyInstalledPackages,
    ) { apps, installed, suggested, externallyOwned ->
        apps.filter { hasUpdate(it, installed, suggested, externallyOwned.keys) }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyList())

    /**
     * Apps shown for the current tab. Search / sort / category / favourites filters are already applied
     * by [appsState]; this only picks the precomputed list for the selected tab, so a tab switch is a
     * cheap selection rather than a fresh filter pass.
     */
    val displayedApps: StateFlow<List<AppMinimal>> = combine(
        _selectedTab,
        appsState,
        installedApps,
        updatableApps,
    ) { tab, available, installed, updatable ->
        when (tab) {
            AppTab.AVAILABLE -> available
            AppTab.INSTALLED -> installed
            AppTab.UPDATES -> updatable
            // The External tab renders its own (non-F-Droid) list, so the catalogue list is empty.
            AppTab.EXTERNAL -> emptyList()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyList())

    /** Number of installed apps with an available, installable update (shown on the Updates tab). */
    val updatesCount: StateFlow<Int> = updatableApps
        .map { it.size }
        .distinctUntilChanged()
        .asStateFlow(0)

    /** Whether [app] has a newer catalogue version worth offering. The rule itself lives in
     *  [hasCatalogueUpdate], shared with the automatic update installer, which has no ViewModel to read
     *  it from; this only unpacks [installed] for it. */
    private fun hasUpdate(
        app: AppMinimal,
        installed: InstalledInfo,
        suggested: Map<String, SuggestedVersion>,
        externallyOwned: Set<String>,
    ): Boolean {
        val pkg = app.packageName.name
        return hasCatalogueUpdate(
            installedVersionCode = installed.versions[pkg],
            installedVersionName = installed.names[pkg],
            installedSigner = installed.signatures[pkg],
            isSystemApp = pkg in installed.systemApps,
            installedFromExternalSource = pkg in externallyOwned,
            suggested = suggested[pkg],
        )
    }

    private fun isSystemApp(packageName: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    // A curated carousel ("New apps" / "Recently updated" / "Most downloaded") opened as its own full
    // page via its "see all" arrow; null = the Discover home. (Categories, by contrast, expand inline
    // — see [expandedSections].)
    private val _openedSection = MutableStateFlow<String?>(null)
    val openedSection: StateFlow<String?> = _openedSection

    /** Opens a curated carousel as its own full page. */
    fun openSection(key: String) {
        _openedSection.value = key
    }

    /** Returns from a carousel's "see all" page to the Discover home. */
    fun closeSection() {
        _openedSection.value = null
    }

    fun toggleTvOnly() {
        _tvOnly.value = !_tvOnly.value
    }

    // How the favourites full page is sorted right now. Session-only like tvOnly above, and scoped to
    // that one page: it never touches favouriteApps' own order, so leaving the page resets nothing and
    // affects nothing else.
    private val _favouritesSortOrder = MutableStateFlow(FavouritesSortOrder.FAVOURITED_AT)
    val favouritesSortOrder: StateFlow<FavouritesSortOrder> = _favouritesSortOrder

    fun setFavouritesSortOrder(order: FavouritesSortOrder) {
        _favouritesSortOrder.value = order
    }

    /** True while any sync runs (first launch, manual, repo-enable, periodic) — drives the bar. */
    val isSyncing: StateFlow<Boolean> = SyncWorker.isSyncing(context).asStateFlow(false)

    /** True while a batch "update all" is downloading its apps — locks the button and shows progress. */
    val isUpdatingAll: StateFlow<Boolean> = UpdateAllWorker.isUpdating(context).asStateFlow(false)

    /** The package currently being updated by a running "update all" batch (null when idle), so the
     *  Updates tab shows a live spinner on that app and moves to the next in real time. */
    val updatingPackage: StateFlow<String?> = UpdateAllWorker.currentPackage(context).asStateFlow(null)

    /**
     * The same batch, with its byte counts and its place in the run, so the tile being updated can
     * show how far along it actually is and the "update all" button how far the whole batch has come.
     * Distinct from [updatingPackage], which comes from WorkManager and so survives the app's process
     * being restarted: this one is in memory (see [BatchUpdateProgress]) and is simply null in that
     * case, which leaves the tile on its plain spinner rather than showing nothing.
     */
    val batchUpdate: StateFlow<BatchUpdateProgress.State?> = batchProgress.state

    /**
     * Downloads and installs every app currently listed on the Updates tab. The list is already
     * filtered to installable updates ([updatableApps]); the worker resolves and installs each, one
     * after another, skipping any that can't update in place (a different signer). No-op when nothing
     * is pending or a batch is already running.
     */
    fun updateAll() {
        if (isUpdatingAll.value) return
        val packages = updatableApps.value.map { it.packageName.name }
        UpdateAllWorker.updateAll(context, packages)
    }

    /**
     * Stops a running batch update: the downloads, and the installs already handed to the queue.
     *
     * Both halves matter. Cancelling the worker alone leaves whatever it already queued still going,
     * and an install waiting on a system confirmation the user never answered (they left the prompt,
     * so Android reports nothing back) holds every install behind it until the queue's own ten-minute
     * safety timeout. That is a batch that looks frozen with nothing on screen able to end it, which
     * is exactly what this offers a way out of.
     */
    fun cancelUpdateAll() {
        UpdateAllWorker.cancel(context)
        installManager.cancelAll()
    }

    /** Every catalogue app the user has favourited (the heart on its own detail screen), feeding both
     *  the favourites full page and, after AppListScreen re-sorts it by [FavouritesSortOrder], the
     *  Discover home's own favourites carousel. Unlike a curated discovery row this is a list the user
     *  built on purpose, so it isn't capped to [DISCOVER_ROW_COUNT]: nothing they favourited should
     *  hide behind a "see all" tap. Empty until a favourite exists, which is also what keeps the
     *  carousel itself off the Discover home until then (see AppListScreen). This flow's own
     *  most-recently-favourited-first order is only ever the tie-break base for that re-sort now (e.g.
     *  two favourites added the very same millisecond), not what either place actually displays. */
    val favouriteApps: StateFlow<List<AppMinimal>> = catalogChanges.combine(hiddenApps) { _, hidden -> hidden }
        .combine(favouriteKeys) { hidden, favourites -> hidden to favourites }
        .combine(favouritedAt) { (hidden, favourites), timestamps -> Triple(hidden, favourites, timestamps) }
        .mapLatest { (hidden, favourites, timestamps) ->
            if (favourites.isEmpty()) {
                emptyList()
            } else {
                // NAME is just a stable base order for apps with no recorded timestamp (favourited
                // before this was tracked, or two favourited the same millisecond) to tie-break on,
                // rather than the arbitrary order the catalogue query happens to return.
                appRepository.apps(sortOrder = SortOrder.NAME)
                    .excludingHidden(hidden)
                    .filter { it.packageName.name in favourites }
                    .sortedByDescending { timestamps[it.packageName.name] ?: 0L }
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** Catalogue favourites' on-device install date (epoch millis), read live from PackageManager:
     *  purely an ordering signal for the favourites full page's "date installed" sort. A favourite
     *  that was never installed simply has no entry here rather than a fabricated date. */
    val favouriteInstallDates: StateFlow<Map<String, Long>> = favouriteApps
        .map { apps ->
            apps.mapNotNull { app ->
                val pkg = app.packageName.name
                val installedAt = context.packageManager.getPackageInfoCompat(pkg, 0)?.firstInstallTime
                installedAt?.let { pkg to it }
            }.toMap()
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyMap())

    /** "What's new" carousel on the Discover home — the most recently added apps. */
    val newApps: StateFlow<List<AppMinimal>> = catalogChanges.combine(hiddenApps) { _, hidden -> hidden }
        .mapLatest { hidden ->
            appRepository.apps(sortOrder = SortOrder.ADDED).excludingHidden(hidden).take(DISCOVER_ROW_COUNT)
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** "Recently updated" carousel on the Discover home. Restricted to apps that have actually been
     *  updated since they were added, so it isn't a duplicate of the "New apps" carousel (a brand-new
     *  app has lastUpdated == added and would otherwise head both lists identically). */
    val recentlyUpdatedApps: StateFlow<List<AppMinimal>> = catalogChanges.combine(hiddenApps) { _, hidden -> hidden }
        .mapLatest { hidden ->
            appRepository.apps(sortOrder = SortOrder.UPDATED, updatedOnly = true)
                .excludingHidden(hidden)
                .take(DISCOVER_ROW_COUNT)
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** "Most downloaded" carousel on the Discover home (F-Droid v2's third curated row). Re-queries
     *  when either the catalogue or the download-stats table changes; empty until the stats worker has
     *  fetched data, so the carousel stays hidden until then. */
    val mostDownloadedApps: StateFlow<List<AppMinimal>> =
        combine(catalogChanges, statsChanges, hiddenApps) { _, _, hidden -> hidden }
            .mapLatest { hidden -> appRepository.mostDownloadedApps(DISCOVER_ROW_COUNT).excludingHidden(hidden) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .asStateFlow(emptyList())

    /** "Shizuku" carousel on the Discover home — apps that declare the Shizuku permission, i.e. apps
     *  that can use Shizuku for elevated actions without root. Empty (row hidden) when none are found. */
    val shizukuApps: StateFlow<List<AppMinimal>> = catalogChanges.combine(hiddenApps) { _, hidden -> hidden }
        .mapLatest { hidden ->
            val apps = appRepository.apps(
                sortOrder = SortOrder.UPDATED,
                permissionsToInclude = listOf(SHIZUKU_PERMISSION),
            )
            shizukuFirst(apps).excludingHidden(hidden).take(DISCOVER_ROW_COUNT)
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** "Root" carousel on the Discover home — apps that need or use root (the superuser permission or
     *  strong root phrasing in their text; see AppDao.rootApps). Empty (row hidden) when none found. */
    val rootApps: StateFlow<List<AppMinimal>> = catalogChanges.combine(hiddenApps) { _, hidden -> hidden }
        .mapLatest { hidden -> appRepository.rootApps(DISCOVER_ROW_COUNT).excludingHidden(hidden) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** Puts the Shizuku app itself at the head of its own "Works with Shizuku" list, so the section
     *  reads coherently. Shizuku defines the permission rather than requesting it, so it may not match
     *  the filter — fetch it by package when it's missing, then prepend (deduping if already present). */
    private suspend fun shizukuFirst(apps: List<AppMinimal>): List<AppMinimal> {
        val shizuku = apps.firstOrNull { it.packageName.name == SHIZUKU_PACKAGE }
            ?: appRepository.apps(
                sortOrder = SortOrder.UPDATED,
                searchQuery = SHIZUKU_PACKAGE,
            ).firstOrNull { it.packageName.name == SHIZUKU_PACKAGE }
            ?: return apps
        return listOf(shizuku) + apps.filter { it.packageName.name != SHIZUKU_PACKAGE }
    }

    /** "Made for TV" carousel — apps that declare the Android TV (leanback) launcher feature, OR are
     *  tagged with a genuinely TV-relevant category (see [TV_RELEVANT_CATEGORIES]). The manifest feature
     *  alone is nearly unused across the whole F-Droid catalogue (checked against the live index: 3
     *  packages total, main + archive + IzzyOnDroid combined) — most apps that work great on TV (VLC,
     *  Kodi, Jellyfin, mpv, …) simply never declare it, since F-Droid doesn't require Play Store TV
     *  compliance. The category union surfaces those real, already-catalogued apps instead of leaving the
     *  carousel almost empty. Shown only on the TV build (the caller gates the UI too), so couch users
     *  get a row of apps that actually work with a remote. The query is skipped entirely off TV to spare
     *  the work. */
    val tvApps: StateFlow<List<AppMinimal>> = catalogChanges.combine(hiddenApps) { _, hidden -> hidden }
        .mapLatest { hidden ->
            if (!isTelevisionDevice) {
                emptyList()
            } else {
                appRepository.apps(
                    sortOrder = SortOrder.UPDATED,
                    featuresToInclude = listOf(LEANBACK_FEATURE),
                    categoriesToInclude = TV_RELEVANT_CATEGORIES,
                    featuresOrCategories = true,
                ).excludingHidden(hidden).take(DISCOVER_ROW_COUNT)
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** Full app list for the carousel page the user opened via "see all" (empty when none is open).
     *  Unlike the carousels above, this isn't capped to a row — it's the whole section. */
    val openedSectionApps: StateFlow<List<AppMinimal>> =
        combine(catalogChanges, statsChanges, _openedSection, hiddenApps) { _, _, section, hidden -> section to hidden }
            .mapLatest { (section, hidden) ->
                when (section) {
                    SECTION_WHATS_NEW ->
                        appRepository.apps(sortOrder = SortOrder.ADDED).excludingHidden(hidden).take(SECTION_PAGE_LIMIT)
                    SECTION_RECENTLY_UPDATED ->
                        appRepository.apps(sortOrder = SortOrder.UPDATED, updatedOnly = true)
                            .excludingHidden(hidden)
                            .take(SECTION_PAGE_LIMIT)
                    SECTION_MOST_DOWNLOADED ->
                        appRepository.mostDownloadedApps(SECTION_PAGE_LIMIT).excludingHidden(hidden)
                    SECTION_TV -> appRepository.apps(
                        sortOrder = SortOrder.UPDATED,
                        featuresToInclude = listOf(LEANBACK_FEATURE),
                        categoriesToInclude = TV_RELEVANT_CATEGORIES,
                        featuresOrCategories = true,
                    ).excludingHidden(hidden).take(SECTION_PAGE_LIMIT)
                    SECTION_SHIZUKU -> shizukuFirst(
                        appRepository.apps(
                            sortOrder = SortOrder.UPDATED,
                            permissionsToInclude = listOf(SHIZUKU_PERMISSION),
                        ),
                    ).excludingHidden(hidden).take(SECTION_PAGE_LIMIT)
                    SECTION_ROOT -> appRepository.rootApps(SECTION_PAGE_LIMIT).excludingHidden(hidden)
                    else -> emptyList()
                }
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .asStateFlow(emptyList())

    // Categories the user expanded inline in the accordion (keyed by category name). Their app lists
    // load on demand and collapse when the chevron is toggled off.
    private val _expandedSections = MutableStateFlow<Set<String>>(emptySet())
    val expandedSections: StateFlow<Set<String>> = _expandedSections

    fun toggleSection(key: String) {
        _expandedSections.value = _expandedSections.value.let {
            if (key in it) it - key else it + key
        }
    }

    /** Apps for each currently-expanded category (capped), re-loaded when the catalogue changes. */
    val expandedSectionApps: StateFlow<Map<String, List<AppMinimal>>> =
        combine(catalogChanges, _expandedSections, hiddenApps) { _, sections, hidden -> sections to hidden }
            .mapLatest { (sections, hidden) ->
                val result = mutableMapOf<String, List<AppMinimal>>()
                for (category in sections) {
                    result[category] = appRepository.apps(
                        sortOrder = SortOrder.UPDATED,
                        categoriesToInclude = listOf(category),
                    ).excludingHidden(hidden).take(SECTION_EXPAND_LIMIT)
                }
                result.toMap()
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .asStateFlow(emptyMap())

    /**
     * Manually re-syncs all enabled repositories through the same worker as every other sync, so the
     * in-app bar and the notification both show. Lists refresh automatically afterwards.
     */
    fun sync() {
        SyncWorker.enqueueUserSync(context)
    }
}

private const val DISCOVER_ROW_COUNT = 16

/** Section keys for the inline-expandable Discover sections (the "::" prefix can't collide with a
 *  category name). */
const val SECTION_FAVOURITES = "::favourites"
const val SECTION_WHATS_NEW = "::whats_new"
const val SECTION_RECENTLY_UPDATED = "::recently_updated"
const val SECTION_MOST_DOWNLOADED = "::most_downloaded"
const val SECTION_TV = "::tv_apps"
const val SECTION_SHIZUKU = "::shizuku"
const val SECTION_ROOT = "::root"

/** The manifest <uses-feature> an app declares when it ships an Android TV (leanback) launcher — our
 *  marker for "made for TV". */
private const val LEANBACK_FEATURE = "android.software.leanback"

/** F-Droid category defaultNames that are a reliable "this is genuinely useful on TV" signal even for
 *  apps that never bother declaring [LEANBACK_FEATURE] (see tvApps' doc comment) — media playback,
 *  emulation and remote-control apps are used on a couch with a D-pad far more than any other category. */
private val TV_RELEVANT_CATEGORIES = listOf(
    "Local Media Player",
    "Online Media Player",
    "Cast",
    "Emulator",
    "Remote Controller",
)

/** The manifest <uses-permission> an app declares to talk to Shizuku — our marker for "uses Shizuku". */
private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

/** Shizuku's own package, pinned to the front of the "Works with Shizuku" section. */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** Cap on apps shown when a category is expanded inline (a quick "see more", not the whole list). */
private const val SECTION_EXPAND_LIMIT = 40

/** Cap on a carousel's full "see all" page — plenty to browse, while keeping the list small enough
 *  that opening/closing the page stays smooth (the whole catalogue would be thousands of rows). */
private const val SECTION_PAGE_LIMIT = 200

/** How often catalogue changes are allowed to refresh the lists (throttles the first-sync flood). */
private const val CATALOG_REFRESH_MS = 500L

private data class AppQuery(
    val search: String,
    val categories: Set<DefaultName>,
    val tvOnly: Boolean,
)

/**
 * A snapshot of the installed apps used to drive the Installed/Updates tabs, all derived from one
 * package-table subscription:
 *  - [versions]   packageName -> installed versionCode (compared against the catalogue)
 *  - [signatures] packageName -> installed signing-cert fingerprint (lowercase hex SHA-256)
 *  - [systemApps] packages that are system apps (or updates to one) — can't be uninstalled, so a
 *                 differently-signed catalogue version can never replace them.
 */
private data class InstalledInfo(
    val versions: Map<String, Long> = emptyMap(),
    // The version as its publisher writes it, alongside the code Android orders builds by: the two
    // answer different questions once a package can come from more than one place. See
    // [com.looker.droidify.data.catalogueBuildIsOlder].
    val names: Map<String, String> = emptyMap(),
    val signatures: Map<String, String> = emptyMap(),
    val systemApps: Set<String> = emptySet(),
)

enum class AppTab { AVAILABLE, INSTALLED, UPDATES, EXTERNAL }
