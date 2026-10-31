package com.looker.droidify.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.looker.droidify.data.encryption.sha256Hex
import com.looker.droidify.BuildConfig
import com.looker.droidify.R
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.PendingUpdates
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.data.signerMismatch
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.external.ExternalAppRepository
import com.looker.droidify.external.ExternalInstallOutcome
import com.looker.droidify.external.ExternalInstaller
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.network.ApkFileDownloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import com.looker.droidify.utility.common.extension.calculateHash
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.singleSignature
import com.looker.droidify.utility.common.extension.versionCodeCompat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.looker.droidify.utility.common.extension.exceptCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Downloads and installs every pending update in one go, so the Updates tab can offer a single
 * "Update all" instead of making the user tap each app.
 *
 * Both halves of that tab, catalogue apps ([KEY_PACKAGES]) and tracked external sources
 * ([KEY_EXTERNAL_KEYS]), in one batch, so the button covers the list it sits above rather than part
 * of it. They take different routes only because they resolve their APKs differently, not because
 * they mean different things to the user.
 *
 * Up to [MAX_PARALLEL] packages are worked on at once, taken in the order asked for: a slow server
 * holds up only its own download, the next package starting as soon as any one is through, and
 * whichever download finishes first is installed first.
 *
 * What to update is decided by the caller (the Updates tab already computes exactly what it lists);
 * this worker just carries it out. For a catalogue package it resolves the best release this device
 * can run ([selectForDevice]), downloads + hash-verifies it with the shared [Downloader], and hands it
 * to [InstallManager], which queues the installs one after another and shows a per-app notification.
 * An external source goes through [ExternalInstaller], which does the same for a release APK the
 * refresher has already resolved.
 *
 * A package whose newer release is signed by a different key than the installed copy can't be updated
 * in place, so it goes through [InstallManager.reinstall]: the old copy is uninstalled (the system
 * asks the user to confirm) and the new version installs automatically once it's gone — the same flow
 * the app's own page offers, so a batch update isn't cut short by a signature change. The equivalent
 * for an external source is [ExternalInstallOutcome.NEEDS_USER], which is skipped rather than
 * uninstalled behind the user's back and stays listed for them to carry out.
 */
