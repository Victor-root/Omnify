package com.looker.droidify.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.looker.droidify.data.encryption.EncryptionStorage
import com.looker.droidify.datastore.PreferenceSettingsRepository
import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.SettingsSerializer
import com.looker.droidify.datastore.migration.ProtoToPreferenceMigration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

private const val PREFERENCES = "settings_file"

private const val SETTINGS = "settings"

@Module
@InstallIn(SingletonComponent::class)
object DatastoreModule {

    @Singleton
    @Provides
    fun provideProtoDatastore(
        @ApplicationContext context: Context,
    ): DataStore<Settings> = DataStoreFactory.create(
        serializer = SettingsSerializer,
    ) {
        context.dataStoreFile(PREFERENCES)
    }

    @Singleton
    @Provides
    fun providePreferenceDatastore(
        @ApplicationContext context: Context,
        oldDatastore: DataStore<Settings>,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(
            ProtoToPreferenceMigration(oldDatastore),
        ),
    ) {
        context.preferencesDataStoreFile(SETTINGS)
    }

    @Singleton
    @Provides
    fun provideEncryptionStorage(
        dataStore: DataStore<Preferences>,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): EncryptionStorage = EncryptionStorage(dataStore, dispatcher)

    @Singleton
    @Provides
    fun provideSettingsRepository(
        dataStore: DataStore<Preferences>,
    ): SettingsRepository = PreferenceSettingsRepository(dataStore)
}
