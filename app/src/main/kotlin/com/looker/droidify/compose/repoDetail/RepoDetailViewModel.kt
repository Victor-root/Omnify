package com.looker.droidify.compose.repoDetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.looker.droidify.compose.repoDetail.navigation.RepoDetail
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.AppMinimal
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RepoDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repoRepository: RepoRepository,
    private val appRepository: AppRepository,
    private val installedRepository: InstalledRepository,
) : ViewModel() {

    private val route: RepoDetail = savedStateHandle.toRoute()
    val repoId = route.repoId

    val repo = repoRepository.repo(repoId).asStateFlow(null)

    /** Every app this repository serves, alphabetical — refetched whenever the catalogue changes
     *  (e.g. a sync just added/updated rows) so the tab stays live without a manual refresh. */
    val apps: StateFlow<List<AppMinimal>> = repo
        .combine(appRepository.catalogChanges) { repo, _ -> repo }
        .mapLatest { repo ->
            if (repo == null) {
                emptyList()
            } else {
                appRepository.apps(sortOrder = SortOrder.NAME, repoId = repo.id)
            }
        }
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    val installedPackages: StateFlow<Set<String>> = installedRepository.getAllStream()
        .map { items -> items.map { it.packageName }.toSet() }
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptySet())

    fun enableRepository(enable: Boolean) {
        viewModelScope.launch {
            repo.value?.let { repoRepository.enableRepository(it, enable) }
        }
    }

    fun deleteRepository(onDelete: () -> Unit) {
        viewModelScope.launch {
            repoRepository.deleteRepo(repoId)
            onDelete()
        }
    }
}
