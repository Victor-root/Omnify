package com.looker.droidify.data.local.dao

import androidx.room.Dao
import androidx.room.MapInfo
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.looker.droidify.data.local.model.AntiFeatureAppRelation
import com.looker.droidify.data.local.model.AppEntity
import com.looker.droidify.data.local.model.AppEntityRelations
import com.looker.droidify.data.local.model.CategoryAppRelation
import com.looker.droidify.data.local.model.LocalizedAppIconEntity
import com.looker.droidify.data.local.model.VersionEntity
import com.looker.droidify.data.model.AppMinimal
import com.looker.droidify.data.model.FilePath
import com.looker.droidify.data.model.PackageName
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.sync.v2.model.DefaultName
import com.looker.droidify.sync.v2.model.Tag
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {

    @RawQuery(
        observedEntities = [
            AppEntity::class,
            VersionEntity::class,
            CategoryAppRelation::class,
            AntiFeatureAppRelation::class,
        ],
    )
    fun _rawStreamAppEntities(query: SimpleSQLiteQuery): Flow<List<AppEntity>>

    @RawQuery
    suspend fun _rawQueryAppEntities(query: SimpleSQLiteQuery): List<AppEntity>

    suspend fun query(
        sortOrder: SortOrder,
        searchQuery: String? = null,
        repoId: Int? = null,
        categoriesToInclude: List<DefaultName>? = null,
        categoriesToExclude: List<DefaultName>? = null,
        antiFeaturesToInclude: List<Tag>? = null,
        antiFeaturesToExclude: List<Tag>? = null,
        // Each entry keeps only apps that declare it as a manifest <uses-feature> on some version (e.g.
        // "android.software.leanback" for apps built for Android TV).
        featuresToInclude: List<String>? = null,
        // Each entry keeps only apps that declare it as a manifest <uses-permission> on some version
        // (e.g. "moe.shizuku.manager.permission.API_V23" for apps that integrate with Shizuku).
        permissionsToInclude: List<String>? = null,
        // Keep only apps updated at least once since they were added (lastUpdated > added). Used by the
        // "Recently updated" carousel so it isn't a copy of the "New apps" one.
        updatedOnly: Boolean = false,
        locale: String,
    ): List<AppMinimal> = _rawQueryAppMinimal(
        searchQueryMinimal(
            sortOrder = sortOrder,
            searchQuery = searchQuery,
            repoId = repoId,
            categoriesToInclude = categoriesToInclude,
            categoriesToExclude = categoriesToExclude,
            antiFeaturesToInclude = antiFeaturesToInclude,
            antiFeaturesToExclude = antiFeaturesToExclude,
            featuresToInclude = featuresToInclude,
            permissionsToInclude = permissionsToInclude,
            updatedOnly = updatedOnly,
            locale = locale,
        ),
    ).map {
        AppMinimal(
            appId = it.appId.toLong(),
            packageName = PackageName(it.packageName),
            name = it.name,
            summary = it.summary,
            icon = FilePath(it.baseAddress, it.iconName),
            suggestedVersion = it.suggestedVersion ?: "",
        )
    }

    data class AppMinimalRow(
        val appId: Int,
        val packageName: String,
        val name: String,
        val summary: String?,
        val baseAddress: String,
        val iconName: String?,
        val suggestedVersion: String?,
    )

    @RawQuery
    suspend fun _rawQueryAppMinimal(query: SimpleSQLiteQuery): List<AppMinimalRow>

    /**
     * Top apps by total download count (IzzyOnDroid stats), most-downloaded first — the data behind
     * F-Droid v2's "Most downloaded" carousel. The inner JOIN to the aggregated download_stats means
     * only apps that *both* have stats and exist in the catalogue come back, so the carousel is simply
     * empty until the stats worker has fetched data. Built as a raw query (reusing the proven
     * [_rawQueryAppMinimal] SELECT) so a typo can't break the build — at worst it yields no rows.
     */
    suspend fun mostDownloaded(locale: String, limit: Int): List<AppMinimal> {
        val query = SimpleSQLiteQuery(
            """
            SELECT
                app.id AS appId,
                app.packageName AS packageName,
                COALESCE(n_loc.name, n_en.name) AS name,
                COALESCE(s_loc.summary, s_en.summary) AS summary,
                repo.address AS baseAddress,
                COALESCE(i_loc.icon_name, i_en.icon_name) AS iconName,
                (
                    SELECT v.versionName FROM version v
                    WHERE v.appId = app.id
                    ORDER BY v.versionCode DESC
                    LIMIT 1
                ) AS suggestedVersion
            FROM app
            JOIN repository AS repo ON app.repoId = repo.id
            JOIN (
                SELECT packageName, SUM(downloads) AS total
                FROM download_stats
                GROUP BY packageName
            ) AS ds ON ds.packageName = app.packageName
            LEFT JOIN localized_app_name AS n_loc ON n_loc.appId = app.id AND n_loc.locale = ?
            LEFT JOIN localized_app_name AS n_en ON n_en.appId = app.id AND n_en.locale = 'en-US'
            LEFT JOIN localized_app_summary AS s_loc ON s_loc.appId = app.id AND s_loc.locale = ?
            LEFT JOIN localized_app_summary AS s_en ON s_en.appId = app.id AND s_en.locale = 'en-US'
            LEFT JOIN localized_app_icon AS i_loc ON i_loc.appId = app.id AND i_loc.locale = ?
            LEFT JOIN localized_app_icon AS i_en ON i_en.appId = app.id AND i_en.locale = 'en-US'
            GROUP BY app.packageName
            ORDER BY ds.total DESC
            LIMIT ?
            """.trimIndent(),
            // Explicit Any[] so the mixed String/Int args don't infer a reified intersection type
            // (matches deviceCompatibleVersions' `listOf<Any>(...)`).
            arrayOf<Any>(locale, locale, locale, limit),
        )
        return _rawQueryAppMinimal(query).map {
            AppMinimal(
                appId = it.appId.toLong(),
                packageName = PackageName(it.packageName),
                name = it.name,
                summary = it.summary,
                icon = FilePath(it.baseAddress, it.iconName),
                suggestedVersion = it.suggestedVersion ?: "",
            )
        }
    }

    /**
     * Apps for the "For rooted devices" carousel. Detecting root is fuzzy: the legacy ACCESS_SUPERUSER
     * permission is declared by only a fraction of root apps (root itself needs no manifest permission
     * — an app just runs `su` at runtime), so relying on it alone misses most. This also scans the
     * localized name/summary/description for strong root phrasing (Magisk/KernelSU/"requires root"…),
     * while excluding negations ("no root", "without root"…) so an app that advertises "works without
     * root" isn't wrongly included. Union of the permission and the keyword match, newest first.
     */
    suspend fun rootApps(locale: String, limit: Int): List<AppMinimal> {
        // The searchable blob: localized (falling back to en-US) description + summary + name.
        val text = "LOWER(" +
            "COALESCE(d_loc.description, d_en.description, '') || ' ' || " +
            "COALESCE(s_loc.summary, s_en.summary, '') || ' ' || " +
            "COALESCE(n_loc.name, n_en.name, ''))"
        fun clause(words: List<String>, op: String, join: String) = words.joinToString(join) {
            "$text $op '%${it.replace("'", "''")}%'"
        }
        val permMatch = "EXISTS (SELECT 1 FROM version WHERE version.appId = app.id AND " +
            "(version.permissions LIKE '%\"$ROOT_PERMISSION\"%' OR " +
            "version.permissionsSdk23 LIKE '%\"$ROOT_PERMISSION\"%'))"
        val keywordMatch = "((${clause(ROOT_KEYWORDS, "LIKE", " OR ")}) AND " +
            "(${clause(ROOT_NEGATIONS, "NOT LIKE", " AND ")}))"
        val query = SimpleSQLiteQuery(
            """
            SELECT
                app.id AS appId,
                app.packageName AS packageName,
                COALESCE(n_loc.name, n_en.name) AS name,
                COALESCE(s_loc.summary, s_en.summary) AS summary,
                repo.address AS baseAddress,
                COALESCE(i_loc.icon_name, i_en.icon_name) AS iconName,
                (
                    SELECT v.versionName FROM version v
                    WHERE v.appId = app.id
                    ORDER BY v.versionCode DESC
                    LIMIT 1
                ) AS suggestedVersion
            FROM app
            JOIN repository AS repo ON app.repoId = repo.id
            LEFT JOIN localized_app_name AS n_loc ON n_loc.appId = app.id AND n_loc.locale = ?
            LEFT JOIN localized_app_name AS n_en ON n_en.appId = app.id AND n_en.locale = 'en-US'
            LEFT JOIN localized_app_summary AS s_loc ON s_loc.appId = app.id AND s_loc.locale = ?
            LEFT JOIN localized_app_summary AS s_en ON s_en.appId = app.id AND s_en.locale = 'en-US'
            LEFT JOIN localized_app_icon AS i_loc ON i_loc.appId = app.id AND i_loc.locale = ?
            LEFT JOIN localized_app_icon AS i_en ON i_en.appId = app.id AND i_en.locale = 'en-US'
            LEFT JOIN localized_app_description AS d_loc ON d_loc.appId = app.id AND d_loc.locale = ?
            LEFT JOIN localized_app_description AS d_en ON d_en.appId = app.id AND d_en.locale = 'en-US'
            WHERE ($permMatch OR $keywordMatch)
            GROUP BY app.packageName
            ORDER BY app.lastUpdated DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf<Any>(locale, locale, locale, locale, limit),
        )
        return _rawQueryAppMinimal(query).map {
            AppMinimal(
                appId = it.appId.toLong(),
                packageName = PackageName(it.packageName),
                name = it.name,
                summary = it.summary,
                icon = FilePath(it.baseAddress, it.iconName),
                suggestedVersion = it.suggestedVersion ?: "",
            )
        }
    }

    /** Emits on any change to the download-stats table (e.g. after the stats worker inserts a monthly
     *  file), so the "Most downloaded" carousel re-queries once data lands. Value isn't meaningful. */
    @Query("SELECT COUNT(*) FROM download_stats")
    fun downloadStatsCountStream(): Flow<Int>

    private fun searchQueryMinimal(
        sortOrder: SortOrder,
        searchQuery: String?,
        repoId: Int?,
        categoriesToInclude: List<DefaultName>?,
        categoriesToExclude: List<DefaultName>?,
        antiFeaturesToInclude: List<Tag>?,
        antiFeaturesToExclude: List<Tag>?,
        featuresToInclude: List<String>?,
        permissionsToInclude: List<String>?,
        updatedOnly: Boolean,
        locale: String,
    ): SimpleSQLiteQuery {
        logQuery(
            "sortOrder" to sortOrder,
            "searchQuery" to searchQuery,
            "repoId" to repoId,
            "categoriesToInclude" to categoriesToInclude,
            "categoriesToExclude" to categoriesToExclude,
            "antiFeaturesToInclude" to antiFeaturesToInclude,
            "antiFeaturesToExclude" to antiFeaturesToExclude,
            "featuresToInclude" to featuresToInclude,
            "permissionsToInclude" to permissionsToInclude,
            "locale" to locale,
        )
        val args = arrayListOf<Any?>()

        val query = buildString(2048) {
            append(
                """
                SELECT
                    app.id AS appId,
                    app.packageName AS packageName,
                    COALESCE(n_loc.name, n_en.name) AS name,
                    COALESCE(s_loc.summary, s_en.summary) AS summary,
                    repo.address AS baseAddress,
                    COALESCE(i_loc.icon_name, i_en.icon_name) AS iconName,
                    (
                        SELECT v.versionName FROM version v
                        WHERE v.appId = app.id
                        ORDER BY v.versionCode DESC
                        LIMIT 1
                    ) AS suggestedVersion
                FROM app
                JOIN repository AS repo ON app.repoId = repo.id
                LEFT JOIN localized_app_name AS n_loc ON n_loc.appId = app.id AND n_loc.locale = ?
                LEFT JOIN localized_app_name AS n_en ON n_en.appId = app.id AND n_en.locale = 'en-US'
                LEFT JOIN localized_app_summary AS s_loc ON s_loc.appId = app.id AND s_loc.locale = ?
                LEFT JOIN localized_app_summary AS s_en ON s_en.appId = app.id AND s_en.locale = 'en-US'
                LEFT JOIN localized_app_icon AS i_loc ON i_loc.appId = app.id AND i_loc.locale = ?
                LEFT JOIN localized_app_icon AS i_en ON i_en.appId = app.id AND i_en.locale = 'en-US'
                LEFT JOIN localized_app_description AS d_loc ON d_loc.appId = app.id AND d_loc.locale = ?
                LEFT JOIN localized_app_description AS d_en ON d_en.appId = app.id AND d_en.locale = 'en-US'
                """.trimIndent(),
            )
            // locale args repeated for each localized table
            args.add(locale)
            args.add(locale)
            args.add(locale)
            args.add(locale)

            if (sortOrder == SortOrder.SIZE) {
                append(" LEFT JOIN version ON app.id = version.appId")
            }
            if (categoriesToInclude != null || categoriesToExclude != null) {
                append(" LEFT JOIN category_app_relation ON app.id = category_app_relation.id")
            }
            if (antiFeaturesToExclude != null || antiFeaturesToInclude != null) {
                append(" LEFT JOIN anti_features_app_relation ON app.id = anti_features_app_relation.appId")
            }

            append(" WHERE 1")

            if (repoId != null) {
                append(" AND app.repoId = ?")
                args.add(repoId)
            }

            // "Recently updated" only: keep apps that have actually been updated at least once since
            // they were first added. A brand-new app has lastUpdated == added, so without this it would
            // top both the "new apps" and "recently updated" carousels identically — the same list twice.
            if (updatedOnly) {
                append(" AND app.lastUpdated > app.added")
            }

            if (categoriesToInclude != null) {
                append(" AND category_app_relation.defaultName IN (")
                append(categoriesToInclude.joinToString(", ") { "?" })
                append(")")
                args.addAll(categoriesToInclude)
            }

            if (categoriesToExclude != null) {
                append(" AND category_app_relation.defaultName NOT IN (")
                append(categoriesToExclude.joinToString(", ") { "?" })
                append(")")
                args.addAll(categoriesToExclude)
            }

            if (antiFeaturesToInclude != null) {
                append(" AND anti_features_app_relation.tag IN (")
                append(antiFeaturesToInclude.joinToString(", ") { "?" })
                append(")")
                args.addAll(antiFeaturesToInclude)
            }

            if (antiFeaturesToExclude != null) {
                append(" AND anti_features_app_relation.tag NOT IN (")
                append(antiFeaturesToExclude.joinToString(", ") { "?" })
                append(")")
                args.addAll(antiFeaturesToExclude)
            }

            // Keep only apps that declare each required <uses-feature> on at least one of their
            // versions. `features` is a JSON list (e.g. ["android.software.leanback"]); matching the
            // quoted name avoids a prefix matching a longer feature. EXISTS (rather than a JOIN) keeps
            // one row per app and doesn't disturb the GROUP BY below.
            featuresToInclude?.forEach { feature ->
                append(
                    " AND EXISTS (SELECT 1 FROM version WHERE version.appId = app.id" +
                        " AND version.features LIKE ?)",
                )
                args.add("%\"$feature\"%")
            }

            // Same idea for required <uses-permission> declarations. Permissions are stored as a JSON
            // list of objects with a "name" field, in two columns (normal + sdk23 runtime), so match the
            // quoted permission name in either. Used for the "Shizuku" section.
            permissionsToInclude?.forEach { permission ->
                append(
                    " AND EXISTS (SELECT 1 FROM version WHERE version.appId = app.id" +
                        " AND (version.permissions LIKE ? OR version.permissionsSdk23 LIKE ?))",
                )
                args.add("%\"$permission\"%")
                args.add("%\"$permission\"%")
            }

            if (searchQuery != null) {
                val searchPattern = "%$searchQuery%"
                append(
                    """
                     AND (
                        app.packageName LIKE ?
                        OR COALESCE(n_loc.name, n_en.name) LIKE ?
                        OR COALESCE(s_loc.summary, s_en.summary) LIKE ?
                        OR COALESCE(d_loc.description, d_en.description) LIKE ?
                    )
                    """.trimIndent(),
                )
                args.addAll(listOf(searchPattern, searchPattern, searchPattern, searchPattern))
            }
            append(" GROUP BY app.packageName")

            append(" ORDER BY ")

            if (searchQuery != null) {
                val searchPattern = "%$searchQuery%"
                append("(CASE WHEN COALESCE(n_loc.name, n_en.name) LIKE ? THEN 4 ELSE 0 END) + ")
                append("(CASE WHEN COALESCE(s_loc.summary, s_en.summary) LIKE ? THEN 3 ELSE 0 END) + ")
                append("(CASE WHEN app.packageName LIKE ? THEN 2 ELSE 0 END) + ")
                append("(CASE WHEN COALESCE(d_loc.description, d_en.description) LIKE ? THEN 1 ELSE 0 END) DESC, ")
                args.addAll(listOf(searchPattern, searchPattern, searchPattern, searchPattern))
            }

            when (sortOrder) {
                SortOrder.UPDATED -> append("app.lastUpdated DESC")
                SortOrder.ADDED -> append("app.added DESC")
                SortOrder.SIZE -> append("version.apk_size DESC")
                // Order alphabetically by the (localised) name, case-insensitively. Appending
                // nothing here left a dangling "ORDER BY" and crashed the query (SQLITE_ERROR).
                SortOrder.NAME -> append("COALESCE(n_loc.name, n_en.name) COLLATE NOCASE")
            }
        }

        return SimpleSQLiteQuery(query, args.toTypedArray())
    }

    @Query(
        """
        SELECT app.*
        FROM app
        LEFT JOIN installed
        ON app.packageName = installed.packageName
        LEFT JOIN version
        ON version.appId = app.id
        WHERE installed.packageName IS NOT NULL
        ORDER BY
        CASE WHEN version.versionCode > installed.versionCode THEN 1 ELSE 2 END,
        app.lastUpdated DESC
        """,
    )
    fun installedStream(): Flow<List<AppEntity>>

    // Locales the app is translated into: any locale with a localized name, summary or description.
    @Query(
        """
        SELECT DISTINCT locale FROM (
            SELECT appId, locale FROM localized_app_name
            UNION SELECT appId, locale FROM localized_app_summary
            UNION SELECT appId, locale FROM localized_app_description
        ) WHERE appId = :appId
        """,
    )
    suspend fun appLocales(appId: Int): List<String>

    @Query("SELECT versionCode FROM version WHERE appId = :appId ORDER BY versionCode DESC LIMIT 1")
    suspend fun suggestedVersionCode(appId: Int): Long

    @Query("SELECT versionName FROM version WHERE appId = :appId ORDER BY versionCode DESC LIMIT 1")
    suspend fun suggestedVersionName(appId: Int): String

    // Batch fetch suggested (max versionCode) versionName for multiple appIds
    @MapInfo(keyColumn = "appId", valueColumn = "versionName")
    @Query(
        """
        SELECT v.appId AS appId, MAX(v.versionName) AS versionName
        FROM version v
        GROUP BY appId
        """,
    )
    suspend fun suggestedVersionNamesAll(): Map<Int, String>

    data class AppVersionCodeRow(
        val appId: Int,
        val versionCode: Long,
        // Signing-certificate fingerprint(s) of that exact version (lowercase hex SHA-256), in the
        // same format as the installed app's stored signature so the two can be compared directly.
        val signer: List<String>,
    )

    @RawQuery
    suspend fun _rawDeviceCompatibleVersionCodes(
        query: SimpleSQLiteQuery,
    ): List<AppVersionCodeRow>

    /**
     * Highest *installable-on-this-device* version per app, with the signing-certificate
     * fingerprint(s) of that exact version — used to detect updates and to tell whether an update can
     * actually replace the installed app. Mirrors [com.looker.droidify.data.model.selectForDevice] /
     * isInstallableOnDevice: a version counts only when the running [sdk] is at least its minSdk and,
     * if it ships native code, it targets one of the device's [abis] (no native code = universal).
     * versionCodes are unique per app, so MAX(compatible) is exactly the version selectForDevice
     * would install.
     *
     * Multi-ABI apps (e.g. RustDesk) publish one APK per ABI under *different* versionCodes, so a
     * device-blind MAX picks another architecture's higher code and wrongly flags an update that the
     * detail screen then refuses to install.
     *
     * The bare `signer` column is read from the same row the `MAX(versionCode)` came from — SQLite's
     * documented behaviour for a query with a single MAX() aggregate — so the fingerprint always
     * belongs to the selected version, not some other row in the group.
     */
    suspend fun deviceCompatibleVersions(sdk: Int, abis: List<String>): List<AppVersionCodeRow> {
        // nativeCode is a JSON string list, e.g. ["arm64-v8a"] or []. "No native code" = it contains
        // no quoted element. We use GLOB (not LIKE) so the only wildcard is '*': '_' and '-' are
        // literals, so "x86_64" / "armeabi-v7a" match exactly and "x86" can't match "x86_64". The
        // ABIs are bound as args, and GLOB is case-sensitive — both index and Build.SUPPORTED_ABIS
        // use canonical lowercase names.
        val abiMatch = abis.joinToString(" OR ") { "nativeCode GLOB ('*\"' || ? || '\"*')" }
        val compatible = if (abis.isEmpty()) {
            "nativeCode NOT GLOB '*\"*'"
        } else {
            "(nativeCode NOT GLOB '*\"*' OR $abiMatch)"
        }
        val query = SimpleSQLiteQuery(
            "SELECT appId, MAX(versionCode) AS versionCode, signer FROM version " +
                "WHERE minSdkVersion <= ? AND $compatible GROUP BY appId",
            (listOf<Any>(sdk) + abis).toTypedArray(),
        )
        return _rawDeviceCompatibleVersionCodes(query)
    }

    @Transaction
    @Query("SELECT * FROM app WHERE packageName = :packageName")
    fun queryAppEntity(packageName: String): Flow<List<AppEntityRelations>>

    @Query("SELECT COUNT(*) FROM app")
    suspend fun count(): Int

    /**
     * Emits on any change to the app or version tables (e.g. after a sync). Used to trigger
     * reactive re-queries of the catalogue; the numeric value itself is not meaningful.
     */
    @Query("SELECT (SELECT COUNT(*) FROM app) + (SELECT COUNT(*) FROM version)")
    fun catalogSizeStream(): Flow<Int>

    @Query("DELETE FROM app WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM app WHERE repoId = :repoId")
    suspend fun deleteByRepoId(repoId: Int)

    @Query("SELECT name FROM localized_app_name WHERE appId = :id AND (locale = :locale OR locale = \'en-US\')")
    suspend fun name(id: Int, locale: String): String?

    @Query("SELECT summary FROM localized_app_summary WHERE appId = :id AND (locale = :locale OR locale = \'en-US\')")
    suspend fun summary(id: Int, locale: String): String?

    @Query(
        "SELECT description FROM localized_app_description WHERE appId = :id AND (locale = :locale OR locale = \'en-US\')",
    )
    suspend fun description(id: Int, locale: String): String?

    @Query("SELECT * FROM localized_app_icon WHERE appId = :id AND (locale = :locale OR locale = \'en-US\')")
    suspend fun icon(id: Int, locale: String): LocalizedAppIconEntity?
}

/** The legacy superuser <uses-permission> some root apps still declare (see [AppDao.rootApps]). */
private const val ROOT_PERMISSION = "android.permission.ACCESS_SUPERUSER"

/** Strong "this app uses root" phrasings matched (case-insensitively) in an app's text. Kept specific
 *  enough that a bare "root" (square root, root directory, root CA…) doesn't leak in. */
private val ROOT_KEYWORDS = listOf(
    "magisk", "kernelsu", "superuser", "supersu",
    "root access", "root permission", "root privilege", "root required", "requires root",
    "require root", "needs root", "need root", "rooted device", "rooted phone", "root your",
)

/** Negations that flip a keyword match off, so "works without root" / "no root required" apps aren't
 *  wrongly pulled in. Checked with NOT LIKE against the same text. */
private val ROOT_NEGATIONS = listOf(
    "no root", "without root", "non-root", "nonroot", "rootless", "root-free", "root free",
    "not require root", "not need root", "root not required", "no need for root",
)
