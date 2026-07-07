package com.looker.droidify.compose.repoList

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.Repo
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.work.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RepoListViewModel @Inject constructor(
    private val repository: RepoRepository,
    private val appRepository: AppRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    // A single-app repo's own declared icon is often unusable (many self-hosted repos never customise
    // it and fdroidserver defaults to a QR code of the repo address); its one app's real launcher icon
    // is always the better logo. Refetched whenever the catalogue changes, e.g. right after a repo's
    // first sync populates its app.
    private val singleAppIcons = appRepository.catalogChanges
        .mapLatest { appRepository.singleAppRepoIcons() }
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyMap())

    val stream: StateFlow<List<Repo>> = combine(repository.repos, singleAppIcons) { repos, icons ->
        repos.map { repo ->
            // Never touch a repo that already has a curated logo: some of those (e.g. Cromite) are
            // curated precisely because the repo itself is unreachable to a non-browser client for
            // anything under its /repo path, including its apps' own icons — so a single-app override
            // there could replace a working hand-picked logo with a URL that fails to load.
            val hasCuratedIcon = defaultRepoIcon(repo.address) != null || defaultRepoIconRes(repo.address) != null
            val withIcon = if (hasCuratedIcon) repo else icons[repo.id]?.let { repo.copy(icon = it) } ?: repo
            // Same idea for the name: a repo's own self-declared index name can be confusing (Patched
            // Apps' index names itself "langis", the maintainer's handle) — the curated name, once set,
            // must never regress back to that the moment the repo is synced.
            defaultRepoName(repo.address)?.let { withIcon.copy(name = it) } ?: withIcon
        }
    }.flowOn(Dispatchers.Default).asStateFlow(emptyList())

    /** True while a sync runs — e.g. right after enabling a repo here. Drives the progress bar. */
    val isSyncing: StateFlow<Boolean> = SyncWorker.isSyncing(context).asStateFlow(false)

    fun toggleRepo(repo: Repo) {
        viewModelScope.launch {
            repository.enableRepository(repo, !repo.enabled)
        }
    }

    fun deleteRepo(repoId: Int) {
        viewModelScope.launch {
            repository.deleteRepo(repoId)
        }
    }
}