@HiltWorker
class UpdateAllWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val installManager: InstallManager,
    private val appRepository: AppRepository,
    private val repoRepository: RepoRepository,
    private val apkFileDownloader: ApkFileDownloader,
    private val batchProgress: BatchUpdateProgress,
    private val externalAppRepository: ExternalAppRepository,
    private val externalInstaller: ExternalInstaller,
    private val pendingUpdates: PendingUpdates,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packages = inputData.getStringArray(KEY_PACKAGES)?.toList().orEmpty()
        val externalKeys = inputData.getStringArray(KEY_EXTERNAL_KEYS)?.toList().orEmpty()
        if (packages.isEmpty() && externalKeys.isEmpty()) return@withContext Result.success()

        // Resolved here rather than carried in the input: a source's latest release is what the
        // refresher records, and the record is what ExternalInstaller reads. Keys are all that has to
        // survive the trip. Kept in the order asked for, so the batch counts up the way the list reads.
        val tracked = externalAppRepository.getApps().associateBy { it.key }
        val external = externalKeys.mapNotNull(tracked::get)
        val total = packages.size + external.size

        Log.i(TAG, "Updating ${packages.size} catalogue app(s) and ${external.size} external source(s)")
        // The source's own key for an external source, never the package id it installs under: the
        // tiles this marks are keyed that way, and a catalogue tile must not light up for a source that
        // happens to install the same package.
        val steps = packages.map { name -> name to suspend { updateCatalogueApp(name) } } +
            external.map { app -> app.key to suspend { updateExternalApp(app) } }
        batchProgress.start(total)
        try {
            val slots = Semaphore(MAX_PARALLEL)
            coroutineScope {
                steps.forEach { (key, update) ->
                    launch {
                        slots.withPermit {
                            batchProgress.startPackage(key)
                            logStep("started", key)
                            publishStep()
                            try {
                                update()
                            } catch (e: Exception) {
                                e.exceptCancellation()
                                Log.w(TAG, "Update failed for $key", e)
                            }
                            batchProgress.finishPackage(key)
                            logStep("done with", key)
                            publishStep()
                        }
                    }
                }
            }
        } finally {
            // In a finally so a cancelled batch (the user leaving, the system stopping the work)
            // doesn't leave a download frozen on screen at whatever percentage it had reached.
            batchProgress.clear()
        }
        Result.success()
    }

    /**
     * Publishes which packages are in flight, so the Updates tab can show a live spinner on those
     * tiles, and moves the notification's count along. One at a time: the packages running together
     * would otherwise publish over each other out of order.
     */
    private suspend fun publishStep() = stepPublishing.withLock {
        val state = batchProgress.state.value ?: return@withLock
        setProgress(Data.Builder().putStringArray(KEY_CURRENT_PACKAGES, state.items.keys.toTypedArray()).build())
        showBatchStep(R.string.batch_updating_FORMAT, state.position, state.count)
    }

    private val stepPublishing = Mutex()

    /** A catalogue app, asked again at its turn rather than trusted from when Update all was tapped: an
     *  app updated by hand meanwhile, from its own page, was downloaded and installed a second time. */
    private suspend fun updateCatalogueApp(packageName: String) {
        if (packageName !in pendingUpdates.cataloguePackages()) {
            logSkippedAsUpToDate()
            return
        }
        updateOne(packageName)
    }

    /** The same as [updateCatalogueApp], with the source's record as it is now rather than as it was. */
    private suspend fun updateExternalApp(app: ExternalApp) {
        val current = pendingUpdates.externalApps().firstOrNull { it.key == app.key }
        if (current == null) {
            logSkippedAsUpToDate()
            return
        }
        updateOneExternal(current)
    }

    private fun logStep(what: String, key: String) {
        if (!BuildConfig.DIAGNOSTICS) return
        val inFlight = batchProgress.state.value?.items?.size ?: 0
        DiagnosticLog.log("Update all: $what $key, $inFlight in flight")
    }

    private fun logSkippedAsUpToDate() {
        if (BuildConfig.DIAGNOSTICS) DiagnosticLog.log("Update all: an app already updated meanwhile, skipped")
    }

    /**
     * One external source. Anything but [ExternalInstallOutcome.STARTED] is a source that cannot be
     * updated on its own (a signature change or a downgrade needing a confirmed uninstall, a release
     * that turns out to be a different package, nothing installed to update); it is skipped rather
     * than aborting the rest, and stays listed for the user to carry out from its own page.
     */
    private suspend fun updateOneExternal(app: ExternalApp) {
        val outcome = externalInstaller.installLatest(
            app = app,
            onDownload = { read, total -> batchProgress.reportDownload(app.key, read.value, total?.value) },
            // Asked again once downloaded: the app may have been updated by hand during the download.
            stillWanted = { pendingUpdates.externalApps().any { it.key == app.key } },
        )
        batchProgress.finishDownload(app.key)
        if (outcome == ExternalInstallOutcome.UP_TO_DATE) logSkippedAsUpToDate()
        if (outcome != ExternalInstallOutcome.STARTED) Log.i(TAG, "Skipped ${app.key}: $outcome")
    }

    private suspend fun updateOne(packageName: String) {
        // Every release of this package across enabled repos, paired with its repo.
        val apps = appRepository.getApp(PackageName(packageName)).first()
        val candidates = apps.flatMap { app ->
            val repo = repoRepository.getRepo(app.repoId.toInt())
            if (repo != null) app.packages.orEmpty().map { pkg -> pkg to repo } else emptyList()
        }
        val suggested = apps.maxOfOrNull { it.metadata.suggestedVersionCode } ?: 0L
        val target = candidates.selectForDevice(suggested)
        if (target == null) {
            Log.w(TAG, "No compatible release for $packageName")
            return
        }
        val (pkg, repo) = target
        // Now that the release is known, an app's version list can mark the row being downloaded.
        batchProgress.setTargetVersion(packageName, pkg.manifest.versionCode)

        // Download and verify first — never touch the installed app until the new APK is in hand.
        val cacheFileName = downloadAndVerify(packageName, pkg, repo) ?: return
        // Asked again once downloaded, and of the device itself rather than the installed-apps table,
        // which only catches up a moment after an install: the app may have been updated by hand, from
        // its own page, during the download.
        val installedNow = applicationContext.packageManager.getPackageInfoCompat(packageName)?.versionCodeCompat
        if (installedNow != null && installedNow >= pkg.manifest.versionCode) {
            logSkippedAsUpToDate()
            return
        }

        if (installedWithDifferentSignature(packageName, pkg)) {
            // Android can't update across signers in place. Rather than skip (leaving the user to do
            // it by hand), do the same uninstall-then-reinstall the app page offers: the system asks
            // to confirm the uninstall, then the new version installs automatically.
            Log.i(TAG, "Reinstalling $packageName across a signature change")
            installManager.reinstall(PackageName(packageName), cacheFileName)
        } else {
            installManager.install(InstallItem(PackageName(packageName), cacheFileName))
        }
    }

    /**
     * Downloads [pkg] from [repo] and checks it against the index hash. Returns the release cache
     * file name on success, or null if the download or verification failed.
     */
    private suspend fun downloadAndVerify(packageName: String, pkg: Package, repo: Repo): String? {
        val cacheFileName = pkg.apk.hash.replace('/', '-') + ".apk"
        // Reuse an already-downloaded, hash-verified APK instead of fetching it again.
        val cachedRelease = Cache.getReleaseFile(applicationContext, cacheFileName)
        if (cachedRelease.exists() && cachedRelease.sha256Hex().equals(pkg.apk.hash, ignoreCase = true)) {
            return cacheFileName
        }
        // V2 index file names start with a slash; join like the sync layer does (concatenate),
        // never Uri.appendPath() which would percent-encode the leading slash.
        val url = repo.address.removeSuffix("/") + "/" + pkg.apk.name.removePrefix("/")
        val partialFile = Cache.getPartialReleaseFile(applicationContext, cacheFileName)
        val response = apkFileDownloader.download(
            url = url,
            target = partialFile,
            headers = {
                repo.authentication?.let { authentication(it.username, it.password) }
            },
        ) { read, total ->
            // The batch downloads outside any screen, so this is the only place the bytes are known.
            // BatchUpdateProgress throttles them; this callback fires far too often to publish raw.
            batchProgress.reportDownload(packageName, read.value, total?.value)
        }
        batchProgress.finishDownload(packageName)
        if (response !is NetworkResponse.Success) {
            Log.w(TAG, "Download failed for ${pkg.apk.name}")
            partialFile.delete()
            return null
        }
        if (!partialFile.sha256Hex().equals(pkg.apk.hash, ignoreCase = true)) {
            Log.w(TAG, "Hash mismatch for ${pkg.apk.name}")
            partialFile.delete()
            return null
        }
        partialFile.copyTo(Cache.getReleaseFile(applicationContext, cacheFileName), overwrite = true)
        partialFile.delete()
        return cacheFileName
    }

    /**
     * True when [packageName] is already installed but signed by a key the [pkg] release isn't. The
     * index records each release's signer(s) in [Package.manifest].signer (a SHA-256 of the cert); we
     * compute the installed cert's hash the same way ([singleSignature] + [calculateHash]) so the two
     * are directly comparable via [signerMismatch] — the one shared definition of this comparison (see
     * [com.looker.droidify.data.InstalledIdentityRepository]), which also handles the
     * nothing-to-compare cases (not installed, no declared signer, unreadable signature) as "no
     * conflict" rather than skipping on uncertainty.
     */
    private fun installedWithDifferentSignature(packageName: String, pkg: Package): Boolean {
        val installedSigner = applicationContext.packageManager
            .getPackageInfoCompat(packageName)
            ?.singleSignature
            ?.calculateHash()
        return signerMismatch(installedSigner, pkg.manifest.signer)
    }

    companion object {
        private const val TAG = "UpdateAllWorker"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_EXTERNAL_KEYS = "external_keys"
        private const val KEY_CURRENT_PACKAGES = "current_packages"

        /** Packages downloaded at once: enough that one slow server no longer holds up the rest, few
         *  enough not to split a slow connection so many ways that nothing finishes. */
        private const val MAX_PARALLEL = 3

        /**
         * Enqueues a one-shot update of [packageNames] (catalogue) and [externalKeys] (tracked
         * sources); a second tap replaces a queued run.
         */
        fun updateAll(context: Context, packageNames: List<String>, externalKeys: List<String>) {
            if (packageNames.isEmpty() && externalKeys.isEmpty()) return
            val data = Data.Builder()
                .putStringArray(KEY_PACKAGES, packageNames.toTypedArray())
                .putStringArray(KEY_EXTERNAL_KEYS, externalKeys.toTypedArray())
                .build()
            val request = OneTimeWorkRequestBuilder<UpdateAllWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName = TAG,
                existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                request = request,
            )
            Log.i(
                TAG,
                "Update-all enqueued for ${packageNames.size} app(s) " +
                    "and ${externalKeys.size} source(s)",
            )
        }

        /**
         * Stops a running batch. Cancelling the work cancels the coroutine it runs in, so the
         * download in flight is abandoned and [doWork]'s finally clears the on-screen progress.
         *
         * The whole batch, not one app: the run is a single piece of work, so there is no per-app
         * handle to pull. Cancel on an app's page therefore means "stop
         * updating", not "skip this one", which is the plain reading of the button given that the
         * page shows the batch's download as its own.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TAG)
            Log.i(TAG, "Update-all cancelled")
        }

        /** Emits `true` while a batch update is downloading, so the button can show progress and lock. */
        fun isUpdating(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(TAG)
                .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

        /** The packages the running batch is updating right now (empty when idle), so the Updates tab
         *  can show a live spinner on those apps' tiles. */
        fun currentPackages(context: Context): Flow<Set<String>> =
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(TAG)
                .map { infos ->
                    infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        ?.progress?.getStringArray(KEY_CURRENT_PACKAGES)?.toSet().orEmpty()
                }
    }
}
