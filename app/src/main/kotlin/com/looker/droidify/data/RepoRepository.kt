package com.looker.droidify.data

import android.content.Context
import android.util.Log
import com.looker.droidify.BuildConfig
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
import com.looker.droidify.network.header.basicAuthorization
import com.looker.droidify.sync.SyncState
import com.looker.droidify.sync.v1.V1Syncable
import com.looker.droidify.sync.v2.EntrySyncable
import com.looker.droidify.sync.v2.model.IndexV2
import com.looker.droidify.utility.common.extension.exceptCancellation
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import com.looker.droidify.work.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
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
    private val repoCatalogue: RepoCatalogue,
) {

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
        // The key only for a repository with a login: resolving it can mean unlocking the Keystore, and
        // every app detail page reads its repositories through here.
        val auth = authDao.authFor(id)?.toAuthentication(keyStream.first())
        val currentLocale = locale.first()
        val enabled = id in settings.first().enabledRepoIds
        val mirrors = getMirrors(id)
        val name = repoDao.name(id, currentLocale) ?: repoEntity.address
        val description = repoDao.description(id, currentLocale) ?: ""
        val icon = repoDao.icon(id, currentLocale)?.icon?.name
        val repo = repoEntity.toRepo(
            mirrors = mirrors,
            enabled = enabled,
            authentication = auth,
            name = name,
            description = description,
            icon = icon,
        )
        trailRepoIcon {
            "read: repo $id (${repoEntity.address}) locale=$currentLocale " +
                "stored=${repoDao.allIcons(id).joinToString { "${it.locale}:${it.icon.name}" }} " +
                "chosen=[$icon] url=[${repo.icon?.path}]"
        }
        return repo
    }

    fun repo(id: Int): Flow<Repo?> = combine(
        repoDao.repo(id),
        settings.map { it.enabledRepoIds },
    ) { repo, enabled ->
        // Same as getRepo: the key only once there is a login to open with it.
        val auth = authDao.authFor(id)?.toAuthentication(keyStream.first())
        val mirrors = getMirrors(id)
        val currentLocale = locale.first()
        val name = repoDao.name(id, currentLocale) ?: repo?.address ?: "Unknown"
        val description = repoDao.description(id, currentLocale) ?: ""
        val icon = repoDao.icon(id, currentLocale)?.icon?.name
        trailRepoIcon {
            "stream: repo $id (${repo?.address}) locale=$currentLocale " +
                "stored=${repoDao.allIcons(id).joinToString { "${it.locale}:${it.icon.name}" }} " +
                "chosen=[$icon]"
        }
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

    /** Whether any repository exists at all: [repos] would also read each one's name, description and
     *  icon just to answer that. */
    suspend fun hasRepos(): Boolean = repoDao.hasAny()

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

    /**
     * What each repository holding a login expects in an `Authorization` header, by the address it
     * answers on. Kept live, so adding, editing or removing one is followed.
     *
     * Read from the credentials rather than from the repositories: nearly every repository has none,
     * and this table changes only when a login does, unlike the repository table which is written on
     * every sync. With no login at all, the key is not even resolved: that can mean unlocking the
     * Keystore, or creating and storing a key on a fresh install, and the first image waits on this.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val authorizations: Flow<Map<String, String>>
        get() = authDao.stream().flatMapLatest { credentials ->
            if (credentials.isEmpty()) return@flatMapLatest flowOf(emptyMap())
            keyStream.map { key ->
                val addresses = repoDao.getAddressByIds(credentials.map { it.repoId })
                credentials.mapNotNull { entity ->
                    val address = addresses[entity.repoId] ?: return@mapNotNull null
                    val authentication = entity.toAuthentication(key) ?: return@mapNotNull null
                    address to basicAuthorization(authentication.username, authentication.password)
                }.toMap()
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

    /** Adds the repository and answers with the id of the row it went into. */
    suspend fun insertRepo(
        address: String,
        fingerprint: String?,
        username: String?,
        password: String?,
        name: String? = null,
        description: String? = null,
    ): Int {
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
        if (BuildConfig.TRACES) Log.d(TRACE_TAG, "insertRepo: $address stored as row $id")
        if (password != null && username != null) {
            if (BuildConfig.TRACES) Log.d(TRACE_TAG, "insertRepo: resolving the encryption key")
            val key = keyStream.first()
            if (BuildConfig.TRACES) Log.d(TRACE_TAG, "insertRepo: key resolved, encrypting the password")
            val (encrypted, iv) = key.encrypt(password)
            val authEntity = AuthenticationEntity(
                password = encrypted,
                username = username,
                initializationVector = iv,
                repoId = id.toInt(),
            )
            authDao.insert(authEntity)
            if (BuildConfig.TRACES) Log.d(TRACE_TAG, "insertRepo: credentials stored for row $id")
        }
        return id.toInt()
    }

    suspend fun enableRepository(repo: Repo, enable: Boolean) {
        settingsRepository.setRepoEnabled(repo.id, enable)
        if (enable) {
            SyncWorker.syncRepo(context, repo.id)
        } else {
            repoCatalogue.clear(repo.id)
        }
    }

    /**
     * Downloads [repo]'s index and stores it, for as long as the repository stays enabled. Disabling it
     * stops the download, and an index downloaded by then is dropped (see [RepoCatalogue]): neither is a
     * failure, so both answer true and the worker doesn't retry a sync nobody wants any more.
     */
    suspend fun sync(repo: Repo, onState: ((SyncState) -> Unit)? = null): Boolean {
        val fetched = repoCatalogue.whileEnabled(repo.id) {
            val lock = fetchLock(repo.id)
            val label = DiagnosticLog.repoLabel(repo)
            if (lock.isLocked) DiagnosticLog.log("sync $label: already being synced, waiting for that one")
            lock.withLock {
                DiagnosticLog.log("sync $label: start")
                fetchIndex(repo, onState).also {
                    DiagnosticLog.log("sync $label: end, success=${it.success}, new index=${it.index != null}")
                }
            }
        }
        if (fetched == null) {
            Log.i(TAG, "${repo.name} (id=${repo.id}) is disabled; sync skipped")
            return true
        }
        val fingerprint = fetched.fingerprint
        val index = fetched.index
        if (index != null && fingerprint != null) {
            try {
                Log.i(TAG, "Saving index for ${repo.name} (id=${repo.id}): ${index.packages.size} packages")
                // What the repository says its own logo is, straight out of the index and before
                // anything here has touched it: the first place the answer can differ from what the
                // owner expects, and the only one nothing in the app can put right.
                trailRepoIcon {
                    "sync: repo ${repo.id} index declares icon=${index.repo.icon} " +
                        "name=${index.repo.name} address=${index.repo.address}"
                }
                if (!repoCatalogue.save(repo.id, fingerprint, index)) {
                    Log.i(TAG, "${repo.name} (id=${repo.id}) was disabled during its sync; index dropped")
                    return true
                }
                Log.i(TAG, "Saved ${repo.name} (id=${repo.id}); catalog now holds ${appDao.count()} apps")
            } catch (t: Throwable) {
                t.exceptCancellation()
                // Saving the parsed index can fail (e.g. OOM while inserting a large index, or a DB
                // error). Report it instead of letting the worker think the sync succeeded.
                Log.e(TAG, "Saving the index failed for ${repo.name} (id=${repo.id})", t)
                return false
            }
        } else {
            Log.i(
                TAG,
                "No index to save for ${repo.name} (id=${repo.id}); " +
                    "success=${fetched.success} (up to date or failed)",
            )
        }
        return fetched.success
    }

    /**
     * One at a time per repository: the scheduled sync and the one the user asked for run on separate
     * workers and can reach the same repository together, and both work on the same cached index file.
     * One downloading over it while the other reads it makes the read hit a file that shrank under it,
     * which Android reports as a native crash (SIGBUS) that no try/catch can stop.
     *
     * The locks live in the companion because this class is not a singleton: each worker gets an
     * instance of its own, and a lock per instance would never be shared by the two that collide.
     */
    private fun fetchLock(repoId: Int): Mutex = fetchLocks.getOrPut(repoId) { Mutex() }

    private suspend fun fetchIndex(repo: Repo, onState: ((SyncState) -> Unit)?): FetchedIndex {
        var success = false
        var parsedFingerprint: Fingerprint? = null
        var parsedIndex: IndexV2? = null
        val label = DiagnosticLog.repoLabel(repo)
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
                is SyncState.IndexDownload.Failure -> {
                    Log.e(TAG, "Index download failed for ${repo.name} (id=${repo.id})", state.error)
                    DiagnosticLog.log("sync $label: download failed, ${state.error.javaClass.simpleName}")
                }
                is SyncState.JarParsing.Failure -> {
                    Log.e(TAG, "Index signature check failed for ${repo.name} (id=${repo.id})", state.error)
                    DiagnosticLog.log("sync $label: jar check failed, ${state.error.javaClass.simpleName}")
                }
                is SyncState.JsonParsing.Failure -> {
                    Log.e(TAG, "Index parse failed for ${repo.name} (id=${repo.id})", state.error)
                    DiagnosticLog.log("sync $label: parse failed, ${state.error.javaClass.simpleName}")
                }
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
            trailRepoIcon { "sync: repo ${repo.id} has no v2 index, reading the v1 one" }
            v1Syncable.sync(repo, handleState)
        }
        return FetchedIndex(success = success, fingerprint = parsedFingerprint, index = parsedIndex)
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

    /** What [fetchIndex] read: [index] is null when the repository is up to date or the sync failed. */
    private class FetchedIndex(
        val success: Boolean,
        val fingerprint: Fingerprint?,
        val index: IndexV2?,
    )

    private companion object {
        private const val TAG = "RepoRepository"

        private val fetchLocks = ConcurrentHashMap<Int, Mutex>()

        /** Temporary: the add-a-repository trail's tag, shared with the screen so one Logcat filter
         *  covers the whole path from the form down to the write. Debug builds only, and it goes away
         *  with the rest of that trail. */
        private const val TRACE_TAG = "OmnifyRepo"
    }
}
