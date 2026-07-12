package com.looker.droidify.compose.repoList

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.Repo
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.utility.common.extension.asStateFlow
import com.looker.droidify.work.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RepoListViewModel @Inject constructor(
    private val repository: RepoRepository,
    private val appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    /** Section keys ("external", "fdroid", "omnify_picks") the user has collapsed on this screen —
     *  sections start expanded, collapsing one is remembered across app restarts. */
    val collapsedSections: StateFlow<Set<String>> =
        settingsRepository.get { collapsedRepoSections }.asStateFlow(emptySet())

    fun toggleSectionCollapsed(sectionKey: String) {
        viewModelScope.launch { settingsRepository.toggleRepoSectionCollapsed(sectionKey) }
    }

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

    /** Repo ids whose own sync is currently enqueued/running — set optimistically the moment their
     *  toggle is tapped, cleared once [SyncWorker.isSyncingRepo] reports that repo's sync is done. Lets
     *  a row show its own progress (e.g. around the enable toggle) instead of only the screen-wide bar,
     *  so enabling several repos in quick succession shows each one's own status. */
    private val _syncingRepoIds = MutableStateFlow<Set<Int>>(emptySet())
    val syncingRepoIds: StateFlow<Set<Int>> = _syncingRepoIds

    fun toggleRepo(repo: Repo) {
        val enabling = !repo.enabled
        if (enabling) {
            _syncingRepoIds.value = _syncingRepoIds.value + repo.id
            viewModelScope.launch {
                // Wait for this repo's own sync to actually start (it may sit enqueued behind others
                // toggled on just before it — see SyncWorker's APPEND_OR_REPLACE chaining), then clear
                // once it's done, rather than guessing a fixed duration. Bounded: a sync that completes
                // before this collector even attaches (never observed running) would otherwise wait
                // forever for a "started" signal that already came and went.
                withTimeoutOrNull(SYNC_WAIT_TIMEOUT_MS) {
                    SyncWorker.isSyncingRepo(context, repo.id).dropWhile { !it }.first { !it }
                }
                _syncingRepoIds.value = _syncingRepoIds.value - repo.id
            }
        } else {
            _syncingRepoIds.value = _syncingRepoIds.value - repo.id
        }
        viewModelScope.launch {
            repository.enableRepository(repo, enabling)
        }
    }

    fun deleteRepo(repoId: Int) {
        viewModelScope.launch {
            repository.deleteRepo(repoId)
        }
    }

    private companion object {
        /** Safety net for [toggleRepo]'s wait on [SyncWorker.isSyncingRepo]: long enough for a real
         *  sync (queued behind others, a big index, a slow connection), short enough that a row can't
         *  spin forever if the "started" signal was somehow missed. */
        const val SYNC_WAIT_TIMEOUT_MS = 5 * 60_000L
    }
}
