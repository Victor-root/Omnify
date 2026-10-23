package com.looker.droidify.data.local.dao

import com.looker.droidify.data.local.BaseDatabaseTest
import com.looker.droidify.data.local.model.AppEntity
import com.looker.droidify.data.local.model.AuthorEntity
import com.looker.droidify.data.local.model.CategoryAppRelation
import com.looker.droidify.data.local.model.GraphicEntity
import com.looker.droidify.data.local.model.RepoEntity
import com.looker.droidify.data.local.model.TV_BANNER
import com.looker.droidify.data.local.model.VersionEntity
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.data.model.MadeForTv
import com.looker.droidify.datastore.model.SortOrder
import com.looker.droidify.sync.v2.model.ApkFileV2
import org.junit.Assert.assertEquals
import org.junit.Test

/** The reads the TV home is built from. */
class TvCatalogueTest : BaseDatabaseTest() {

    private lateinit var indexDao: IndexDao
    private lateinit var appDao: AppDao
    private val repoIds = mutableMapOf<String, Int>()
    private var authorId: Int? = null

    override fun initDao() {
        indexDao = database.indexDao()
        appDao = database.appDao()
    }

    private suspend fun insertApp(
        packageName: String,
        features: List<String> = emptyList(),
        address: String = "https://example.org/repo",
    ): Int {
        val repoId = repoIds.getOrPut(address) {
            indexDao.upsertRepo(
                RepoEntity(
                    address = address,
                    webBaseUrl = null,
                    fingerprint = Fingerprint("ABC123"),
                    timestamp = 1000L,
                ),
            )
        }
        val author = authorId
            ?: indexDao.upsertAuthor(AuthorEntity(email = null, name = "Author", website = null)).also { authorId = it }
        val appId = indexDao.insertApp(
            AppEntity(
                added = 0L,
                lastUpdated = 0L,
                license = null,
                preferredSigner = null,
                packageName = packageName,
                authorId = author,
                repoId = repoId,
            ),
        ).toInt()
        indexDao.insertVersions(
            listOf(
                VersionEntity(
                    added = 0L,
                    whatsNew = emptyMap(),
                    versionName = "1.0",
                    versionCode = 1,
                    maxSdkVersion = null,
                    minSdkVersion = 21,
                    targetSdkVersion = 33,
                    apk = ApkFileV2(name = "/$packageName.apk", sha256 = "sha-$packageName", size = 1L),
                    src = null,
                    features = features,
                    nativeCode = emptyList(),
                    signer = emptyList(),
                    permissions = emptyList(),
                    permissionsSdk23 = emptyList(),
                    appId = appId,
                ),
            ),
        )
        return appId
    }

    private suspend fun madeForTv(launchable: Set<String> = emptySet()): Set<String> = appDao.query(
        sortOrder = SortOrder.UPDATED,
        madeForTv = MadeForTv(launchable),
        locale = "en-US",
    ).mapTo(HashSet()) { it.packageName.name }

    /** An app made for phones and TV alike says nothing of TV in its features, yet is still one for TV. */
    @Test
    fun eachSignalOfTvCounts() = runDbTest {
        insertApp("com.example.leanback", features = listOf("android.software.leanback"))
        val banner = insertApp("com.example.banner")
        indexDao.insertGraphics(listOf(GraphicEntity(TV_BANNER, "/tv.png", "en-US", banner)))
        insertApp("com.example.launchable")
        insertApp("com.example.phone", features = listOf("android.hardware.touchscreen"))

        assertEquals(
            setOf("com.example.leanback", "com.example.banner"),
            madeForTv(),
        )
        assertEquals(
            setOf("com.example.leanback", "com.example.banner", "com.example.launchable"),
            madeForTv(launchable = setOf("com.example.launchable", "org.example.notInCatalogue")),
        )
    }

    @Test
    fun aFeatureNamedAfterLeanbackIsNotIt() = runDbTest {
        insertApp("com.example.other", features = listOf("android.software.leanback_only"))

        assertEquals(emptySet<String>(), madeForTv())
    }

    @Test
    fun categoriesAreReadByPackageAcrossRepositories() = runDbTest {
        val player = insertApp("com.example.player")
        val other = insertApp("com.example.other")
        val playerElsewhere = insertApp("com.example.player", address = "https://example.org/other")
        indexDao.insertCategoryAppRelation(
            listOf(
                CategoryAppRelation(player, "Local Media Player"),
                CategoryAppRelation(playerElsewhere, "Local Media Player"),
                CategoryAppRelation(playerElsewhere, "Cast"),
                CategoryAppRelation(other, "Games"),
            ),
        )

        val categories = appDao.packageCategories().groupBy({ it.packageName }, { it.defaultName })

        assertEquals(setOf("Local Media Player", "Cast"), categories.getValue("com.example.player").toSet())
        assertEquals(2, categories.getValue("com.example.player").size)
        assertEquals(listOf("Games"), categories.getValue("com.example.other"))
    }
}
