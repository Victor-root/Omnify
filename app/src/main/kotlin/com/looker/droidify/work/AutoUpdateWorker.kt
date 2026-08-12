package com.looker.droidify.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.looker.droidify.data.PendingUpdates
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.external.ExternalInstallOutcome
import com.looker.droidify.external.ExternalInstaller
import com.looker.droidify.utility.common.extension.exceptCancellation
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Installs everything that has an update waiting, when the user has asked for updates to be installed
 * automatically (Settings -> Updates -> "Install updates automatically").
 *
 * That setting existed but drove nothing at all: no code outside the settings screen itself ever read
 * it, so turning it on changed no behaviour. This is what it now runs.
 *
 * Catalogue apps and external sources are both covered, which is the whole point: an app followed from
 * GitHub should behave like one from a repository, not need hand-holding the other doesn't. The two
 * halves install through different paths only because they resolve their APKs differently: the
 * catalogue's is handed to [UpdateAllWorker], the same batch updater the Updates tab's "Update all"
 * button already uses, while external sources go through [ExternalInstaller].
 *
 * This never decides *what* is updatable on its own: [PendingUpdates] answers that with the very same
 * rules the Updates tab shows on screen, so this can only ever install something the user could see
 * listed there.
 *
 * Triggered by [SyncWorker] once a scheduled sync has finished, rather than on a schedule of its own,
 * so it always acts on freshly-checked data instead of racing the check that produces it.
 */
@HiltWorker
class AutoUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pendingUpdates: PendingUpdates,
    private val externalInstaller: ExternalInstaller,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Re-checked here, not only where this is enqueued: the setting can be turned off between the
        // two, and a queued run must not outlive the permission it was queued under.
        if (!settingsRepository.getInitial().autoUpdate) {
            Log.i(TAG, "Auto-update is off, nothing to do")
            return@withContext Result.success()
        }

        val cataloguePackages = pendingUpdates.catalogueApps().map { it.packageName.name }
        if (cataloguePackages.isNotEmpty()) {
            Log.i(TAG, "Auto-updating ${cataloguePackages.size} catalogue app(s)")
            UpdateAllWorker.updateAll(applicationContext, cataloguePackages)
        }

        val external = pendingUpdates.externalApps()
        if (external.isNotEmpty()) {
            Log.i(TAG, "Auto-updating ${external.size} external app(s)")
        }
        // One at a time: each is a full APK download, and InstallManager installs serially anyway, so
        // running them together would only compete for bandwidth. A source that can't be updated on its
        // own (a signature change needing a confirmed uninstall, a release that turns out to be a
        // different package) is skipped rather than aborting the rest. It stays listed in the Updates
        // tab, where the user can carry it out themselves.
        external.forEach { app ->
            try {
                val outcome = externalInstaller.installLatest(app)
                if (outcome != ExternalInstallOutcome.STARTED) {
                    Log.i(TAG, "Skipped ${app.key}: $outcome")
                }
            } catch (t: Throwable) {
                t.exceptCancellation()
                Log.w(TAG, "Auto-update failed for ${app.key}", t)
            }
        }
        Result.success()
    }

    companion object {
        private const val TAG = "AutoUpdateWorker"

        /**
         * Runs a pass now, if auto-update is on. Called at the end of a scheduled sync, so what it acts
         * on has just been re-checked, and the moment the setting is switched on, so it acts on the
         * updates that were already waiting instead of appearing to do nothing until the next sync.
         *
         * The network condition follows the user's existing sync preference rather than adding a second
         * one to keep in step: someone who limits background syncing to Wi-Fi plainly does not want
         * APKs (much heavier than an index) pulled over mobile data. "Never" can't reach here at all,
         * since it cancels the sync that triggers this.
         */
        suspend fun enqueue(context: Context, settingsRepository: SettingsRepository) {
            val settings = settingsRepository.getInitial()
            if (!settings.autoUpdate) return
            val request = OneTimeWorkRequestBuilder<AutoUpdateWorker>()
                // Downloading and installing a batch of apps on a nearly-flat battery is the kind of
                // background work that should wait; nothing here is time-critical.
                .setConstraints(settings.autoSync.workConstraints(requiresBatteryNotLow = true))
                .addTag(TAG)
                .build()
            // KEEP, not REPLACE: a pass already queued or running is doing this exact job, and replacing
            // it would abandon a download partway through to start the same work again.
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName = TAG,
                existingWorkPolicy = ExistingWorkPolicy.KEEP,
                request = request,
            )
            Log.i(TAG, "Auto-update pass enqueued")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
            Log.i(TAG, "Auto-update cancelled")
        }
    }
}
