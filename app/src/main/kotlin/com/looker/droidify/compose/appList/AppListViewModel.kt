package com.looker.droidify.compose.appList

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.SuggestedVersion
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.data.model.CatalogCategory
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.sync.v2.model.DefaultName
import com.looker.droidify.utility.common.device.isTelevision
import com.looker.droidify.utility.common.extension.asStateFlow
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

@HiltViewModel
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class AppListViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val installedRepository: InstalledRepository,
    private val settingsRepository: SettingsRepository,
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

    // Favourites state
    private val _favouritesOnly = MutableStateFlow(false)
    val favouritesOnly: StateFlow<Boolean> = _favouritesOnly
    private val favouriteApps: Flow<Set<String>> = settingsRepository.get { favouriteApps }.distinctUntilChanged()

    val sortOrderFlow = settingsRepository.get { sortOrder }.asStateFlow(SortOrder.UPDATED)

    /** Whether a left/right swipe on the home grid switches tab (user setting, off by default). */
    val homeScreenSwiping = settingsRepository.get { homeScreenSwiping }.asStateFlow(false)

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

    val appsState: StateFlow<List<AppMinimal>> = combine(
        searchQueryStream,
        selectedCategories,
        sortOrderFlow,
        favouritesOnly,
        favouriteApps,
    ) { searchQuery, categories, sortOrder, favOnly, favSet ->
        AppQuery(searchQuery, categories, sortOrder, favOnly, favSet)
    }
        .combine(catalogChanges) { query, _ -> query }
        .mapLatest { query ->
            val items = appRepository.apps(
                sortOrder = query.sortOrder,
                searchQuery = query.search,
                categoriesToInclude = query.categories.toList(),
            )
            if (query.favOnly) items.filter { it.packageName.name in query.favSet } else items
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

    // A single snapshot of the installed apps — versionCode, signing fingerprint and system-app
    // status — reactive to install/uninstall. Everything the Installed/Updates tabs need, derived
    // from one package-table subscription.
    private val installedInfo: StateFlow<InstalledInfo> = installedRepository
        .getAllStream()
        .map { items ->
            InstalledInfo(
                versions = items.associate { it.packageName to it.versionCode },
                signatures = items.associate { it.packageName to it.signature },
                systemApps = items.mapNotNullTo(mutableSetOf()) {
                    it.packageName.takeIf { pkg -> isSystemApp(pkg) }
                },
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(InstalledInfo())

    /**
     * Installed packageName -> the real on-device versionName (e.g. "6.5.5-c"). Read from the
     * package manager, so the Installed/Updates tabs show what's actually installed rather than the
     * catalogue's version (which can differ, e.g. a fork installed over the upstream package).
     */
    val installedVersionNames: StateFlow<Map<String, String>> = installedRepository
        .getAllStream()
        .map { items -> items.associate { it.packageName to it.version } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyMap())

    // appId -> the catalogue version we'd install on this device (versionCode + signer fingerprints),
    // re-queried whenever the catalogue changes.
    private val suggestedVersions: StateFlow<Map<Int, SuggestedVersion>> = catalogChanges
        .mapLatest { appRepository.suggestedVersions() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyMap())

    /**
     * The Installed tab's list, kept precomputed in the background (recomputed only when the catalogue
     * or the installed set changes, NOT when the tab is selected). Switching to the tab then just reads
     * this — no filtering happens on the switch, so it's instant instead of lagging while it filters.
     */
    val installedApps: StateFlow<List<AppMinimal>> = combine(
        appsState,
        installedInfo,
    ) { apps, installed ->
        apps.filter { it.packageName.name in installed.versions }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyList())

    /** The Updates tab's list — installed apps with an available update. Precomputed like [installedApps];
     *  also drives the tab badge ([updatesCount]) so the work is done once. */
    val updatableApps: StateFlow<List<AppMinimal>> = combine(
        appsState,
        installedInfo,
        suggestedVersions,
    ) { apps, installed, suggested ->
        apps.filter { hasUpdate(it, installed, suggested) }
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

    /**
     * Whether [app] has a newer catalogue version worth offering.
     *
     * We hide an update only when it provably can't be carried out: the newer version is signed by a
     * different key than what's installed (Android refuses an in-place update across signers) *and*
     * the installed app is a system app, which can't be uninstalled to clear the conflict. For a
     * normal app the same conflict is resolvable — uninstall then reinstall, which the detail screen
     * offers — so the update still shows. When either signature is unknown we never hide a legitimate
     * update.
     */
    private fun hasUpdate(
        app: AppMinimal,
        installed: InstalledInfo,
        suggested: Map<Int, SuggestedVersion>,
    ): Boolean {
        val pkg = app.packageName.name
        val installedCode = installed.versions[pkg] ?: return false
        val suggestedVersion = suggested[app.appId.toInt()] ?: return false
        if (suggestedVersion.versionCode <= installedCode) return false

        // A newer version exists. Suppress it only when it can't replace the installed app.
        val installedSigner = installed.signatures[pkg]
        val catalogueSigners = suggestedVersion.signers
        val signerConflict = !installedSigner.isNullOrEmpty() &&
            catalogueSigners.isNotEmpty() &&
            catalogueSigners.none { it.equals(installedSigner, ignoreCase = true) }
        if (signerConflict && pkg in installed.systemApps) return false
        return true
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

    fun toggleFavouritesOnly() {
        _favouritesOnly.value = !_favouritesOnly.value
    }

    /** True while any sync runs (first launch, manual, repo-enable, periodic) — drives the bar. */
    val isSyncing: StateFlow<Boolean> = SyncWorker.isSyncing(context).asStateFlow(false)

    /** True while a batch "update all" is downloading its apps — locks the button and shows progress. */
    val isUpdatingAll: StateFlow<Boolean> = UpdateAllWorker.isUpdating(context).asStateFlow(false)

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

    /** "What's new" carousel on the Discover home — the most recently added apps. */
    val newApps: StateFlow<List<AppMinimal>> = catalogChanges
        .mapLatest { appRepository.apps(sortOrder = SortOrder.ADDED).take(DISCOVER_ROW_COUNT) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** "Recently updated" carousel on the Discover home. Restricted to apps that have actually been
     *  updated since they were added, so it isn't a duplicate of the "New apps" carousel (a brand-new
     *  app has lastUpdated == added and would otherwise head both lists identically). */
    val recentlyUpdatedApps: StateFlow<List<AppMinimal>> = catalogChanges
        .mapLatest {
            appRepository.apps(sortOrder = SortOrder.UPDATED, updatedOnly = true).take(DISCOVER_ROW_COUNT)
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** "Most downloaded" carousel on the Discover home (F-Droid v2's third curated row). Re-queries
     *  when either the catalogue or the download-stats table changes; empty until the stats worker has
     *  fetched data, so the carousel stays hidden until then. */
    val mostDownloadedApps: StateFlow<List<AppMinimal>> =
        combine(catalogChanges, statsChanges) { catalog, stats -> catalog to stats }
            .mapLatest { appRepository.mostDownloadedApps(DISCOVER_ROW_COUNT) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .asStateFlow(emptyList())

    /** "Shizuku" carousel on the Discover home — apps that declare the Shizuku permission, i.e. apps
     *  that can use Shizuku for elevated actions without root. Empty (row hidden) when none are found. */
    val shizukuApps: StateFlow<List<AppMinimal>> = catalogChanges
        .mapLatest {
            val apps = appRepository.apps(
                sortOrder = SortOrder.UPDATED,
                permissionsToInclude = listOf(SHIZUKU_PERMISSION),
            )
            shizukuFirst(apps).take(DISCOVER_ROW_COUNT)
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** "Root" carousel on the Discover home — apps that need or use root (the superuser permission or
     *  strong root phrasing in their text; see AppDao.rootApps). Empty (row hidden) when none found. */
    val rootApps: StateFlow<List<AppMinimal>> = catalogChanges
        .mapLatest { appRepository.rootApps(DISCOVER_ROW_COUNT) }
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

    /** "Made for TV" carousel — apps that declare the Android TV (leanback) launcher feature. Shown
     *  only on the TV build (the caller gates the UI too), so couch users get a row of apps that
     *  actually work with a remote. The query is skipped entirely off TV to spare the work. */
    val tvApps: StateFlow<List<AppMinimal>> = catalogChanges
        .mapLatest {
            if (!isTelevisionDevice) {
                emptyList()
            } else {
                appRepository.apps(
                    sortOrder = SortOrder.UPDATED,
                    featuresToInclude = listOf(LEANBACK_FEATURE),
                ).take(DISCOVER_ROW_COUNT)
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    /** Full app list for the carousel page the user opened via "see all" (empty when none is open).
     *  Unlike the carousels above, this isn't capped to a row — it's the whole section. */
    val openedSectionApps: StateFlow<List<AppMinimal>> =
        combine(catalogChanges, statsChanges, _openedSection) { _, _, section -> section }
            .mapLatest { section ->
                when (section) {
                    SECTION_WHATS_NEW ->
                        appRepository.apps(sortOrder = SortOrder.ADDED).take(SECTION_PAGE_LIMIT)
                    SECTION_RECENTLY_UPDATED ->
                        appRepository.apps(sortOrder = SortOrder.UPDATED, updatedOnly = true)
                            .take(SECTION_PAGE_LIMIT)
                    SECTION_MOST_DOWNLOADED -> appRepository.mostDownloadedApps(SECTION_PAGE_LIMIT)
                    SECTION_TV -> appRepository.apps(
                        sortOrder = SortOrder.UPDATED,
                        featuresToInclude = listOf(LEANBACK_FEATURE),
                    ).take(SECTION_PAGE_LIMIT)
                    SECTION_SHIZUKU -> shizukuFirst(
                        appRepository.apps(
                            sortOrder = SortOrder.UPDATED,
                            permissionsToInclude = listOf(SHIZUKU_PERMISSION),
                        ),
                    ).take(SECTION_PAGE_LIMIT)
                    SECTION_ROOT -> appRepository.rootApps(SECTION_PAGE_LIMIT)
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
        combine(catalogChanges, _expandedSections) { catalog, sections -> catalog to sections }
            .mapLatest { (_, sections) ->
                val result = mutableMapOf<String, List<AppMinimal>>()
                for (category in sections) {
                    result[category] = appRepository.apps(
                        sortOrder = SortOrder.UPDATED,
                        categoriesToInclude = listOf(category),
                    ).take(SECTION_EXPAND_LIMIT)
                }
                result.toMap()
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .asStateFlow(emptyMap())

    /** Persists the chosen sort order; [appsState] re-queries automatically. */
    fun setSortOrder(order: SortOrder) {
        viewModelScope.launch { settingsRepository.setSortOrder(order) }
    }

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
const val SECTION_WHATS_NEW = "::whats_new"
const val SECTION_RECENTLY_UPDATED = "::recently_updated"
const val SECTION_MOST_DOWNLOADED = "::most_downloaded"
const val SECTION_TV = "::tv_apps"
const val SECTION_SHIZUKU = "::shizuku"
const val SECTION_ROOT = "::root"

/** The manifest <uses-feature> an app declares when it ships an Android TV (leanback) launcher — our
 *  marker for "made for TV". */
private const val LEANBACK_FEATURE = "android.software.leanback"

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
    val sortOrder: SortOrder,
    val favOnly: Boolean,
    val favSet: Set<String>,
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
    val signatures: Map<String, String> = emptyMap(),
    val systemApps: Set<String> = emptySet(),
)

enum class AppTab { AVAILABLE, INSTALLED, UPDATES, EXTERNAL }
