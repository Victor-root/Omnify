package com.looker.droidify.compose.repoList

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.Repo
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.work.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RepoListViewModel @Inject constructor(
    private val repository: RepoRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val stream = repository.repos
        .asStateFlow(emptyList())

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
