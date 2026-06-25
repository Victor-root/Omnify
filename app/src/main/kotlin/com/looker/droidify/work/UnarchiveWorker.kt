package com.looker.droidify.work

import android.content.Context
import android.content.pm.PackageInstaller.EXTRA_UNARCHIVE_ALL_USERS
import android.content.pm.PackageInstaller.EXTRA_UNARCHIVE_ID
import android.content.pm.PackageInstaller.EXTRA_UNARCHIVE_PACKAGE_NAME
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Re-installs an app that Android 15+ archived to reclaim space, when the user taps its stub icon.
 *
 * Runs entirely on the Room data layer: it looks the package up across enabled repos, picks the
 * newest release this device can run (see [selectForDevice]), downloads + hash-verifies it with the
 * shared [Downloader], and hands it to [InstallManager] with the unarchive id.
 */
@HiltWorker
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class UnarchiveWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val installManager: InstallManager,
    private val appRepository: AppRepository,
    private val repoRepository: RepoRepository,
    private val downloader: Downloader,
) : CoroutineWorker(context, workerParams) {
    companion object {
        private const val TAG = "UnarchiveWorker"

        fun updateNow(context: Context, packageName: String, unarchiveId: Int, allUsers: Boolean) {
            val data = Data.Builder()
                .putString(EXTRA_UNARCHIVE_PACKAGE_NAME, packageName)
                .putInt(EXTRA_UNARCHIVE_ID, unarchiveId)
                .putBoolean(EXTRA_UNARCHIVE_ALL_USERS, allUsers)
                .build()
            val workRequest = OneTimeWorkRequestBuilder<UnarchiveWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packageName = inputData.getString(EXTRA_UNARCHIVE_PACKAGE_NAME)
            ?: return@withContext Result.failure()
        val unarchiveId = inputData.getInt(EXTRA_UNARCHIVE_ID, -1)

        // Every release of this package across enabled repos, paired with its repo.
        val apps = appRepository.getApp(PackageName(packageName)).first()
        val candidates = apps.flatMap { app ->
            val repo = repoRepository.getRepo(app.repoId.toInt())
            if (repo != null) app.packages.orEmpty().map { pkg -> pkg to repo } else emptyList()
        }
        val suggested = apps.maxOfOrNull { it.metadata.suggestedVersionCode } ?: 0L
        // TODO: once the Room model stores APK signatures (VersionEntity.toPackages currently sets
        //  signer = emptySet()), also require the chosen release's signature to match the archived
        //  app's, as the legacy worker did, instead of trusting the enabled repos.
        val target = candidates.selectForDevice(suggested)
        if (target == null) {
            Log.e(TAG, "doWork: no compatible release found for $packageName")
            return@withContext Result.failure()
        }
        val (pkg, repo) = target

        val cacheFileName = downloadAndVerify(pkg, repo)
            ?: return@withContext Result.failure()

        installManager.install(
            InstallItem(PackageName(packageName), cacheFileName, unarchiveId),
        )
        Result.success()
    }

    /**
     * Downloads [pkg] from [repo] and checks it against the index hash. Returns the release cache
     * file name on success, or null if the download or verification failed.
     */
    private suspend fun downloadAndVerify(pkg: Package, repo: Repo): String? {
        val cacheFileName = pkg.apk.hash.replace('/', '-') + ".apk"
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
            Log.e(TAG, "downloadAndVerify: download failed for ${pkg.apk.name}")
            partialFile.delete()
            return null
        }
        if (!partialFile.sha256Hex().equals(pkg.apk.hash, ignoreCase = true)) {
            Log.e(TAG, "downloadAndVerify: hash mismatch for ${pkg.apk.name}")
            partialFile.delete()
            return null
        }
        partialFile.copyTo(Cache.getReleaseFile(applicationContext, cacheFileName), overwrite = true)
        partialFile.delete()
        return cacheFileName
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
