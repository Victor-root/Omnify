package com.looker.droidify.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncProgressTest {

    @Test
    fun `repositories and sources count as one figure, each weighing the same`() {
        val progress = SyncProgress()
        progress.expectRepositories(listOf(1, 2))
        progress.startSources(2)
        progress.reportRepository(1, 0.5f)
        progress.sourceChecked()

        assertEquals(1.5f / 4, progress.fraction.value)
    }

    @Test
    fun `finished repositories keep counting while the sources go on`() {
        val progress = SyncProgress()
        progress.expectRepositories(listOf(1))
        progress.startSources(1)
        progress.finishRepositories(listOf(1))

        assertEquals(0.5f, progress.fraction.value)
    }

    @Test
    fun `nothing is shown once everything has finished`() {
        val progress = SyncProgress()
        progress.expectRepositories(listOf(1))
        progress.startSources(3)
        progress.finishRepositories(listOf(1))
        progress.finishSources()

        assertNull(progress.fraction.value)
    }

    @Test
    fun `a repository not counted in reports nothing`() {
        val progress = SyncProgress()
        progress.expectRepositories(listOf(1))
        progress.reportRepository(2, 1f)

        assertEquals(0f, progress.fraction.value)
    }

    @Test
    fun `nothing to count is no figure`() {
        assertNull(syncFraction(emptyList(), null))
        assertNull(syncFraction(emptyList(), 0 to 0))
    }
}
