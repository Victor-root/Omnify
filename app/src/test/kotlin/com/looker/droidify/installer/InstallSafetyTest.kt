package com.looker.droidify.installer

import com.looker.droidify.installer.model.InstallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import kotlin.time.Duration.Companion.minutes

class InstallSafetyTest {

    @Test
    fun `returns backend result on success`() = runTest {
        val result = safeInstall(TIMEOUT) { InstallState.Installed }
        assertEquals(InstallState.Installed, result)
    }

    @Test
    fun `passes through a backend failure`() = runTest {
        val result = safeInstall(TIMEOUT) { InstallState.Failed }
        assertEquals(InstallState.Failed, result)
    }

    @Test
    fun `converts a thrown exception into Failed`() = runTest {
        val result = safeInstall(TIMEOUT) { throw RuntimeException("installer blew up") }
        assertEquals(InstallState.Failed, result)
    }

    @Test
    fun `converts a missing apk into Failed instead of crashing the queue`() = runTest {
        // Mirrors issue #1231: the APK is deleted before the installer can read it.
        val result = safeInstall(TIMEOUT) { throw FileNotFoundException("apk gone") }
        assertEquals(InstallState.Failed, result)
    }

    @Test
    fun `times out a backend that never reports a result`() = runTest {
        // Mirrors issue #781 / the stuck "Installing" report: a backend that never completes.
        val result = safeInstall(TIMEOUT) { awaitCancellation() }
        assertEquals(InstallState.Failed, result)
    }

    @Test
    fun `re-throws cancellation so cooperative cancellation keeps working`() = runTest {
        var caught: CancellationException? = null
        try {
            safeInstall(TIMEOUT) { throw CancellationException("cancelled by user") }
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull(caught)
    }

    private companion object {
        val TIMEOUT = 10.minutes
    }
}
