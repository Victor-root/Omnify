package com.looker.droidify.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.looker.droidify.assets
import com.looker.droidify.data.local.BaseDatabaseTest
import com.looker.droidify.data.local.dao.AppDao
import com.looker.droidify.data.local.dao.IndexDao
import com.looker.droidify.data.local.dao.RepoDao
import com.looker.droidify.data.local.model.RepoEntity
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.datastore.PreferenceSettingsRepository
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.sync.JsonParser
import com.looker.droidify.sync.v2.model.IndexV2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A disabled repository must have no apps in the catalogue, whatever order its sync and its switch
 * land in. Every catalogue screen (Explore, All apps, Search, Favourites) reads [AppDao.query], so
 * that is what the checks below look at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepoCatalogueTest : BaseDatabaseTest() {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var indexDao: IndexDao
    private lateinit var appDao: AppDao
    private lateinit var repoDao: RepoDao
    private lateinit var settings: PreferenceSettingsRepository
    private lateinit var catalogue: RepoCatalogue

    private val index: IndexV2 by lazy {
        JsonParser.decodeFromString<IndexV2>(assets("test-data/collabora_index_v2.json")!!.readText())
    }
    private val fingerprint = Fingerprint("A".repeat(64))
    private val packages = listOf("com.collabora.libreoffice", "com.collabora.libreoffice.snapshot")

    override fun initDao() {
        indexDao = database.indexDao()
        appDao = database.appDao()
        repoDao = database.repoDao()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmpFolder.newFolder(), "settings.preferences_pb") },
        )
        settings = PreferenceSettingsRepository(dataStore)
        catalogue = RepoCatalogue(
            context = ApplicationProvider.getApplicationContext<Context>(),
            settingsRepository = settings,
            repoDao = repoDao,
            appDao = appDao,
            indexDao = indexDao,
        )
    }

    private suspend fun addRepo(address: String): Int = indexDao.upsertRepo(
        RepoEntity(address = address, webBaseUrl = address, fingerprint = fingerprint, timestamp = null),
    )

    /** What RepoRepository.enableRepository does when switching a repository off. */
    private suspend fun disable(repoId: Int) {
        settings.setRepoEnabled(repoId, false)
        catalogue.clear(repoId)
    }

    private suspend fun catalogueApps(
        searchQuery: String? = null,
        repoId: Int? = null,
        packageNames: List<String>? = null,
    ): List<String> = appDao.query(
        sortOrder = SortOrder.UPDATED,
        searchQuery = searchQuery,
        repoId = repoId,
        packageNames = packageNames,
        locale = "en-US",
    ).map { it.packageName.name }

    @Test
    fun anEnabledRepositoryIsSaved() = runDbTest {
        val repoId = addRepo("https://one.example/repo")
        settings.setRepoEnabled(repoId, true)

        assertTrue(catalogue.save(repoId, fingerprint, index))

        assertEquals(packages.toSet(), catalogueApps().toSet())
    }

    @Test
    fun anIndexDownloadedWhileTheRepositoryWasDisabledIsDropped() = runDbTest {
        val repoId = addRepo("https://one.example/repo")
        settings.setRepoEnabled(repoId, true)
        disable(repoId)

        assertFalse(catalogue.save(repoId, fingerprint, index))

        assertTrue(catalogueApps().isEmpty())
        assertTrue(catalogueApps(searchQuery = "Collabora").isEmpty())
        assertTrue(catalogueApps(packageNames = packages).isEmpty())
    }

    @Test
    fun disablingAfterTheIndexIsSavedRemovesItsApps() = runDbTest {
        val repoId = addRepo("https://one.example/repo")
        settings.setRepoEnabled(repoId, true)
        catalogue.save(repoId, fingerprint, index)

        disable(repoId)

        assertTrue(catalogueApps().isEmpty())
        assertNull(repoDao.getRepo(repoId)!!.timestamp)
    }

    @Test
    fun aDisabledRepositoryIsNotDownloaded() = runDbTest {
        val repoId = addRepo("https://one.example/repo")
        var ran = false

        assertNull(catalogue.whileEnabled(repoId) { ran = true })
        assertFalse(ran)
    }

    @Test
    fun aDownloadStopsOnceItsRepositoryIsDisabled() = runDbTest {
        val repoId = addRepo("https://one.example/repo")
        settings.setRepoEnabled(repoId, true)
        val started = CompletableDeferred<Unit>()

        coroutineScope {
            val download = async {
                catalogue.whileEnabled<Unit>(repoId) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            settings.setRepoEnabled(repoId, false)

            assertNull(download.await())
        }
    }

    @Test
    fun aRepositoryEnabledAgainBeforeItsCleanupKeepsItsApps() = runDbTest {
        val repoId = addRepo("https://one.example/repo")
        settings.setRepoEnabled(repoId, true)
        catalogue.save(repoId, fingerprint, index)

        settings.setRepoEnabled(repoId, false)
        settings.setRepoEnabled(repoId, true)
        catalogue.clear(repoId)

        assertEquals(packages.toSet(), catalogueApps().toSet())
        assertNotNull(repoDao.getRepo(repoId)!!.timestamp)
    }

    @Test
    fun rapidTogglesEndingDisabledLeaveNoApps() = runDbTest {
        val repoId = addRepo("https://one.example/repo")

        settings.setRepoEnabled(repoId, true)
        disable(repoId)
        settings.setRepoEnabled(repoId, true)
        catalogue.save(repoId, fingerprint, index)
        disable(repoId)
        catalogue.save(repoId, fingerprint, index)

        assertTrue(catalogueApps().isEmpty())
    }

    @Test
    fun anAppFromSeveralRepositoriesStaysThroughTheOthers() = runDbTest {
        val disabled = addRepo("https://one.example/repo")
        val kept = addRepo("https://two.example/repo")
        settings.setRepoEnabled(disabled, true)
        settings.setRepoEnabled(kept, true)
        catalogue.save(disabled, fingerprint, index)
        catalogue.save(kept, fingerprint, index)

        disable(disabled)
        catalogue.save(disabled, fingerprint, index)

        assertTrue(catalogueApps(repoId = disabled).isEmpty())
        assertEquals(packages.toSet(), catalogueApps(repoId = kept).toSet())
        assertTrue("com.collabora.libreoffice" in catalogueApps(searchQuery = "Collabora"))
    }

    @Test
    fun aFavouriteOutlivesItsRepositoryBeingDisabledAndComesBackWithIt() = runDbTest {
        val repoId = addRepo("https://one.example/repo")
        val favourite = packages.first()
        settings.setRepoEnabled(repoId, true)
        catalogue.save(repoId, fingerprint, index)
        settings.toggleFavourites(favourite)

        disable(repoId)

        assertTrue(favourite in settings.getInitial().favouriteApps)
        assertTrue(catalogueApps(packageNames = listOf(favourite)).isEmpty())

        settings.setRepoEnabled(repoId, true)
        catalogue.save(repoId, fingerprint, index)

        assertEquals(listOf(favourite), catalogueApps(packageNames = listOf(favourite)))
    }

    @Test
    fun appsLeftBehindByADisabledRepositoryAreClearedOnly() = runDbTest {
        val ghost = addRepo("https://one.example/repo")
        val enabled = addRepo("https://two.example/repo")
        settings.setRepoEnabled(ghost, true)
        settings.setRepoEnabled(enabled, true)
        catalogue.save(ghost, fingerprint, index)
        catalogue.save(enabled, fingerprint, index)
        // The state an older build could leave: switched off, yet its apps still stored.
        settings.setRepoEnabled(ghost, false)

        catalogue.clearDisabled()

        assertTrue(catalogueApps(repoId = ghost).isEmpty())
        assertNull(repoDao.getRepo(ghost)!!.timestamp)
        assertEquals(packages.toSet(), catalogueApps(repoId = enabled).toSet())
        assertNotNull(repoDao.getRepo(enabled)!!.timestamp)
        assertEquals(setOf(enabled), settings.getEnabledRepoIds().first())
    }
}
