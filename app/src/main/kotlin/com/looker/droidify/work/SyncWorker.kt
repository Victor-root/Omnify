package com.looker.droidify.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.hasKeyWithValueOfType
import com.looker.droidify.R
import com.looker.droidify.data.PendingUpdates
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.AutoSync
import com.looker.droidify.external.ExternalRefresher
import com.looker.droidify.sync.SyncState
import com.looker.droidify.utility.common.createNotificationChannel
import com.looker.droidify.utility.common.extension.exceptCancellation
import com.looker.droidify.utility.common.toForegroundInfo
import com.looker.droidify.utility.notifications.showUpdatesAvailableNotification
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repoRepository: RepoRepository,
    private val externalRefresher: ExternalRefresher,
    private val settingsRepository: SettingsRepository,
    private val pendingUpdates: PendingUpdates,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repoId = if (inputData.hasKeyWithValueOfType<Int>(KEY_REPO_ID)) {
            inputData.getInt(KEY_REPO_ID, -1).takeIf { it >= 0 }
        } else {
            null
        }
        Log.i(TAG, "SyncWorker started (repoId=$repoId)")
        try {
            val success = if (repoId != null) {
                val repo = repoRepository.getRepo(repoId)
                if (repo != null) {
                    setForeground(createForegroundInfo(repo.name, -1))
                    // The progress callback fires on every downloaded chunk; refreshing the foreground
                    // notification each time floods WorkManager (it logs a "move to foreground" per
                    // call) and is wasteful. Update only when the whole-percent changes, and at most a
                    // few times a second.
                    var lastPercent = -1
                    var lastEmit = 0L
                    repoRepository.sync(repo) { state ->
                        val progress =
                            if (state is SyncState.IndexDownload.Progress) state.progress else -1
                        val now = SystemClock.elapsedRealtime()
                        if (progress != lastPercent && (progress < 0 || now - lastEmit >= 400L)) {
                            lastPercent = progress
                            lastEmit = now
                            setForegroundAsync(createForegroundInfo(repo.name, progress))
                        }
                    }
                } else {
                    Log.w(TAG, "Repo not found for id=$repoId; falling back to syncAll")
                    repoRepository.syncAll()
                }
            } else {
                repoRepository.syncAll()
            }
            // "Sync everything" means the external sources too, not just the catalogue repositories:
            // they are the other half of the same Updates tab, and until this ran here they had no
            // background check at all: a source only ever learned about a new release while a screen
            // was open. Skipped when syncing one named repository, which is a catalogue-only action.
            // Failures are contained: a provider being unreachable must not fail a catalogue sync that
            // worked, and ExternalRefresher's own throttle decides whether this actually does anything.
            if (repoId == null) {
                try {
                    externalRefresher.refresh()
                } catch (t: Throwable) {
                    t.exceptCancellation()
                    Log.w(TAG, "External source refresh failed", t)
                }
                // Now that both halves have been re-checked, hand over to the automatic installer if the
                // user asked for one. Only on the scheduled pass: a manual sync happens with the app in
                // front of the user, where the Updates tab and its "Update all" button are right there,
                // and starting installs out from under a deliberate tap on Sync would be a surprise.
                if (inputData.getString(KEY_TRIGGER) == TRIGGER_PERIODIC) {
                    AutoUpdateWorker.enqueue(applicationContext, settingsRepository)
                    try {
                        notifyAvailableUpdates()
                    } catch (t: Throwable) {
                        t.exceptCancellation()
                        Log.w(TAG, "Could not post the updates notification", t)
                    }
                }
            }
            if (success) {
                Log.i(TAG, "Sync completed successfully (repoId=$repoId)")
                Result.success()
            } else {
                Log.w(TAG, "Sync reported failure (repoId=$repoId)")
                retryOrGiveUp(repoId)
            }
        } catch (t: Throwable) {
            t.exceptCancellation()
            Log.e(TAG, "Sync failed with exception", t)
            retryOrGiveUp(repoId)
        }
    }

    /**
     * Retry a failed sync a few times, then give up with [Result.success] rather than [Result.retry].
     * Syncs are chained under one unique work name (see [enqueueUserSync]); a work that retries forever
     * would block every repo queued behind it, so a repo whose server is down or broken must not stall
     * the others. Finishing "successfully" just lets the chain proceed — the repo keeps its old data and
     * is retried by the next periodic or manual sync, not this run.
     */
    private fun retryOrGiveUp(repoId: Int?): Result =
        if (runAttemptCount + 1 < MAX_SYNC_ATTEMPTS) {
            Log.w(TAG, "Sync retry ${runAttemptCount + 1}/$MAX_SYNC_ATTEMPTS (repoId=$repoId)")
            Result.retry()
        } else {
            Log.w(TAG, "Sync gave up after $MAX_SYNC_ATTEMPTS attempts (repoId=$repoId)")
            Result.success()
        }

    /**
     * Tells the user what a background check turned up, if they asked to be told.
     *
     * The notification itself had been written and then never wired to anything, so "Notify about
     * updates" had no effect whatsoever, on by default, for as long as it had existed. It lists what
     * the Updates tab lists, catalogue and external sources together, since [PendingUpdates] answers
     * with the same rules the tab does.
     *
     * Nothing is posted while automatic installation is on: those updates are about to be installed,
     * and each install announces itself. An empty result clears any notification still showing from a
     * previous round, so one can't outlive the updates it was about.
     */
    private suspend fun notifyAvailableUpdates() {
        if (!settingsRepository.getInitial().notifyUpdate) return
        if (settingsRepository.getInitial().autoUpdate) return
        applicationContext.showUpdatesAvailableNotification(pendingUpdates.allAsEntries())
    }

    private fun createForegroundInfo(name: String, percent: Int): ForegroundInfo {
        val id = "sync_channel"
        val title = "Syncing: $name"
        val cancel = applicationContext.getString(R.string.cancel)
        val intent = WorkManager
            .getInstance(applicationContext)
            .createCancelPendingIntent(getId())

        applicationContext.createNotificationChannel(
            id = id,
            name = "Sync channel",
            showBadge = true,
        )

        val notification = NotificationCompat.Builder(applicationContext, id)
            .setContentTitle(title)
            .setTicker(title)
            .setProgress(100, percent, percent == -1)
            .setSmallIcon(R.drawable.ic_sync)
            .setOngoing(true)
            .addAction(R.drawable.ic_cancel, cancel, intent)
            .build()

        return notification.toForegroundInfo(124)
    }

    companion object {
        private const val TAG = "SyncWorker"
        // Total tries (initial + retries) before a failing sync gives up so it can't block the ones
        // chained behind it.
        private const val MAX_SYNC_ATTEMPTS = 3
        private const val KEY_REPO_ID = "repo_id"
        private const val KEY_TRIGGER = "trigger"
        private const val TRIGGER_USER = "user"
        private const val TRIGGER_PERIODIC = "periodic"

        /** The one queue every sync the user is waiting on goes through, so they run one after another
         *  rather than several large indexes at once. */
        private const val USER_WORK_NAME = "$TAG.user"

        /** For a sync the user asked for: any connection will do, since they are waiting on it. The
         *  scheduled one goes by their auto-sync choice instead (see [workConstraints]). */
        private val userSyncConstraints: Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun userSyncRequest(repoId: Int?): OneTimeWorkRequest {
            val data = Data.Builder()
                .putString(KEY_TRIGGER, TRIGGER_USER)
                .apply { if (repoId != null) putInt(KEY_REPO_ID, repoId) }
                .build()

            return OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .setConstraints(userSyncConstraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                // A per-repo tag, since WorkInfo never exposes its own inputData back (only tags,
                // progress and outputData) — this is what lets isSyncingRepo() below tell *this*
                // repo's sync apart from any other queued/running one.
                .apply { if (repoId != null) addTag(repoSyncTag(repoId)) }
                .build()
        }

        fun enqueueUserSync(context: Context, repoId: Int? = null) {
            // APPEND_OR_REPLACE, not KEEP: enabling several repos in quick succession enqueues one sync
            // each under the same unique name. KEEP dropped every sync after the first (only some repos
            // synced, the rest needed a manual re-sync). Appending chains them so all enabled repos are
            // synced, one after another — which also avoids decoding several large indexes at once (a
            // memory spike on low-RAM devices). OR_REPLACE keeps the chain going even if one repo's sync
            // ends up failing, instead of cancelling the ones queued behind it.
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    uniqueWorkName = USER_WORK_NAME,
                    existingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request = userSyncRequest(repoId),
                )
            Log.i(TAG, "User sync enqueued (repoId=$repoId)")
        }

        /**
         * Starts the catalogue's first sync again, right now.
         *
         * For the cold start whose sync is still waiting: held back by the network constraint on a
         * launch with no connection, or serving out a retry delay after failing for want of one. Both
         * leave the moment of the next attempt to WorkManager, which on a device that has just joined a
         * network can be minutes of a screen with nothing on it. Called when the connection comes back
         * with the catalogue still empty (see AppListViewModel), so the wait is the connection's, not a
         * timer's.
         *
         * REPLACE, where [enqueueUserSync] appends: the catalogue being empty means nothing has ever
         * been synced, so a full sync covers everything the queued one would have, and appending would
         * merely run the same work twice over.
         */
        fun restartUserSync(context: Context) {
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(
                    uniqueWorkName = USER_WORK_NAME,
                    existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                    request = userSyncRequest(repoId = null),
                )
            Log.i(TAG, "User sync restarted")
        }

        fun syncRepo(context: Context, repoId: Int) {
            enqueueUserSync(context, repoId)
        }

        /** [autoSync] is the user's own choice of when background work may run; see [workConstraints].
         *  A sync they press for themselves goes through [enqueueUserSync] instead and ignores it. */
        fun schedulePeriodicSync(context: Context, repeatInterval: Duration, autoSync: AutoSync) {
            val data = Data.Builder()
                .putString(KEY_TRIGGER, TRIGGER_PERIODIC)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(repeatInterval.toJavaDuration())
                .setInputData(data)
                .setConstraints(autoSync.workConstraints())
                // Delay the first periodic run by a full interval: a freshly-scheduled periodic work
                // otherwise fires immediately, and on first launch it would run *alongside* the one-time
                // launch sync — two full index parses at once exhausted memory on low-RAM TVs. The
                // one-time sync covers "now"; the periodic only needs to cover later.
                .setInitialDelay(repeatInterval.toJavaDuration())
                .addTag(TAG)
                .build()

            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    uniqueWorkName = TAG,
                    existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
                    request = request,
                )
            Log.i(TAG, "Periodic sync scheduled every $repeatInterval ($autoSync)")
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            Log.i(TAG, "All sync work cancelled")
        }

        /**
         * Emits `true` while any sync (manual, repo-enable, periodic) is actively running. Drives
         * the in-app progress bar so the user sees the catalog is loading — notably on first launch,
         * where the list would otherwise look empty/broken until the first sync finishes.
         */
        fun isSyncing(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(TAG)
                .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

        /**
         * Emits `true` while a sync the user is waiting on is running *or* still to run: held back by
         * the network constraint, queued behind another repository, or serving out a retry delay.
         *
         * [isSyncing] answers for a sync that is actually running, which is what a progress bar needs.
         * A first launch spends most of its time in the states it leaves out, and answering "nothing is
         * happening" there is what left the catalogue screen blank and silent with no explanation.
         *
         * Only this queue, never the periodic sync: that one is permanently scheduled and so would
         * answer `true` for ever.
         */
        fun isSyncScheduled(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(USER_WORK_NAME)
                .map { infos -> infos.any { !it.state.isFinished } }

        private fun repoSyncTag(repoId: Int): String = "$TAG.repo.$repoId"

        /**
         * Emits `true` while [repoId]'s own sync is enqueued or running — distinct from [isSyncing],
         * which can't tell repos apart. Drives a focused progress indicator right on that repo's own
         * row (e.g. around its enable toggle) instead of only the screen-wide bar, so enabling several
         * repos in quick succession shows each one's own status without the list otherwise changing.
         */
        fun isSyncingRepo(context: Context, repoId: Int): Flow<Boolean> =
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(repoSyncTag(repoId))
                .map { infos ->
                    infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                }
    }
}
