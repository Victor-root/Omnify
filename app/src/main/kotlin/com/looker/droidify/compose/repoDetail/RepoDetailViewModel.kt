package com.looker.droidify.compose.repoDetail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.looker.droidify.compose.repoDetail.navigation.RepoDetail
import com.looker.droidify.compose.repoList.defaultRepoName
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.work.InstallAllWorker
import com.looker.droidify.work.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val route: RepoDetail = savedStateHandle.toRoute()
    val repoId = route.repoId

    // Same curated-name override as the repo list: never let a repo's own confusing self-declared index
    // name (e.g. Patched Apps' index names itself "langis") replace the name we deliberately picked.
    val repo = repoRepository.repo(repoId)
        .map { repo -> repo?.let { r -> defaultRepoName(r.address)?.let { r.copy(name = it) } ?: r } }
        .asStateFlow(null)

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

    /** True while any sync is running, so the Apps tab can show a spinner (rather than an empty or
     *  misleading state) while this repo's catalogue is still loading. */
    val isSyncing: StateFlow<Boolean> = SyncWorker.isSyncing(context).asStateFlow(false)

    /** How many of this repo's apps aren't installed yet — what "Install all" would fetch. Drives the
     *  button's label and whether it's shown at all. */
    val notInstalledCount: StateFlow<Int> = apps.combine(installedPackages) { apps, installed ->
        apps.count { it.packageName.name !in installed }
    }.flowOn(Dispatchers.Default).asStateFlow(0)

    /** True while a batch "install all" is downloading this repo's apps — locks the button and shows
     *  progress. */
    val isInstallingAll: StateFlow<Boolean> = InstallAllWorker.isInstalling(context, repoId).asStateFlow(false)

    /** Downloads and installs every app of this repo that isn't already installed, one after another.
     *  No-op when nothing is pending or a batch is already running. */
    fun installAll() {
        if (isInstallingAll.value) return
        val installed = installedPackages.value
        val packages = apps.value
            .map { it.packageName.name }
            .filter { it !in installed }
        InstallAllWorker.installAll(context, repoId, packages)
    }

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
