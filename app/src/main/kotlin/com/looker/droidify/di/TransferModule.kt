package com.looker.droidify.di

import android.content.Context
import com.looker.droidify.data.encryption.KeystoreKeyWrapper
import com.looker.droidify.transfer.TrustedDeviceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TransferModule {

    /** In the directory Android never backs up, with the secrets wrapped under a key-store key of
     *  their own, apart from the one repository credentials use. */
    @Singleton
    @Provides
    fun provideTrustedDeviceStore(
        @ApplicationContext context: Context,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): TrustedDeviceStore = TrustedDeviceStore(
        file = File(context.noBackupFilesDir, "trusted_devices.json"),
        keyWrapper = KeystoreKeyWrapper("omnify_trusted_devices"),
        ioDispatcher = dispatcher,
    )
}
