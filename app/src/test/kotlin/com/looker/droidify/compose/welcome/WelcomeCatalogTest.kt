package com.looker.droidify.compose.welcome

import com.looker.droidify.external.ExternalApp
import com.looker.droidify.model.Repository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the welcome page offers, checked against what Omnify actually ships. An address that matched no
 *  default repository would silently never show up in the list. */
class WelcomeCatalogTest {

    private val described = WelcomeCatalog.describedRepositories.map { it.address }

    @Test
    fun `every described repository is one Omnify ships`() {
        assertTrue(described.isNotEmpty())
        described.forEach {
            assertTrue(it in Repository.defaultAddresses, "$it is not one of the default repositories")
        }
    }

    @Test
    fun `a repository is described once`() {
        assertEquals(described.distinct(), described)
    }

    @Test
    fun `every repository Omnify ships is offered once, the described ones first`() {
        val order = WelcomeCatalog.repositoryOrder
        assertEquals(order.distinct(), order)
        assertEquals(Repository.defaultAddresses, order.toSet())
        assertEquals(described, order.take(described.size))
    }

    @Test
    fun `the others keep the order Omnify lists them in`() {
        val others = WelcomeCatalog.repositoryOrder.drop(described.size)
        assertEquals(Repository.defaultAddresses.filter { it !in described }, others)
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
