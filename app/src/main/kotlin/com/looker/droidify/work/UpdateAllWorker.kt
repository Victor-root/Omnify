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
import com.looker.droidify.data.AppRepository
import com.looker.droidify.data.RepoRepository
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.selectForDevice
import com.looker.droidify.data.signerMismatch
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.utility.common.cache.Cache
import com.looker.droidify.utility.common.extension.calculateHash
import com.looker.droidify.utility.common.extension.getPackageInfoCompat
import com.looker.droidify.utility.common.extension.singleSignature
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads and installs every pending update in one go, so the Updates tab can offer a single
 * "Update all" instead of making the user tap each app.
 *
 * The set of packages to update is decided by the UI (the Updates tab already computes exactly which
 * installed apps have an installable update) and handed in as [KEY_PACKAGES]; this worker just carries
 * out the downloads. For each package it resolves the best release this device can run
 * ([selectForDevice]), downloads + hash-verifies it with the shared [Downloader], and hands it to
 * [InstallManager] — which queues the installs one after another and shows a per-app notification.
 *
 * A package whose newer release is signed by a different key than the installed copy can't be updated
 * in place, so it goes through [InstallManager.reinstall]: the old copy is uninstalled (the system
 * asks the user to confirm) and the new version installs automatically once it's gone — the same flow
 * the app's own page offers, so a batch update isn't cut short by a signature change.
 */
@HiltWorker
class UpdateAllWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val installManager: InstallManager,
    private val appRepository: AppRepository,
    private val repoRepository: RepoRepository,
    private val downloader: Downloader,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packages = inputData.getStringArray(KEY_PACKAGES)?.toList().orEmpty()
        if (packages.isEmpty()) return@withContext Result.success()

        Log.i(TAG, "Updating ${packages.size} app(s)")
        for (packageName in packages) {
            // Publish which app is being handled right now, so the Updates tab can show a live spinner
            // on that tile and move to the next as the batch progresses.
            setProgress(Data.Builder().putString(KEY_CURRENT_PACKAGE, packageName).build())
            runCatching { updateOne(packageName) }
                .onFailure { Log.w(TAG, "Update failed for $packageName", it) }
        }
        Result.success()
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

        // Download and verify first — never touch the installed app until the new APK is in hand.
        val cacheFileName = downloadAndVerify(pkg, repo) ?: return

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
    private suspend fun downloadAndVerify(pkg: Package, repo: Repo): String? {
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
        val response = downloader.downloadToFile(
            url = url,
            target = partialFile,
            headers = {
                repo.authentication?.let { authentication(it.username, it.password) }
            },
        )
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
        private const val KEY_CURRENT_PACKAGE = "current_package"

        /** Enqueues a one-shot update of [packageNames]; a second tap replaces a queued run. */
        fun updateAll(context: Context, packageNames: List<String>) {
            if (packageNames.isEmpty()) return
            val data = Data.Builder()
                .putStringArray(KEY_PACKAGES, packageNames.toTypedArray())
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
            Log.i(TAG, "Update-all enqueued for ${packageNames.size} app(s)")
        }

        /** Emits `true` while a batch update is downloading, so the button can show progress and lock. */
        fun isUpdating(context: Context): Flow<Boolean> =
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(TAG)
                .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }

        /** The package name currently being updated by the running batch (or null when idle), so the
         *  Updates tab can show a live spinner on that app's tile. */
        fun currentPackage(context: Context): Flow<String?> =
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(TAG)
                .map { infos ->
                    infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        ?.progress?.getString(KEY_CURRENT_PACKAGE)
                }
    }
}

private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
