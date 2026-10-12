package com.looker.droidify.compose.welcome

import com.looker.droidify.external.ExternalApp
import com.looker.droidify.model.Repository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the welcome page offers, checked against what Omnify actually ships. A recommended address that
 *  matched no default repository would silently never show up in the list. */
class WelcomeCatalogTest {

    private val repositoryIds = WelcomeCatalog.sources
        .map { it.id }
        .filter { it != ExternalApp.OMNIFY_REPO_KEY }

    @Test
    fun `every recommended repository is one Omnify ships`() {
        assertTrue(repositoryIds.isNotEmpty())
        repositoryIds.forEach {
            assertTrue(it in Repository.defaultAddresses, "$it is not one of the default repositories")
        }
    }

    @Test
    fun `a source is listed once`() {
        val ids = WelcomeCatalog.sources.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `the sources ticked at first are the ones a new install always switched on`() {
        val switchedOnBefore = Repository.defaultRepositories
            .filter { it.enabled }
            .map { it.address.trimEnd('/') }
            .toSet() + ExternalApp.OMNIFY_REPO_KEY
        assertEquals(switchedOnBefore, WelcomeCatalog.defaultChecked().toSet())
    }
}
