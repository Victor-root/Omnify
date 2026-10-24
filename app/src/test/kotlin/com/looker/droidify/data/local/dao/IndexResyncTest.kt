package com.looker.droidify.data.local.dao

import com.looker.droidify.assets
import com.looker.droidify.data.local.BaseDatabaseTest
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.sync.JsonParser
import com.looker.droidify.sync.v2.model.IndexV2
import org.junit.Assert.assertEquals
import org.junit.Test

/** A repository synced again: its catalogue becomes what the new index lists, nothing left behind. */
class IndexResyncTest : BaseDatabaseTest() {

    private lateinit var indexDao: IndexDao
    private lateinit var appDao: AppDao

    override fun initDao() {
        indexDao = database.indexDao()
        appDao = database.appDao()
    }

    private val fingerprint = Fingerprint("A".repeat(64))

    private fun collabora(): IndexV2 =
        JsonParser.decodeFromString<IndexV2>(assets("test-data/collabora_index_v2.json")!!.readText())

    private fun strings(sql: String): List<String> = database.openHelper.readableDatabase.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    @Test
    fun whatTheRepositoryNoLongerListsGoes() = runDbTest {
        val index = collabora()
        indexDao.insertIndex(fingerprint, index)
        val repoId = strings("SELECT id FROM repository").single().toInt()
        val app = index.packages.getValue("com.collabora.libreoffice")

        indexDao.insertIndex(
            fingerprint,
            index.copy(
                packages = mapOf(
                    "com.collabora.libreoffice" to app.copy(metadata = app.metadata.copy(categories = listOf("Office"))),
                ),
            ),
            expectedRepoId = repoId,
        )

        assertEquals(listOf("com.collabora.libreoffice"), strings("SELECT packageName FROM app"))
        assertEquals(
            mapOf("com.collabora.libreoffice" to listOf("Office")),
            appDao.packageCategories().groupBy({ it.packageName }, { it.defaultName }),
        )
        assertEquals(
            app.versions.size,
            strings("SELECT COUNT(*) FROM version").single().toInt(),
        )
    }
}
