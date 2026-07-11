package com.looker.droidify.di

import android.content.Context
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.installer.InstallManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InstallModule {

    @Singleton
    @Provides
    fun providesInstaller(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        installedRepository: InstalledRepository,
    ): InstallManager = InstallManager(context, settingsRepository, installedRepository)
}
