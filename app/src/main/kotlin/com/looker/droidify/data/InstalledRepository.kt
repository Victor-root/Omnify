package com.looker.droidify.data

import com.looker.droidify.data.local.dao.ConfirmedInstallDao
import com.looker.droidify.data.local.dao.InstalledDao
import com.looker.droidify.data.local.model.ConfirmedInstallEntity
import com.looker.droidify.data.local.model.toDomain
import com.looker.droidify.data.local.model.toEntity
import com.looker.droidify.model.InstalledItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class InstalledRepository @Inject constructor(
    private val installedDao: InstalledDao,
    private val confirmedInstallDao: ConfirmedInstallDao,
) {

    fun getStream(packageName: String): Flow<InstalledItem?> {
        return installedDao.stream(packageName).map { entity ->
            entity?.toDomain()
        }
    }

    fun getAllStream(): Flow<List<InstalledItem>> {
        return installedDao.streamAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    suspend fun get(packageName: String): InstalledItem? {
        return installedDao.get(packageName)?.toDomain()
    }

    suspend fun put(installedItem: InstalledItem) {
        installedDao.insert(installedItem.toEntity())
    }

    suspend fun putAll(installedItems: List<InstalledItem>) {
        installedDao.replaceAll(installedItems.map { it.toEntity() })
    }

    suspend fun delete(packageName: String): Int {
        confirmedInstallDao.delete(packageName)
        return installedDao.delete(packageName)
    }

    /** Records that Omnify itself just confirmed installing [versionCode] of [packageName]. See
     *  [com.looker.droidify.data.local.model.ConfirmedInstallEntity]'s own doc comment for why this
     *  lives apart from the rest of this repository. */
    suspend fun recordConfirmedInstall(packageName: String, versionCode: Long) {
        confirmedInstallDao.put(ConfirmedInstallEntity(packageName, versionCode))
    }

    /** The versionCode Omnify last confirmed installing for [packageName], or null if it never has (or
     *  the package was since uninstalled, which clears this same as everything else in [delete]). */
    suspend fun confirmedInstallVersionCode(packageName: String): Long? {
        return confirmedInstallDao.versionCodeOf(packageName)
    }
}
