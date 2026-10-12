package com.looker.droidify.data

import android.content.Context
import com.looker.droidify.data.local.dao.AppDao
import com.looker.droidify.data.local.dao.IndexDao
import com.looker.droidify.data.local.dao.RepoDao
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.sync.v2.model.IndexV2
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps each repository's apps in the catalogue in step with whether it is enabled.
 *
 * A disabled repository is hidden by removing its apps, and every screen reads the catalogue as it is,
 * so an index written after that removal brings them all back. Writing an index and removing a
 * repository's apps share one lock, and each re-reads the switch inside it: whatever order a sync and
 * a toggle land in, a disabled repository ends up with no apps and an enabled one keeps them.
 */
@Singleton
class RepoCatalogue @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val repoDao: RepoDao,
    private val appDao: AppDao,
    private val indexDao: IndexDao,
) {

    private val lock = Mutex()

    /** Runs [block] for as long as [repoId] stays enabled: null if it is disabled before or meanwhile. */
    suspend fun <T : Any> whileEnabled(repoId: Int, block: suspend () -> T): T? {
        if (!settingsRepository.isRepoEnabled(repoId)) return null
        return merge<T?>(
            flow { emit(block()) },
            settingsRepository.getEnabledRepoIds().filter { repoId !in it }.map { null },
        ).first()
    }

    /** Writes [index] as [repoId]'s catalogue, unless the repository has been disabled. True if written. */
    suspend fun save(repoId: Int, fingerprint: Fingerprint, index: IndexV2): Boolean {
        lock.withLock {
            if (!settingsRepository.isRepoEnabled(repoId)) return false
            indexDao.insertIndex(fingerprint = fingerprint, index = index, expectedRepoId = repoId)
            return true
        }
    }

    /** Removes [repoId]'s apps, unless the repository has been enabled again since. */
    suspend fun clear(repoId: Int) {
        lock.withLock {
            if (!settingsRepository.isRepoEnabled(repoId)) removeApps(repoId)
        }
    }

    /** Removes the apps every disabled repository still holds, left by a sync that outlived its switch. */
    suspend fun clearDisabled() {
        lock.withLock {
            val enabled = settingsRepository.getEnabledRepoIds().first()
            appDao.repoIds().filter { it !in enabled }.forEach { removeApps(it) }
        }
    }

    /** The timestamp goes too, so the next sync reads the whole index rather than a diff against apps
     *  no longer stored. */
    private suspend fun removeApps(repoId: Int) {
        repoDao.resetTimestamp(repoId)
        runCatching {
            File(context.cacheDir, "index").listFiles()?.forEach { file ->
                if (file.name.startsWith("repo_${repoId}_")) file.delete()
            }
        }
        appDao.deleteByRepoId(repoId)
    }
}
