package com.looker.droidify.data

import android.os.Build
import com.looker.droidify.data.local.dao.ApkLocaleCacheDao
import com.looker.droidify.data.local.dao.AppDao
import com.looker.droidify.data.local.dao.RepoDao
import com.looker.droidify.data.local.model.ApkLocaleCacheEntity
import com.looker.droidify.data.local.model.toApp
import com.looker.droidify.data.model.App
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.data.model.CatalogCategory
import com.looker.droidify.data.model.FilePath
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.get
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.sync.v2.model.DefaultName
import com.looker.droidify.sync.v2.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

class AppRepository @Inject constructor(
    private val appDao: AppDao,
    private val repoDao: RepoDao,
    private val apkLocaleCacheDao: ApkLocaleCacheDao,
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
        featuresToInclude: List<String>? = null,
        permissionsToInclude: List<String>? = null,
        updatedOnly: Boolean = false,
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
            featuresToInclude = featuresToInclude?.ifEmpty { null },
            permissionsToInclude = permissionsToInclude?.ifEmpty { null },
            updatedOnly = updatedOnly,
            locale = currentLocale,
        )
    }

    /**
     * Highest version *installable on this device* for every app **across all repos**, keyed by
     * packageName — used to detect updates so the Updates tab matches what the detail screen will
     * actually install (newest build wherever it lives, filtered by ABI + minSdk). Each entry also
     * carries that version's signer fingerprint(s) so callers can tell whether the update can replace
     * the installed app in place (same signer) or not (different signer).
     */
    suspend fun suggestedVersions(): Map<String, SuggestedVersion> = withContext(Dispatchers.Default) {
        appDao.deviceCompatibleVersions(
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
        ).associate { row ->
            row.packageName to SuggestedVersion(row.versionCode, row.signer.toSet())
        }
    }

    /** Number of apps currently in the catalogue. 0 means it's empty — e.g. before the first sync,
     *  or after a schema migration reset the database. */
    suspend fun appCount(): Int = withContext(Dispatchers.Default) { appDao.count() }

    /** Locale codes the app has metadata translated into (its supported languages). */
    suspend fun supportedLocales(appId: Long): List<String> = withContext(Dispatchers.Default) {
        appDao.appLocales(appId.toInt())
    }

    /** Cached real supported locales for a not-yet-installed APK (see [ApkLocaleCacheDao]), read once
     *  by inspecting the APK's compiled resources — null if nothing has been cached for it yet. */
    suspend fun cachedApkLocales(apkHash: String): List<String>? = withContext(Dispatchers.Default) {
        apkLocaleCacheDao.getLocales(apkHash)
    }

    /** Caches the real supported locales for a not-yet-installed APK, keyed by its content hash. */
    suspend fun cacheApkLocales(apkHash: String, locales: List<String>) {
        withContext(Dispatchers.Default) {
            apkLocaleCacheDao.setLocales(ApkLocaleCacheEntity(apkHash, locales))
        }
    }

    /**
     * Top apps by download count for the Discover home's "Most downloaded" carousel. Empty until the
     * download-stats worker has fetched data (or if stats are disabled), and guarded so a query
     * failure simply yields no carousel rather than crashing the home screen.
     */
    suspend fun mostDownloadedApps(limit: Int): List<AppMinimal> = withContext(Dispatchers.Default) {
        val currentLocale = localeStream.first()
        runCatching { appDao.mostDownloaded(locale = currentLocale, limit = limit) }
            .getOrDefault(emptyList())
    }

    /**
     * Apps that need or use root, for the "For rooted devices" carousel: the superuser permission plus
     * strong root phrasing in the app text (see [AppDao.rootApps]). Guarded so a query issue simply
     * yields no carousel rather than crashing the home screen.
     */
    suspend fun rootApps(limit: Int): List<AppMinimal> = withContext(Dispatchers.Default) {
        val currentLocale = localeStream.first()
        runCatching { appDao.rootApps(locale = currentLocale, limit = limit) }
            .getOrDefault(emptyList())
    }

    /**
     * The real app icon for every repo that serves exactly one app, keyed by repo id (see
     * [AppDao.singleAppRepoIcons]) — lets the repositories list show that app's actual icon instead of a
     * single-app repo's own often-unusable declared icon (many small repos never customise it and
     * fdroidserver defaults to a QR code of the repo address).
     */
    suspend fun singleAppRepoIcons(): Map<Int, FilePath> = withContext(Dispatchers.Default) {
        val currentLocale = localeStream.first()
        runCatching { appDao.singleAppRepoIcons(locale = currentLocale) }
            .getOrDefault(emptyList())
            .mapNotNull { row ->
                val icon = FilePath(row.baseAddress, row.iconName) ?: return@mapNotNull null
                row.repoId to icon
            }
            .toMap()
    }

    /** Emits whenever the catalogue (apps/versions) changes, e.g. after a sync. */
    val catalogChanges: Flow<Int>
        get() = appDao.catalogSizeStream()

    /** Emits whenever the download-stats table changes (the stats worker inserted a monthly file),
     *  so the "Most downloaded" carousel refreshes as soon as stats arrive. */
    val downloadStatsChanges: Flow<Int>
        get() = appDao.downloadStatsCountStream()

    /** Categories with their localized display names (see [RepoDao.categoriesLocalized]). The user's
     *  language is resolved once on collection; changing it recreates the activity anyway. */
    val categories: Flow<List<CatalogCategory>>
        get() = flow {
            emitAll(repoDao.categoriesLocalized(languagePrefix(localeStream.first())))
        }

    /** A SQL LIKE pattern (e.g. "fr%") for the user's language, so any region variant matches. */
    private fun languagePrefix(language: String): String {
        val lang = if (language == "system") {
            Locale.getDefault().language
        } else {
            language.substringBefore('-').substringBefore('_')
        }
        return "$lang%"
    }

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
