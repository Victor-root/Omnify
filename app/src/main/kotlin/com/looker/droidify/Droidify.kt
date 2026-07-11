package com.looker.droidify

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.receivers.InstalledAppReceiver
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.getDrawableCompat
import com.looker.droidify.utility.common.extension.getInstalledPackagesCompat
import com.looker.droidify.utility.extension.toInstalledItem
import com.looker.droidify.work.CleanUpWorker
import com.looker.droidify.work.DownloadStatsWorker
import com.looker.droidify.work.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.hours

@HiltAndroidApp
class Droidify : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    private val parentJob = SupervisorJob()
    private val appScope = CoroutineScope(Dispatchers.Default + parentJob)

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var installedRepository: InstalledRepository

    @Inject
    lateinit var installer: InstallManager

    @Inject
    lateinit var httpClient: HttpClient

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // A fresh install seeds + syncs its default repos from MainComposeActivity, so there's no
        // legacy "database created -> full sync" step here any more.
        listenApplications()
        checkLanguage()
        updatePreference()
        scheduleDownloadStats()
        appScope.launch { installer() }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel("Application Terminated")
        installer.close()
    }

    private fun listenApplications() {
        val installedItems = packageManager
            .getInstalledPackagesCompat()
            ?.map { it.toInstalledItem() }
        if (installedItems != null) {
            appScope.launch { installedRepository.putAll(installedItems) }
        }
        appScope.launch {
            registerReceiver(
                InstalledAppReceiver(packageManager, installedRepository, appScope),
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                },
            )
        }
    }

    private fun checkLanguage() {
        appScope.launch {
            val lastSetLanguage = settingsRepository.getInitial().language
            val systemSetLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (systemSetLanguage != lastSetLanguage && lastSetLanguage != "system") {
                settingsRepository.setLanguage(systemSetLanguage)
            }
        }
    }

    private fun updatePreference() {
        appScope.launch {
            launch {
                settingsRepository.get { unstableUpdate }.drop(1).collect {
                    forceSyncAll()
                }
            }
            launch {
                settingsRepository.get { autoSync }.collectIndexed { index, syncMode ->
                    // Don't update sync job on initial collect
                    updateSyncJob(index > 0, syncMode)
                }
            }
            launch {
                settingsRepository.get { cleanUpInterval }.drop(1).collect {
                    if (it == INFINITE) {
                        CleanUpWorker.removeAllSchedules(applicationContext)
                    } else {
                        CleanUpWorker.scheduleCleanup(applicationContext, it)
                    }
                }
            }
        }
    }

    private fun updateSyncJob(force: Boolean, autoSync: AutoSync) {
        if (autoSync == AutoSync.NEVER) {
            SyncWorker.cancelAll(this)
            return
        }
        // Auto-sync runs through the single data layer (SyncWorker -> RepoRepository -> Room), the
        // same engine as the manual Sync button. The per-network-type conditions are simplified to
        // "connected" for now.
        SyncWorker.schedulePeriodicSync(this, 12.hours)
    }

    private fun forceSyncAll() {
        SyncWorker.enqueueUserSync(this)
    }

    /**
     * Powers the Discover home's "Most downloaded" carousel: the download-stats worker was never
     * scheduled, so the stats table stayed empty and the carousel never had data. On launch we fetch
     * once if it's never run (so the carousel appears promptly), then keep it fresh in the background.
     * Honours the privacy setting — cancelled when the user turns stats off.
     */
    private fun scheduleDownloadStats() {
        appScope.launch {
            val settings = settingsRepository.getInitial()
            if (!settings.dlStatsEnabled) {
                DownloadStatsWorker.cancelPeriodic(this@Droidify)
                return@launch
            }
            if (settings.lastModifiedDownloadStats == null) {
                DownloadStatsWorker.fetchDownloadStats(this@Droidify)
            }
            DownloadStatsWorker.schedulePeriodic(this@Droidify)
        }
    }

    class BootReceiver : BroadcastReceiver() {
        @SuppressLint("UnsafeProtectedBroadcastReceiver")
        override fun onReceive(context: Context, intent: Intent) = Unit
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val memoryCache = MemoryCache.Builder()
            .maxSizePercent(context, 0.25)
            .build()

        val diskCache = DiskCache.Builder()
            .directory(Cache.getImagesDir(this))
            .maxSizePercent(0.05)
            .build()

        return ImageLoader.Builder(this)
            .memoryCache(memoryCache)
            .diskCache(diskCache)
            .error(getDrawableCompat(R.drawable.ic_cannot_load).asImage())
            // No crossfade: while scrolling, icons load into view and each fade forces an offscreen
            // alpha layer per icon every frame — the main scroll stutter on slower devices. Icons just
            // appear instead, which reads fine at this size and keeps the grid smooth.
            .crossfade(false)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { httpClient }))
                add(FallbackIconInterceptor())
            }
            .build()
    }
}

private class FallbackIconInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val result = chain.proceed()

        if (result is SuccessResult) return result

        val fallbackIconUrl = request.newBuilder()
            .data((request.data as String).replaceAfterLast('/', "icon.png"))
            .build()
        return chain.withRequest(fallbackIconUrl).proceed()
    }
}
