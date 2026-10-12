package com.looker.droidify.compose.welcome

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.compose.repoList.defaultRepoName
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.Repo
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A source as the welcome page shows it. [repo] is null for Omnify's own update channel, which is an
 *  external source rather than a repository. */
data class WelcomeItem(
    val id: String,
    val name: String,
    @StringRes val description: Int,
    val checked: Boolean,
    val repo: Repo?,
)

/**
 * What the welcome page of a fresh install needs: the recommended sources with their ticks, and the
 * one action that ends the page by switching on whatever is still ticked, which is also what starts the
 * first synchronisation.
 *
 * The ticks live in the saved state: giving Omnify the permission to install apps can make Android
 * close it (see allow_unknown_sources_DESC), and the person coming back should find their choices as
 * they left them.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repoRepository: RepoRepository,
    private val externalAppRepository: ExternalAppRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val checkedIds: StateFlow<ArrayList<String>> =
        savedStateHandle.getStateFlow(KEY_CHECKED, WelcomeCatalog.defaultChecked())

    /** The recommended sources that exist yet: they are seeded on the first start, in parallel with this
     *  page opening, so a source simply appears in the list the moment it is there. */
    val items: StateFlow<List<WelcomeItem>> = combine(
        repoRepository.repos,
        externalAppRepository.apps,
        checkedIds,
    ) { repos, externalApps, checked ->
        val reposByAddress = repos.associateBy { it.address.trimEnd('/') }
        val omnify = externalApps.firstOrNull { it.key == ExternalApp.OMNIFY_REPO_KEY }
        WelcomeCatalog.sources.mapNotNull { source ->
            val repo = reposByAddress[source.id]
            val name = when {
                source.id == ExternalApp.OMNIFY_REPO_KEY -> omnify?.label
                else -> repo?.let { defaultRepoName(it.address) ?: it.name }
            } ?: return@mapNotNull null
            WelcomeItem(
                id = source.id,
                name = name,
                description = source.description,
                checked = source.id in checked,
                repo = repo,
            )
        }
    }.flowOn(Dispatchers.Default).asStateFlow(emptyList())

    private val _finishing = MutableStateFlow(false)

    /** True from the moment the page is being ended, so a second tap cannot end it twice. */
    val finishing: StateFlow<Boolean> = _finishing

    fun toggle(id: String) {
        val current = checkedIds.value
        setChecked(if (id in current) current - id else current + id)
    }

    fun setAll(checked: Boolean) {
        setChecked(if (checked) WelcomeCatalog.sources.map { it.id } else emptyList())
    }

    /**
     * Switches on what is ticked (each repository's own synchronisation starts with it), records that
     * the page has been seen, then calls [onDone]. Nothing ticked is a normal way out: nothing is
     * switched on and nothing is synchronised.
     */
    fun finish(onDone: () -> Unit) {
        if (!_finishing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val checked = checkedIds.value
                val reposByAddress = repoRepository.repos.first().associateBy { it.address.trimEnd('/') }
                checked.mapNotNull { reposByAddress[it] }.forEach {
                    repoRepository.enableRepository(it, enable = true)
                }
                externalAppRepository.getApps()
                    .firstOrNull { it.key == ExternalApp.OMNIFY_REPO_KEY }
                    ?.let {
                        externalAppRepository.upsertApp(
                            it.copy(enabled = ExternalApp.OMNIFY_REPO_KEY in checked),
                        )
                    }
                FirstRun.finishWelcome(context)
                onDone()
            } finally {
                _finishing.value = false
            }
        }
    }

    private fun setChecked(ids: List<String>) {
        savedStateHandle[KEY_CHECKED] = ArrayList(ids)
    }

    private companion object {
        const val KEY_CHECKED = "checked"
    }
}
