package com.looker.droidify.data.local.dao

import com.looker.droidify.data.local.BaseDatabaseTest
import com.looker.droidify.data.local.model.AppEntity
import com.looker.droidify.data.local.model.AuthorEntity
import com.looker.droidify.data.local.model.RepoEntity
import com.looker.droidify.data.model.Fingerprint
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What tells the home lists to read the catalogue again. */
class CatalogChangesTest : BaseDatabaseTest() {

    private lateinit var indexDao: IndexDao
    private lateinit var appDao: AppDao

    override fun initDao() {
        indexDao = database.indexDao()
        appDao = database.appDao()
    }

    private fun repo(id: Int = 0, timestamp: Long) = RepoEntity(
        id = id,
        address = "https://example.org/repo",
        webBaseUrl = null,
        fingerprint = Fingerprint("ABC123"),
        timestamp = timestamp,
    )

    @Test
    fun `a sync that keeps the same apps and versions still counts as a change`() = runDbTest {
        val repoId = indexDao.upsertRepo(repo(timestamp = 1000L))
        val authorId = indexDao.upsertAuthor(AuthorEntity(email = null, name = "Author", website = null))
        indexDao.insertApp(
            AppEntity(
                added = 0L,
                lastUpdated = 0L,
                license = null,
                preferredSigner = null,
                packageName = "auk.dialer.vroot",
                authorId = authorId,
                repoId = repoId,
            ),
        )
        val before = appDao.catalogChangeStream().first()
        val sizeBefore = appDao.catalogSizeStream().first()

        // A new index renaming the app: same app, same versions, a newer index timestamp.
        indexDao.upsertRepo(repo(id = repoId, timestamp = 2000L))

        assertNotEquals(before, appDao.catalogChangeStream().first())
        assertEquals(sizeBefore, appDao.catalogSizeStream().first())
    }
}
