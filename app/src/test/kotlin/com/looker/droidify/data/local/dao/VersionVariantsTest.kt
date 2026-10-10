package com.looker.droidify.data.local.dao

import com.looker.droidify.data.local.BaseDatabaseTest
import com.looker.droidify.data.local.MIGRATION_5_6
import com.looker.droidify.data.local.model.AppEntity
import com.looker.droidify.data.local.model.AuthorEntity
import com.looker.droidify.data.local.model.RepoEntity
import com.looker.droidify.data.local.model.VersionEntity
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.sync.v2.model.ApkFileV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One release published as several APKs under the same versionCode, one per architecture. */
class VersionVariantsTest : BaseDatabaseTest() {

    private lateinit var indexDao: IndexDao
    private lateinit var appDao: AppDao
    private var repoId = 0

    override fun initDao() {
        indexDao = database.indexDao()
        appDao = database.appDao()
    }

    private suspend fun insertApp(): Int {
        repoId = indexDao.upsertRepo(
            RepoEntity(
                address = "https://example.org/repo",
                webBaseUrl = null,
                fingerprint = Fingerprint("ABC123"),
                timestamp = 1000L,
            ),
        )
        val authorId = indexDao.upsertAuthor(AuthorEntity(email = null, name = "Author", website = null))
        return indexDao.insertApp(
            AppEntity(
                added = 0L,
                lastUpdated = 0L,
                license = null,
                preferredSigner = null,
                packageName = "com.example.app",
                authorId = authorId,
                repoId = repoId,
            ),
        ).toInt()
    }

    private fun version(
        appId: Int,
        versionCode: Long,
        apkName: String,
        nativeCode: List<String>,
    ) = VersionEntity(
        added = 0L,
        whatsNew = emptyMap(),
        versionName = "1.0.$versionCode",
        versionCode = versionCode,
        maxSdkVersion = null,
        minSdkVersion = 21,
        targetSdkVersion = 33,
        apk = ApkFileV2(name = apkName, sha256 = "sha-$apkName", size = 1L),
        src = null,
        features = emptyList(),
        nativeCode = nativeCode,
        signer = emptyList(),
        permissions = emptyList(),
        permissionsSdk23 = emptyList(),
        appId = appId,
    )

    private fun storedApkNames(appId: Int): List<String> {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT apk_name FROM version WHERE appId = $appId ORDER BY apk_name",
        )
        return cursor.use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
    }

    private val armeabiV7a = listOf("armeabi-v7a")
    private val arm64 = listOf("arm64-v8a")
    private val device32Bit = listOf("armeabi-v7a", "armeabi")

    @Test
    fun everyApkOfASharedVersionCodeIsKept() = runDbTest {
        val appId = insertApp()
        indexDao.insertVersions(
            listOf(
                version(appId, 10, "/app_arm64.apk", arm64),
                version(appId, 10, "/app_v7a.apk", armeabiV7a),
            ),
        )

        assertEquals(listOf("/app_arm64.apk", "/app_v7a.apk"), storedApkNames(appId))
    }

    @Test
    fun the32BitApkIsFoundWhicheverOrderTheIndexListsThem() = runDbTest {
        val appId = insertApp()
        val v7a = version(appId, 10, "/app_v7a.apk", armeabiV7a)
        val arm = version(appId, 10, "/app_arm64.apk", arm64)

        // Listed last, the 64-bit APK used to replace the 32-bit one, leaving nothing this device runs.
        indexDao.insertVersions(listOf(v7a, arm))
        assertEquals(10L, appDao.deviceCompatibleVersions(sdk = 31, abis = device32Bit).single().versionCode)

        indexDao.insertVersions(listOf(arm, v7a))
        assertEquals(10L, appDao.deviceCompatibleVersions(sdk = 31, abis = device32Bit).single().versionCode)
    }

    @Test
    fun a64BitOnlyReleaseIsStillNotOfferedOn32BitDevices() = runDbTest {
        val appId = insertApp()
        indexDao.insertVersions(listOf(version(appId, 10, "/app_arm64.apk", arm64)))

        assertEquals(emptyList<AppDao.AppVersionCodeRow>(), appDao.deviceCompatibleVersions(31, device32Bit))
    }

    @Test
    fun syncingTheSameIndexAgainDoesNotDuplicateVariants() = runDbTest {
        val appId = insertApp()
        val variants = listOf(
            version(appId, 10, "/app_arm64.apk", arm64),
            version(appId, 10, "/app_v7a.apk", armeabiV7a),
        )

        indexDao.insertVersions(variants)
        indexDao.insertVersions(variants)

        assertEquals(2, storedApkNames(appId).size)
    }

    @Test
    fun separateVersionCodesPerArchitectureStillPickTheNewestCompatibleOne() = runDbTest {
        val appId = insertApp()
        indexDao.insertVersions(
            listOf(
                version(appId, 11, "/app_v7a_11.apk", armeabiV7a),
                version(appId, 12, "/app_arm64_12.apk", arm64),
            ),
        )

        assertEquals(11L, appDao.deviceCompatibleVersions(31, device32Bit).single().versionCode)
        assertEquals(12L, appDao.deviceCompatibleVersions(31, listOf("arm64-v8a")).single().versionCode)
    }

    @Test
    fun migrationReplacesTheUniqueIndexAndForcesAFullResync() = runDbTest {
        val appId = insertApp()
        val db = database.openHelper.writableDatabase
        db.execSQL("DROP INDEX `index_version_appId_versionCode_apk_name`")
        db.execSQL("CREATE UNIQUE INDEX `index_version_appId_versionCode` ON `version` (`appId`, `versionCode`)")

        assertEquals(1000L, database.repoDao().getRepo(repoId)?.timestamp)

        MIGRATION_5_6.migrate(db)

        indexDao.insertVersions(
            listOf(
                version(appId, 10, "/app_arm64.apk", arm64),
                version(appId, 10, "/app_v7a.apk", armeabiV7a),
            ),
        )
        assertEquals(2, storedApkNames(appId).size)
        assertNull(database.repoDao().getRepo(repoId)?.timestamp)
    }
}
