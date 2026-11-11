package com.looker.droidify.compose.settings.exploreCarousels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.compose.appList.CURATED_CAROUSELS
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.model.CatalogCategory
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Explore tab's carousels: which show, in what order, and what else could. */
@HiltViewModel
class ExploreCarouselsViewModel @Inject constructor(
    appRepository: AppRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** The carousels Explore shows, in order; see [com.looker.droidify.datastore.Settings.exploreCarousels]. */
    val shown: StateFlow<List<String>> =
        settingsRepository.get { exploreCarousels ?: CURATED_CAROUSELS }.asStateFlow(emptyList())

    /** Whether Explore is on Omnify's own arrangement, which there is then nothing to reset to. */
    val isDefault: StateFlow<Boolean> = settingsRepository.get { exploreCarousels == null }.asStateFlow(true)

    /** Every category of the catalogue, any of which can become a carousel. */
    val categories: StateFlow<List<CatalogCategory>> = appRepository.categories.asStateFlow(emptyList())

    /** Moves [key] one place up ([by] -1) or down ([by] 1). */
    fun move(key: String, by: Int) = arrange { keys ->
        val from = keys.indexOf(key)
        val to = from + by
        if (from < 0 || to !in keys.indices) return@arrange keys
        keys.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun remove(key: String) = arrange { it - key }

    /** Adds [key] after the carousels already shown. */
    fun add(key: String) = arrange { if (key in it) it else it + key }

    fun reset() {
        viewModelScope.launch { settingsRepository.setExploreCarousels(null) }
    }

    private fun arrange(change: (List<String>) -> List<String>) {
        viewModelScope.launch {
            val current = settingsRepository.getInitial().exploreCarousels ?: CURATED_CAROUSELS
            val next = change(current)
            if (next != current) settingsRepository.setExploreCarousels(next.takeUnless { it == CURATED_CAROUSELS })
        }
    }
}
