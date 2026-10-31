package com.looker.droidify.work

import com.looker.droidify.compose.appDetail.DownloadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatchUpdateProgressTest {

    @Test
    fun `finished packages and those in flight count together`() {
        val state = BatchUpdateProgress.State(
            count = 4,
            finished = 1,
            items = mapOf(
                "a" to BatchUpdateProgress.Item(DownloadStatus(read = 50, total = 100, bytesPerSecond = 0)),
                "b" to BatchUpdateProgress.Item(DownloadStatus(read = 25, total = 100, bytesPerSecond = 0)),
                "c" to BatchUpdateProgress.Item(),
            ),
        )

        assertEquals(1.75f / 4, state.overallFraction)
        assertEquals(2, state.position)
    }

    @Test
    fun `position never goes past the count`() {
        assertEquals(3, BatchUpdateProgress.State(count = 3, finished = 3).position)
    }

    @Test
    fun `a finished package leaves the ones in flight and moves the count on`() {
        val progress = BatchUpdateProgress()
        progress.start(3)
        progress.startPackage("a")
        progress.startPackage("b")
        progress.setTargetVersion("b", 42)
        progress.finishPackage("a")

        val state = progress.state.value!!
        assertEquals(1, state.finished)
        assertEquals(setOf("b"), state.items.keys)
        assertEquals(42L, state.items["b"]?.versionCode)
    }

    @Test
    fun `a package not in flight is not reported`() {
        val progress = BatchUpdateProgress()
        progress.start(1)
        progress.setTargetVersion("unknown", 1)

        assertNull(progress.state.value?.items?.get("unknown"))
    }
}
