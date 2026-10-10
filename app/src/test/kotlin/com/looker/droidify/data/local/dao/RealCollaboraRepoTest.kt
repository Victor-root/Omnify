package com.looker.droidify.data.local.dao

import com.looker.droidify.assets
import com.looker.droidify.data.local.BaseDatabaseTest
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.sync.JsonParser
import com.looker.droidify.sync.v2.model.IndexV2
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The real index of Collabora Office's F-Droid repository (https://www.collaboraoffice.com/downloads/fdroid/repo),
 * as reported in issue #4: 24 APKs in 6 (package, versionCode) groups, each group carrying an APK per
 * architecture (arm64-v8a, armeabi-v7a, x86, x86_64, and for the snapshot a universal one too).
 */
class RealCollaboraRepoTest : BaseDatabaseTest() {

    private lateinit var indexDao: IndexDao
    private lateinit var appDao: AppDao

    override fun initDao() {
        indexDao = database.indexDao()
        appDao = database.appDao()
    }

    private val device32Bit = listOf("armeabi-v7a", "armeabi")
    private val arm64Phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private suspend fun syncCollabora() {
        val text = assets("test-data/collabora_index_v2.json")!!.readText()
        indexDao.insertIndex(Fingerprint("A".repeat(64)), JsonParser.decodeFromString<IndexV2>(text))
    }

    private suspend fun latestVersionCodes(abis: List<String>): Map<String, Long> =
        appDao.deviceCompatibleVersions(sdk = 31, abis = abis).associate { it.packageName to it.versionCode }

    @Test
    fun everyApkOfTheRealIndexIsStored() = runDbTest {
        syncCollabora()

        val cursor = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM version")
        assertEquals(24, cursor.use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun anArmeabiV7aBoxGetsTheLatestReleaseOfBothApps() = runDbTest {
        syncCollabora()

        assertEquals(
            mapOf("com.collabora.libreoffice" to 155L, "com.collabora.libreoffice.snapshot" to 940L),
            latestVersionCodes(device32Bit),
        )
    }

    @Test
    fun anArm64PhoneGetsTheLatestReleaseOfBothApps() = runDbTest {
        syncCollabora()

        assertEquals(
            mapOf("com.collabora.libreoffice" to 155L, "com.collabora.libreoffice.snapshot" to 940L),
            latestVersionCodes(arm64Phone),
        )
    }
}
