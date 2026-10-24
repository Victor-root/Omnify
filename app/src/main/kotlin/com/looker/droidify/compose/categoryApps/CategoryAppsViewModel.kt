package com.looker.droidify.compose.categoryApps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import com.looker.droidify.compose.categoryApps.navigation.CategoryApps
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.InstalledIdentityRepository
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.data.model.excludingHidden
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryAppsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    appRepository: AppRepository,
    installedIdentityRepository: InstalledIdentityRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val defaultName = savedStateHandle.toRoute<CategoryApps>().defaultName

    /** The category's name in the app's language, as the Explore tab shows it. */
    val title: StateFlow<String> = appRepository.categories
        .map { categories -> categories.firstOrNull { it.defaultName == defaultName }?.name ?: defaultName }
        .asStateFlow(defaultName)

    /** All of the category's apps, the user's own repositories' first, as its "see all" page in Explore
     *  lists them; null until first read. Read again whenever the catalogue changes. */
    val apps: StateFlow<List<AppMinimal>?> = appRepository.catalogChanges
        .combine(settingsRepository.get { hiddenApps }) { _, hidden -> hidden }
        .mapLatest { hidden ->
            appRepository.apps(
                sortOrder = SortOrder.UPDATED,
                categoriesToInclude = listOf(defaultName),
                ownReposFirst = true,
            ).excludingHidden(hidden)
        }
        .flowOn(Dispatchers.Default)
        .asStateFlow(null)

    /** Installed package names, for the tiles' checkmarks (see [InstalledIdentityRepository]). */
    val installedPackages: StateFlow<Set<String>> = installedIdentityRepository.verifiedInstalled
        .map { it.keys }
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptySet())
}
