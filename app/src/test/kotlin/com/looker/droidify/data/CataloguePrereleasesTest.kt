package com.looker.droidify.data

import com.looker.droidify.data.model.ApkFile
import com.looker.droidify.data.model.Manifest
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Platforms
import com.looker.droidify.data.model.SDKs
import com.looker.droidify.external.PrereleaseMode
import com.looker.droidify.network.DataSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CataloguePrereleasesTest {

    private fun build(versionCode: Long, isPrerelease: Boolean) = Package(
        id = versionCode,
        installed = false,
        added = 0L,
        apk = ApkFile(name = "app_$versionCode.apk", hash = "h$versionCode", size = DataSize(1L)),
        platforms = Platforms(emptyList()),
        features = emptyList(),
        isPrerelease = isPrerelease,
        antiFeatures = emptyList(),
        manifest = Manifest(
            versionCode = versionCode,
            versionName = "$versionCode",
            usesSDKs = SDKs(),
            signer = emptySet(),
            permissions = emptyList(),
        ),
        whatsNew = "",
    ) to "repo"

    private val beta = build(30, isPrerelease = true)
    private val stable = build(20, isPrerelease = false)
    private val older = build(10, isPrerelease = false)
    private val mixed = listOf(beta, stable, older)

    private fun List<Pair<Package, String>>.codes() = map { it.first.manifest.versionCode }

    @Test
    fun eachModeListsAndOffersWhatItSays() {
        assertEquals(listOf(20L, 10L), mixed.listedUnder(PrereleaseMode.HIDDEN).codes())
        assertEquals(listOf(20L, 10L), mixed.offeredUnder(PrereleaseMode.HIDDEN).codes())
        assertEquals(listOf(30L, 20L, 10L), mixed.listedUnder(PrereleaseMode.SHOWN).codes())
        assertEquals(listOf(20L, 10L), mixed.offeredUnder(PrereleaseMode.SHOWN).codes())
        assertEquals(listOf(30L, 20L, 10L), mixed.offeredUnder(PrereleaseMode.BOTH).codes())
        assertEquals(listOf(30L), mixed.offeredUnder(PrereleaseMode.ONLY).codes())
    }

    @Test
    fun anAppPublishingOneKindOnlyHasItAllWhateverTheChoice() {
        val betasOnly = listOf(beta, build(25, isPrerelease = true))
        val stablesOnly = listOf(stable, older)
        for (mode in PrereleaseMode.entries) {
            assertEquals("$mode", betasOnly, betasOnly.listedUnder(mode))
            assertEquals("$mode", betasOnly, betasOnly.offeredUnder(mode))
            assertEquals("$mode", stablesOnly, stablesOnly.offeredUnder(mode))
        }
    }
}
