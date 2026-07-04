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
import com.looker.droidify.installer.InstallManager
import com.looker.droidify.installer.model.InstallItem
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.NetworkResponse
import com.looker.droidify.utility.common.cache.Cache
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
 * Downloads and installs every app a single repository serves in one go, for the "Install all" button
 * on that repo's Applications tab.
 *
 * Unlike [UpdateAllWorker] (which resolves the best release for a package across *every* enabled
 * repo), this always installs the release from the *one* repo the user tapped the button on
 * ([KEY_REPO_ID]) — installing "all of this repo's apps" should mean exactly that repo's builds, not
 * whichever repo happens to publish the newest version. The caller (the repo detail screen) already
 * filters [KEY_PACKAGES] down to apps that aren't installed yet, so there's never a signer conflict to
 * resolve here: this only ever performs fresh installs, one after another.
 */
@HiltWorker
class InstallAllWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val installManager: InstallManager,
    private val appRepository: AppRepository,
    private val repoRepository: RepoRepository,
    private val downloader: Downloader,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packages = inputData.getStringArray(KEY_PACKAGES)?.toList().orEmpty()
        val repoId = inputData.getInt(KEY_REPO_ID, -1)
        val repo = if (repoId == -1) null else repoRepository.getRepo(repoId)
        if (packages.isEmpty() || repo == null) return@withContext Result.success()

        Log.i(TAG, "Installing ${packages.size} app(s) from ${repo.name}")
        for (packageName in packages) {
            runCatching { installOne(packageName, repo) }
                .onFailure { Log.w(TAG, "Install failed for $packageName", it) }
        }
        Result.success()
    }

    private suspend fun installOne(packageName: String, repo: Repo) {
        val app = appRepository.getApp(PackageName(packageName)).first()
            .firstOrNull { it.repoId.toInt() == repo.id }
            ?: return
        val target = app.packages.orEmpty()
            .map { pkg -> pkg to repo }
            .selectForDevice(app.metadata.suggestedVersionCode)
            ?: return
        val (pkg, _) = target

        val cacheFileName = downloadAndVerify(pkg, repo) ?: return
        installManager.install(InstallItem(PackageName(packageName), cacheFileName))
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

    companion object {
        private const val TAG = "InstallAllWorker"
        private const val KEY_PACKAGES = "packages"
        private const val KEY_REPO_ID = "repo_id"

        /** One tag/unique-work-name per repo, so installing from one repo never cancels or is
         *  cancelled by a batch running for another. */
        private fun workName(repoId: Int) = "$TAG-$repoId"

        /** Enqueues a one-shot install of [packageNames] from [repoId]; a second tap while one is
         *  already running for that repo replaces it. */
        fun installAll(context: Context, repoId: Int, packageNames: List<String>) {
            if (packageNames.isEmpty()) return
            val data = Data.Builder()
                .putStringArray(KEY_PACKAGES, packageNames.toTypedArray())
                .putInt(KEY_REPO_ID, repoId)
                .build()
            val request = OneTimeWorkRequestBuilder<InstallAllWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .addTag(workName(repoId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName = workName(repoId),
                existingWorkPolicy = ExistingWorkPolicy.REPLACE,
                request = request,
            )
            Log.i(TAG, "Install-all enqueued for ${packageNames.size} app(s) from repo $repoId")
        }

        /** Emits `true` while a batch install runs for [repoId], so the button can show progress and
         *  lock. */
        fun isInstalling(context: Context, repoId: Int): Flow<Boolean> =
            WorkManager.getInstance(context)
                .getWorkInfosByTagFlow(workName(repoId))
                .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
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
