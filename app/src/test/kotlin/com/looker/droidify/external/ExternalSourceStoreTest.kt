package com.looker.droidify.external

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adding a source, and above all whether the store says what it did.
 *
 * It used to skip a source it already tracked without a word, and the one caller that talks to the
 * user went on to say "Added" either way: an app reached from a badge link that was already in the
 * list was reported as added and nothing happened. Making that impossible is the point of the answer
 * these tests are about.
 */
class ExternalSourceStoreTest {

    @TempDir
    lateinit var dir: File

    private fun store(): ExternalAppRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        return ExternalAppRepository(context)
    }

    private lateinit var repository: ExternalAppRepository

    private val adaway = ExternalApp(owner = "AdAway", repo = "AdAway")

    @BeforeEach
    fun setUp() {
        repository = store()
    }

    @Test
    fun `a source that is not tracked yet is added, and says so`() = runTest {
        assertTrue(repository.addApp(adaway))

        assertEquals(listOf(adaway.key), repository.getApps().map { it.key })
    }

    @Test
    fun `the same source twice says no the second time and stays one entry`() = runTest {
        assertTrue(repository.addApp(adaway))

        assertFalse(repository.addApp(adaway), "the second add cannot report success")
        assertEquals(1, repository.getApps().size)
    }

    @Test
    fun `the answer is about the key, not the rest of the entry`() = runTest {
        repository.addApp(adaway)

        // Same repository, renamed and re-configured: still the same source, so still not an add.
        val again = adaway.copy(label = "AdAway (custom)", nameOverridden = true, muteUpdates = true)
        assertFalse(repository.addApp(again))
        assertEquals("AdAway", repository.getApps().single().label, "the tracked entry is untouched")
    }

    @Test
    fun `another repository of the same owner is its own source`() = runTest {
        assertTrue(repository.addApp(adaway))

        assertTrue(repository.addApp(ExternalApp(owner = "AdAway", repo = "AdAway-fork")))
        assertEquals(2, repository.getApps().size)
    }

    @Test
    fun `a source already on disk is recognised by a store that has read nothing yet`() = runTest {
        // The whole bug: the caller checked a list it held in memory, which is empty until a screen has
        // asked for it, and an add arriving from a badge link arrives before that. The store itself
        // reads what is really there, which is why its answer is the one to believe.
        repository.addApp(adaway)

        val fresh = store()
        assertFalse(fresh.addApp(adaway), "a fresh store must still see what is already saved")
        assertEquals(1, fresh.getApps().size)
    }

    @Test
    fun `what was added survives a restart`() = runTest {
        repository.addApp(adaway)
        repository.addApp(ExternalApp(owner = "Zverik", repo = "every_door"))

        val fresh = store()
        assertEquals(
            listOf("AdAway/AdAway", "Zverik/every_door"),
            fresh.getApps().map { it.path },
        )
    }

    @Test
    fun `an upsert replaces rather than refusing, unlike an add`() = runTest {
        repository.addApp(adaway)

        repository.upsertApp(adaway.copy(label = "AdAway (custom)"))

        assertEquals("AdAway (custom)", repository.getApps().single().label)
        assertEquals(1, repository.getApps().size)
    }

    @Test
    fun `removing a source lets it be added again`() = runTest {
        repository.addApp(adaway)
        repository.removeApp(adaway.key)

        assertTrue(repository.addApp(adaway))
        assertEquals(1, repository.getApps().size)
    }
}
