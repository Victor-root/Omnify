package com.looker.droidify.data.local.dao

import com.looker.droidify.assets
import com.looker.droidify.data.local.BaseDatabaseTest
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.sync.JsonParser
import com.looker.droidify.sync.common.toV2
import com.looker.droidify.sync.v1.model.IndexV1
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The real index of SimpleX Chat's own F-Droid repository (https://app.simplex.chat/fdroid/repo), which
 * publishes an arm64-v8a and an armeabi-v7a APK under every versionCode. Only its newest releases are
 * kept here, exactly as that repository lists them.
 */
class RealRepoMultiAbiTest : BaseDatabaseTest() {

    private lateinit var indexDao: IndexDao
    private lateinit var appDao: AppDao

    override fun initDao() {
        indexDao = database.indexDao()
        appDao = database.appDao()
    }

    private val device32Bit = listOf("armeabi-v7a", "armeabi")
    private val device64Bit = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private suspend fun syncSimplex(arm64ListedLast: Boolean) {
        val text = assets("test-data/simplex_index_v1.json")!!.readText()
        val index = JsonParser.decodeFromString<IndexV1>(text)
        // As published, the 32-bit APK comes second under every versionCode. Reversing the list is what
        // another generator, or a merged update, could produce: the 64-bit APK second.
        val ordered = if (arm64ListedLast) {
            index.copy(packages = index.packages.mapValues { it.value.reversed() })
        } else {
            index
        }
        indexDao.insertIndex(Fingerprint("A".repeat(64)), ordered.toV2())
    }

    private fun nativeCodesOf(versionCode: Long): List<String> {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT nativeCode FROM version WHERE versionCode = $versionCode ORDER BY nativeCode",
        )
        return cursor.use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
    }

    @Test
    fun everyApkOfTheRealIndexIsStored() = runDbTest {
        syncSimplex(arm64ListedLast = false)

        val cursor = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM version")
        assertEquals(10, cursor.use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun eachReleaseKeepsBothArchitectures() = runDbTest {
        syncSimplex(arm64ListedLast = false)

        assertEquals(listOf("[\"arm64-v8a\"]", "[\"armeabi-v7a\"]"), nativeCodesOf(386))
    }

    @Test
    fun a32BitDeviceGetsTheLatestRelease() = runDbTest {
        syncSimplex(arm64ListedLast = false)

        assertEquals(386L, appDao.deviceCompatibleVersions(sdk = 31, abis = device32Bit).single().versionCode)
    }

    @Test
    fun a32BitDeviceGetsTheLatestReleaseWhenTheIndexListsTheArm64ApkLast() = runDbTest {
        syncSimplex(arm64ListedLast = true)

        assertEquals(386L, appDao.deviceCompatibleVersions(sdk = 31, abis = device32Bit).single().versionCode)
    }

    @Test
    fun a64BitDeviceGetsTheLatestReleaseInEitherOrder() = runDbTest {
        syncSimplex(arm64ListedLast = true)

        assertEquals(386L, appDao.deviceCompatibleVersions(sdk = 31, abis = device64Bit).single().versionCode)
    }
}
