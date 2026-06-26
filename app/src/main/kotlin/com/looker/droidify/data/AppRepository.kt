package com.looker.droidify.data

import android.os.Build
import com.looker.droidify.data.local.dao.AppDao
import com.looker.droidify.data.local.dao.RepoDao
import com.looker.droidify.data.local.model.toApp
import com.looker.droidify.data.model.App
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.sync.v2.model.DefaultName
import com.looker.droidify.sync.v2.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AppRepository @Inject constructor(
    private val appDao: AppDao,
    private val repoDao: RepoDao,
    private val settingsRepository: SettingsRepository,
) {

    private val localeStream = settingsRepository.get { language }

    suspend fun apps(
        sortOrder: SortOrder,
        searchQuery: String? = null,
        repoId: Int? = null,
        categoriesToInclude: List<DefaultName>? = null,
        categoriesToExclude: List<DefaultName>? = null,
        antiFeaturesToInclude: List<Tag>? = null,
        antiFeaturesToExclude: List<Tag>? = null,
    ): List<AppMinimal> = withContext(Dispatchers.Default) {
        val currentLocale = localeStream.first()
        appDao.query(
            sortOrder = sortOrder,
            searchQuery = searchQuery?.ifEmpty { null },
            repoId = repoId,
            categoriesToInclude = categoriesToInclude?.ifEmpty { null },
            categoriesToExclude = categoriesToExclude?.ifEmpty { null },
            antiFeaturesToInclude = antiFeaturesToInclude?.ifEmpty { null },
            antiFeaturesToExclude = antiFeaturesToExclude?.ifEmpty { null },
            locale = currentLocale,
        )
    }

    /**
     * Highest version *installable on this device* for every app, keyed by appId — used to detect
     * updates so the Updates tab matches what the detail screen will actually install (it filters by
     * ABI + minSdk, unlike a device-blind MAX which over-reports for multi-ABI apps). Each entry also
     * carries that version's signer fingerprint(s) so callers can tell whether the update can replace
     * the installed app in place (same signer) or not (different signer).
     */
    suspend fun suggestedVersions(): Map<Int, SuggestedVersion> = withContext(Dispatchers.Default) {
        appDao.deviceCompatibleVersions(
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
        ).associate { row ->
            row.appId to SuggestedVersion(row.versionCode, row.signer.toSet())
        }
    }

    /** Number of apps currently in the catalogue. 0 means it's empty — e.g. before the first sync,
     *  or after a schema migration reset the database. */
    suspend fun appCount(): Int = withContext(Dispatchers.Default) { appDao.count() }

    /** Emits whenever the catalogue (apps/versions) changes, e.g. after a sync. */
    val catalogChanges: Flow<Int>
        get() = appDao.catalogSizeStream()

    val categories: Flow<List<DefaultName>>
        get() = repoDao.categories().map { it.map { category -> category.defaultName } }

    fun getApp(packageName: PackageName): Flow<List<App>> = combine(
        appDao.queryAppEntity(packageName.name),
        localeStream,
    ) { appEntityRelations, locale ->
        appEntityRelations.map {
            val repo = repoDao.getRepo(it.app.repoId)!!
            it.toApp(locale, repo)
        }
    }

    suspend fun addToFavourite(packageName: PackageName): Boolean {
        val favourites = settingsRepository.get { favouriteApps }.first()
        val wasInFavourites = packageName.name in favourites
        settingsRepository.toggleFavourites(packageName.name)
        return !wasInFavourites
    }
}

/**
 * The catalogue version that would be installed for an app on this device: its versionCode and the
 * signing-certificate fingerprint(s) of that exact version (lowercase hex SHA-256, same format as an
 * installed app's stored signature).
 */
data class SuggestedVersion(
    val versionCode: Long,
    val signers: Set<String>,
)
