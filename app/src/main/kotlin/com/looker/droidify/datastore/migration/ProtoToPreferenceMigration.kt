package com.looker.droidify.datastore.migration

import com.looker.droidify.datastore.PreferenceSettingsRepository.PreferencesKeys.setting
import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.model.BackgroundStyle
import kotlinx.coroutines.flow.first

class ProtoToPreferenceMigration(
    private val oldDataStore: androidx.datastore.core.DataStore<Settings>,
    // The old store knows nothing of backgrounds, so what it holds is only the built-in default: the
    // device's own default is written instead, or a fresh install would be pinned to it.
    private val defaultBackgroundStyle: BackgroundStyle,
    // Likewise unknown to the old store, and likewise device-dependent.
    private val defaultTvOnly: Boolean,
) : androidx.datastore.core.DataMigration<androidx.datastore.preferences.core.Preferences> {
    override suspend fun cleanUp() {
    }

    override suspend fun shouldMigrate(currentData: androidx.datastore.preferences.core.Preferences): Boolean {
        return currentData.asMap().isEmpty()
    }

    override suspend fun migrate(currentData: androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
        val settings = oldDataStore.data.first()
        val preferences = currentData.toMutablePreferences()
        preferences.setting(settings.copy(backgroundStyle = defaultBackgroundStyle, tvOnly = defaultTvOnly))
        return preferences
    }
}
