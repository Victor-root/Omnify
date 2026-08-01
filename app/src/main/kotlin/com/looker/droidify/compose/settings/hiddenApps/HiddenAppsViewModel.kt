package com.looker.droidify.compose.settings.hiddenApps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.utility.common.extension.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the hidden-apps list: a catalogue app or an external one, unified so the screen can
 *  render and unhide either the same way. */
sealed interface HiddenApp {
    val key: String
    val name: String

    data class Catalogue(val app: AppMinimal) : HiddenApp {
        override val key get() = app.packageName.name
        override val name get() = app.name
    }

    data class External(val app: ExternalApp) : HiddenApp {
        override val key get() = app.key
        override val name get() = app.label
    }
}

@HiltViewModel
class HiddenAppsViewModel @Inject constructor(
    private val appRepository: AppRepository,
    externalAppRepository: ExternalAppRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val hiddenKeys = settingsRepository.get { hiddenApps }

    /** Every hidden app, catalogue and external together, alphabetical, re-resolved whenever the hidden
     *  set or either source's own data changes, so a row's icon/name stays live and an unhidden app
     *  disappears from this screen right away. A key that no longer resolves to any app (its catalogue
     *  entry or external source was removed entirely while hidden) simply doesn't appear, the same way an
     *  orphaned favourite doesn't appear anywhere either: nothing left to show or unhide. */
    val hiddenApps: StateFlow<List<HiddenApp>> = combine(
        hiddenKeys,
        appRepository.catalogChanges,
        externalAppRepository.apps,
    ) { keys, _, externalApps -> keys to externalApps }
        .mapLatest { (keys, externalApps) ->
            if (keys.isEmpty()) {
                emptyList()
            } else {
                val catalogue = appRepository.apps(sortOrder = SortOrder.NAME)
                    .filter { it.packageName.name in keys }
                    .map { HiddenApp.Catalogue(it) }
                val external = externalApps
                    .filter { it.key in keys }
                    .map { HiddenApp.External(it) }
                (catalogue + external).sortedBy { it.name.lowercase() }
            }
        }
        .flowOn(Dispatchers.Default)
        .asStateFlow(emptyList())

    fun unhide(app: HiddenApp) {
        viewModelScope.launch { settingsRepository.toggleHidden(app.key) }
    }
}
