package com.looker.droidify.data

import com.looker.droidify.external.ExternalApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The source an app's updates come from once the user chose one on its page. Aegis is the real case:
 * F-Droid, IzzyOnDroid and the project's own GitHub releases all offer it, and on 7 September 2026
 * GitHub and IzzyOnDroid had 3.4.3 while F-Droid only published it two days later.
 */
class FollowedSourceTest {

    private val packageName = "com.beemdevelopment.aegis"
    private val fdroid = 1
    private val izzy = 2
    private val github = ExternalApp(owner = "beemdevelopment", repo = "Aegis", packageName = packageName)

    private val byRepo = mapOf(
        fdroid to SuggestedVersion(versionCode = 76, versionName = "3.4.2", signers = setOf("f-droid")),
        izzy to SuggestedVersion(versionCode = 77, versionName = "3.4.3", signers = setOf("developer")),
    )

    @Test
    fun `a choice is stored and read back as the same source`() {
        val choices = listOf(FollowedSource.Repository(fdroid), FollowedSource.External(github.key))
        choices.forEach { assertEquals(it, FollowedSource.parse(it.stored)) }
        assertNull(FollowedSource.parse("something else"))
    }

    @Test
    fun `without a choice the newest build wherever it lives is the one to install`() {
        assertEquals("3.4.3", suggestedVersionOf(byRepo, followed = null)?.versionName)
    }

    @Test
    fun `a followed repository's own build counts even when another has a newer one`() {
        assertEquals("3.4.2", suggestedVersionOf(byRepo, FollowedSource.Repository(fdroid))?.versionName)
    }

    @Test
    fun `a followed repository with nothing for this device offers no update from elsewhere`() {
        assertNull(suggestedVersionOf(byRepo, FollowedSource.Repository(repoId = 9)))
    }

    @Test
    fun `a repository no longer offering the app goes back to the automatic choice`() {
        val stored = FollowedSource.Repository(izzy).stored
        assertEquals(
            FollowedSource.Repository(izzy),
            followedSourceOf(stored, packageName, setOf(fdroid, izzy), emptyList()),
        )
        assertNull(followedSourceOf(stored, packageName, setOf(fdroid), emptyList()))
    }

    @Test
    fun `an external source switched off or removed goes back to the automatic choice`() {
        val stored = FollowedSource.External(github.key).stored
        assertEquals(
            FollowedSource.External(github.key),
            followedSourceOf(stored, packageName, emptySet(), listOf(github)),
        )
        assertNull(followedSourceOf(stored, packageName, emptySet(), listOf(github.copy(enabled = false))))
        assertNull(followedSourceOf(stored, packageName, emptySet(), emptyList()))
    }

    @Test
    fun `an external source of another package is not followed for this one`() {
        val stored = FollowedSource.External(github.key).stored
        assertNull(followedSourceOf(stored, packageName, emptySet(), listOf(github.copy(packageName = "other.app"))))
    }

    @Test
    fun `only the followed external source takes the updates`() {
        val other = github.copy(owner = "someone", repo = "aegis-fork")
        val followed = FollowedSource.External(github.key)
        assertTrue(github.takesUpdates(followed) { false })
        assertFalse(other.takesUpdates(followed) { true })
    }

    @Test
    fun `following a repository leaves every external source out`() {
        assertFalse(github.takesUpdates(FollowedSource.Repository(fdroid)) { true })
    }

    @Test
    fun `without a choice the automatic rule decides`() {
        assertTrue(github.takesUpdates(null) { true })
        assertFalse(github.takesUpdates(null) { false })
    }
}
