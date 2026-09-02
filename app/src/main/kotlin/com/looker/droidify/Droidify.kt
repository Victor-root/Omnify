package com.looker.droidify

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
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
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import com.looker.droidify.compose.settings.SettingsViewModel
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.authorizationFor
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.InstallPrompt
import com.looker.droidify.data.InstalledRepository
import com.looker.droidify.receivers.InstalledAppReceiver
import com.looker.droidify.utility.common.SdkCheck
import com.looker.droidify.utility.common.applicationLocale
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.getDrawableCompat
import com.looker.droidify.utility.common.extension.getInstalledPackagesCompat
import com.looker.droidify.utility.common.localeCodeForTag
import com.looker.droidify.utility.extension.toInstalledItem
import com.looker.droidify.work.AutoUpdateWorker
import com.looker.droidify.work.CleanUpWorker
import com.looker.droidify.work.DownloadStatsWorker
import com.looker.droidify.work.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    lateinit var installPrompt: InstallPrompt

    @Inject
    lateinit var httpClient: HttpClient

    @Inject
    lateinit var repoRepository: RepoRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // A fresh install seeds + syncs its default repos from MainComposeActivity, so there's no
        // legacy "database created -> full sync" step here any more.
        installPrompt.attach(this)
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

    /**
     * Brings the stored language back in step with the locale Android runs this app under.
     *
     * Only from Android 13, where the language is chosen in the system's own per-app screen and so
     * nothing inside the app writes the setting when it changes. Below that there is no per-app locale
     * to read: the stored setting is the only record of the choice, and overwriting it from a system
     * that has nothing to say would simply erase it.
     *
     * Two things kept this from ever running before. It asked AppCompat rather than the framework, and
     * AppCompat answers out of its own set of activities, which this app has none of (see
     * [applicationLocale]), so the answer was always "nothing is set". And it then refused to write
     * whenever the setting still read "system", which is exactly what it reads until the in-app picker
     * has been used at all, so the ordinary case could not have reconciled even with a real answer.
     *
     * Stored the way the picker spells it (a resource-directory code, or "system"), never the raw
     * language tag, since that is the vocabulary everything reading it back expects.
     */
    private fun checkLanguage() {
        if (!SdkCheck.isTiramisu) return
        appScope.launch {
            val applied = localeCodeForTag(
                tag = applicationLocale()?.toLanguageTag(),
                available = SettingsViewModel.localeCodesList,
            )
            if (applied != settingsRepository.getInitial().language) {
                settingsRepository.setLanguage(applied)
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
            launch {
                // Switching automatic updates on runs a pass right away rather than leaving the
                // setting to look broken until the next background sync, which can be twelve hours
                // off: someone turning it on with updates already waiting plainly means those ones.
                // Same shape as unstableUpdate forcing a sync above. Switching it off has to stop a
                // pass already queued or running, not merely prevent the next one, or a batch caught
                // mid-download would go on to install anyway.
                settingsRepository.get { autoUpdate }.drop(1).collect { enabled ->
                    if (enabled) {
                        AutoUpdateWorker.enqueue(applicationContext, settingsRepository)
                    } else {
                        AutoUpdateWorker.cancel(applicationContext)
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
        // same engine as the manual Sync button. The chosen mode decides what the scheduled run waits
        // for, so Wi-Fi only really does mean Wi-Fi only.
        SyncWorker.schedulePeriodicSync(this, 12.hours, autoSync)
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
                add(RepoAuthInterceptor(repoAuthorizations()))
                add(FallbackIconInterceptor())
            }
            .build()
    }

    /**
     * The repository logins, kept in memory from the first image onwards.
     *
     * Collected here rather than at launch: the image loader is built when the first image is asked
     * for, which is also the first moment any of this matters. Starts as null, which
     * [RepoAuthInterceptor] waits on, so an image asked for in that same instant isn't sent off
     * without the login it needs.
     */
    private fun repoAuthorizations(): StateFlow<Map<String, String>?> = repoRepository
        .authorizations
        .stateIn(appScope, SharingStarted.Eagerly, null)
}

/**
 * Puts a repository's login on the requests for that repository's own images.
 *
 * The image loader knows nothing about repositories, so everything a password-protected one holds was
 * fetched with no credentials and answered 401: its logo, every one of its apps' icons, every
 * screenshot. Nothing was shown and nothing said why. See [authorizationFor] for which URLs a login
 * is given to, which is narrower than the server it sits on.
 */
private class RepoAuthInterceptor(
    private val authorizations: StateFlow<Map<String, String>?>,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val authorization = authorizations.filterNotNull().first().authorizationFor(url)
            ?: return chain.proceed()
        val authorized = chain.request.newBuilder()
            .httpHeaders(
                NetworkHeaders.Builder()
                    .apply { this[HttpHeaders.Authorization] = authorization }
                    .build(),
            )
            .build()
        return chain.withRequest(authorized).proceed()
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
