package com.looker.droidify.data

import android.content.Context
import android.util.Log
import com.looker.droidify.data.encryption.EncryptionStorage
import com.looker.droidify.data.local.dao.AppDao
import com.looker.droidify.data.local.dao.AuthDao
import com.looker.droidify.data.local.dao.IndexDao
import com.looker.droidify.data.local.dao.RepoDao
import com.looker.droidify.data.local.model.AuthenticationEntity
import com.looker.droidify.data.local.model.LocalizedRepoDescriptionEntity
import com.looker.droidify.data.local.model.LocalizedRepoNameEntity
import com.looker.droidify.data.local.model.RepoEntity
import com.looker.droidify.data.local.model.toAuthentication
import com.looker.droidify.data.local.model.toRepo
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.data.model.Repo
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.di.IoDispatcher
import com.looker.droidify.network.Downloader
import com.looker.droidify.sync.LocalSyncable
import com.looker.droidify.sync.SyncState
import com.looker.droidify.sync.v1.V1Syncable
import com.looker.droidify.sync.v2.EntrySyncable
import com.looker.droidify.sync.v2.model.IndexV2
import com.looker.droidify.utility.common.extension.exceptCancellation
import com.looker.droidify.work.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject

class RepoRepository @Inject constructor(
    encryptionStorage: EncryptionStorage,
    downloader: Downloader,
    @param:ApplicationContext private val context: Context,
    @IoDispatcher syncDispatcher: CoroutineDispatcher,
    private val repoDao: RepoDao,
    private val authDao: AuthDao,
    private val indexDao: IndexDao,
    private val settingsRepository: SettingsRepository,
    private val appDao: AppDao,
) {

    private val localSyncable = LocalSyncable(context = context)

    private val v2Syncable = EntrySyncable(
        context = context,
        downloader = downloader,
        dispatcher = syncDispatcher,
    )

    private val v1Syncable = V1Syncable(
        context = context,
        downloader = downloader,
        dispatcher = syncDispatcher,
    )

    private val settings = settingsRepository.data
    private val keyStream = encryptionStorage.key
    private val locale = settings.map { it.language }

    suspend fun getRepo(id: Int): Repo? {
        val repoEntity = repoDao.getRepo(id) ?: return null
        val key = keyStream.first()
        val auth = authDao.authFor(id)?.toAuthentication(key)
        val currentLocale = locale.first()
        val enabled = id in settings.first().enabledRepoIds
        val mirrors = getMirrors(id)
        val name = repoDao.name(id, currentLocale) ?: repoEntity.address
        val description = repoDao.description(id, currentLocale) ?: ""
        val icon = repoDao.icon(id, currentLocale)?.icon?.name
        return repoEntity.toRepo(
            mirrors = mirrors,
            enabled = enabled,
            authentication = auth,
            name = name,
            description = description,
            icon = icon,
        )
    }

    fun repo(id: Int): Flow<Repo?> = combine(
        repoDao.repo(id),
        settings.map { it.enabledRepoIds },
        keyStream,
    ) { repo, enabled, key ->
        val auth = authDao.authFor(id)?.toAuthentication(key)
        val mirrors = getMirrors(id)
        val currentLocale = locale.first()
        val name = repoDao.name(id, currentLocale) ?: repo?.address ?: "Unknown"
        val description = repoDao.description(id, currentLocale) ?: ""
        val icon = repoDao.icon(id, currentLocale)?.icon?.name
        repo?.toRepo(
            mirrors = mirrors,
            enabled = repo.id in enabled,
            authentication = auth,
            name = name,
            description = description,
            icon = icon,
        )
    }.flowOn(Dispatchers.Default)

    suspend fun deleteRepo(id: Int) {
        repoDao.delete(id)
    }

    val repos: Flow<List<Repo>> = combine(
        repoDao.stream(),
        settings.map { it.enabledRepoIds },
    ) { repos, enabledIds ->
        val currentLocale = locale.first()
        repos.map { repoEntity ->
            val name = repoDao.name(repoEntity.id, currentLocale) ?: repoEntity.address
            val description = repoDao.description(repoEntity.id, currentLocale) ?: ""
            val icon = repoDao.icon(repoEntity.id, currentLocale)?.icon?.name
            repoEntity.toRepo(
                mirrors = emptyList(),
                authentication = null,
                enabled = repoEntity.id in enabledIds,
                name = name,
                description = description,
                icon = icon,
            )
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    val addresses: Flow<Set<String>>
        get() = combine(
            repoDao.stream(),
            repoDao.mirrors(),
        ) { repos, mirrors ->
            repos.map { it.address }.toSet() + mirrors.map { it.url }
        }.flowOn(Dispatchers.Default)

    fun getEnabledRepos(): Flow<List<Repo>> = settingsRepository
        .get { enabledRepoIds }
        .map { ids -> ids.mapNotNull { repoId -> getRepo(repoId) } }

    suspend fun insertRepo(
        address: String,
        fingerprint: String?,
        username: String?,
        password: String?,
        name: String? = null,
        description: String? = null,
    ) {
        val id = indexDao.insertRepo(
            RepoEntity(
                address = address,
                fingerprint = Fingerprint(fingerprint.orEmpty()),
                timestamp = null,
                webBaseUrl = address,
            ),
        )
        if (name != null) {
            indexDao.insertLocalizedRepoNames(
                listOf(LocalizedRepoNameEntity(id.toInt(), "en-US", name)),
            )
        }

        if (description != null) {
            indexDao.insertLocalizedRepoDescription(
                listOf(LocalizedRepoDescriptionEntity(id.toInt(), "en-US", description)),
            )
        }
        if (password != null && username != null) {
            val key = keyStream.first()
            val (encrypted, iv) = key.encrypt(password)
            val authEntity = AuthenticationEntity(
                password = encrypted,
                username = username,
                initializationVector = iv,
                repoId = id.toInt(),
            )
            authDao.insert(authEntity)
        }
    }

    suspend fun enableRepository(repo: Repo, enable: Boolean) {
        settingsRepository.setRepoEnabled(repo.id, enable)
        if (enable) {
            SyncWorker.syncRepo(context, repo.id)
        } else {
            repoDao.resetTimestamp(repo.id)
            runCatching {
                val indexDir = File(context.cacheDir, "index")
                if (indexDir.exists()) {
                    indexDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("repo_${repo.id}_")) {
                            file.delete()
                        }
                    }
                }
            }
            appDao.deleteByRepoId(repo.id)
        }
    }

    suspend fun sync(repo: Repo, onState: ((SyncState) -> Unit)? = null): Boolean {
        var success = false
        var parsedFingerprint: Fingerprint? = null
        var parsedIndex: IndexV2? = null
        val handleState: (SyncState) -> Unit = { state ->
            onState?.invoke(state)
            when (state) {
                is SyncState.JsonParsing.Success -> {
                    parsedFingerprint = state.fingerprint
                    parsedIndex = state.index
                    success = true
                }
                // Surface failures instead of swallowing them — otherwise a sync that downloaded but
                // couldn't parse/verify the index looks "successful" while the catalog stays empty.
                is SyncState.IndexDownload.Failure ->
                    Log.e(TAG, "Index download failed for ${repo.name} (id=${repo.id})", state.error)
                is SyncState.JarParsing.Failure ->
                    Log.e(TAG, "Index signature check failed for ${repo.name} (id=${repo.id})", state.error)
                is SyncState.JsonParsing.Failure ->
                    Log.e(TAG, "Index parse failed for ${repo.name} (id=${repo.id})", state.error)
                else -> Unit
            }
        }
        v2Syncable.sync(repo, handleState)
        if (!success) {
            // Plenty of repos (many small or unmaintained ones, e.g. NanoDroid) never published the
            // newer v2 "entry.jar" index and only ever will serve the legacy v1 index-v1.jar. Without
            // this fallback those repos silently failed to sync forever, and their catalogue stayed
            // empty no matter how many times the user retried.
            Log.i(TAG, "V2 sync unavailable for ${repo.name} (id=${repo.id}); falling back to v1 index")
            v1Syncable.sync(repo, handleState)
        }
        val fingerprint = parsedFingerprint
        val index = parsedIndex
        if (index != null && fingerprint != null) {
            try {
                Log.i(TAG, "Saving index for ${repo.name} (id=${repo.id}): ${index.packages.size} packages")
                indexDao.insertIndex(
                    fingerprint = fingerprint,
                    index = index,
                    expectedRepoId = repo.id,
                )
                Log.i(TAG, "Saved ${repo.name} (id=${repo.id}); catalog now holds ${appDao.count()} apps")
            } catch (t: Throwable) {
                t.exceptCancellation()
                // Saving the parsed index can fail (e.g. OOM while inserting a large index, or a DB
                // error). Report it instead of letting the worker think the sync succeeded.
                Log.e(TAG, "Saving the index failed for ${repo.name} (id=${repo.id})", t)
                return false
            }
        } else {
            Log.i(TAG, "No index to save for ${repo.name} (id=${repo.id}); success=$success (up to date or failed)")
        }
        return success
    }

    suspend fun syncAll(): Boolean {
        val repos = getEnabledRepos().first()
        Log.i(TAG, "syncAll: ${repos.size} enabled repo(s): ${repos.joinToString { it.name }}")
        var allSucceeded = true
        // Sync repositories one at a time. Decoding a full index allocates many times the file size
        // (the F-Droid index is tens of MB), so parsing several at once can exhaust the heap and OOM
        // on a fresh full sync — and the failure was being swallowed, leaving the catalog empty.
        // Sequential is slightly slower but reliable.
        for (repo in repos) {
            val synced = try {
                sync(repo)
            } catch (t: Throwable) {
                t.exceptCancellation()
                Log.e(TAG, "Sync failed for ${repo.name} (id=${repo.id})", t)
                false
            }
            if (!synced) allSucceeded = false
        }
        return allSucceeded
    }

    private suspend fun getMirrors(repoId: Int): List<String> =
        repoDao.mirrors(repoId).map { it.url }

    private companion object {
        private const val TAG = "RepoRepository"
    }
}
