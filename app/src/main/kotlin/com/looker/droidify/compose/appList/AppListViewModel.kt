package com.looker.droidify.compose.appList

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.sync.v2.model.DefaultName
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.work.SyncWorker
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

    val searchQuery = TextFieldState("")
    private val searchQueryStream = snapshotFlow { searchQuery.text.toString() }.debounce(300)

    val categories = appRepository.categories.asStateFlow(emptyList())

    private val _selectedCategories = MutableStateFlow<Set<DefaultName>>(emptySet())
    val selectedCategories: StateFlow<Set<DefaultName>> = _selectedCategories

    // Favourites state
    private val _favouritesOnly = MutableStateFlow(false)
    val favouritesOnly: StateFlow<Boolean> = _favouritesOnly
    private val favouriteApps: Flow<Set<String>> = settingsRepository.get { favouriteApps }.distinctUntilChanged()

    val sortOrderFlow = settingsRepository.get { sortOrder }.asStateFlow(SortOrder.UPDATED)

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

    // installed packageName -> installed versionCode (reactive to install/uninstall)
    private val installedVersions: StateFlow<Map<String, Long>> = installedRepository
        .getAllStream()
        .map { items -> items.associate { it.packageName to it.versionCode } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyMap())

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

    // appId -> latest available versionCode (re-queried whenever the catalogue changes)
    private val suggestedVersions: StateFlow<Map<Int, Long>> = catalogChanges
        .mapLatest { appRepository.suggestedVersionCodes() }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyMap())

    /**
     * Apps shown for the current tab. Search / sort / category / favourites filters are already
     * applied by [appsState]; this only narrows the list to the selected tab.
     */
    val displayedApps: StateFlow<List<AppMinimal>> = combine(
        appsState,
        _selectedTab,
        installedVersions,
        suggestedVersions,
    ) { apps, tab, installed, suggested ->
        when (tab) {
            AppTab.AVAILABLE -> apps
            AppTab.INSTALLED -> apps.filter { it.packageName.name in installed }
            AppTab.UPDATES -> apps.filter { hasUpdate(it, installed, suggested) }
            // The External tab renders its own (non-F-Droid) list, so the catalogue list is empty.
            AppTab.EXTERNAL -> emptyList()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(emptyList())

    /** Number of installed apps with an available update (shown on the Updates tab). */
    val updatesCount: StateFlow<Int> = combine(
        appsState,
        installedVersions,
        suggestedVersions,
    ) { apps, installed, suggested ->
        apps.count { hasUpdate(it, installed, suggested) }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).asStateFlow(0)

    private fun hasUpdate(
        app: AppMinimal,
        installed: Map<String, Long>,
        suggested: Map<Int, Long>,
    ): Boolean {
        val installedCode = installed[app.packageName.name] ?: return false
        val latestCode = suggested[app.appId.toInt()] ?: return false
        return latestCode > installedCode
    }

    fun toggleCategory(category: DefaultName) {
        val currentCategories = _selectedCategories.value
        _selectedCategories.value = if (currentCategories.contains(category)) {
            currentCategories - category
        } else {
            currentCategories + category
        }
    }

    fun toggleFavouritesOnly() {
        _favouritesOnly.value = !_favouritesOnly.value
    }

    /** True while any sync runs (first launch, manual, repo-enable, periodic) — drives the bar. */
    val isSyncing: StateFlow<Boolean> = SyncWorker.isSyncing(context).asStateFlow(false)

    /** A small "What's new" showcase (most recently added apps) for the top of the home. */
    val newApps: StateFlow<List<AppMinimal>> = catalogChanges
        .mapLatest { appRepository.apps(sortOrder = SortOrder.ADDED).take(NEW_APPS_COUNT) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

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

private const val NEW_APPS_COUNT = 12

/** How often catalogue changes are allowed to refresh the lists (throttles the first-sync flood). */
private const val CATALOG_REFRESH_MS = 500L

private data class AppQuery(
    val search: String,
    val categories: Set<DefaultName>,
    val sortOrder: SortOrder,
    val favOnly: Boolean,
    val favSet: Set<String>,
)

enum class AppTab { AVAILABLE, INSTALLED, UPDATES, EXTERNAL }
