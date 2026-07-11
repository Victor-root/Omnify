package com.looker.droidify.installer

import com.looker.droidify.installer.model.InstallState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Runs an install [block] with a hard upper bound and full failure isolation so that a single
 * misbehaving installer backend can never block the install queue forever.
 *
 * Guarantees:
 *  - returns the backend result when the install completes within [timeout],
 *  - returns [InstallState.Failed] when the backend never reports a result (timeout),
 *  - returns [InstallState.Failed] when the backend throws (IO errors, parsing errors,
 *    PackageInstaller exceptions, etc.),
 *  - re-throws [CancellationException] so structured/cooperative cancellation keeps working
 *    (e.g. the user pressing "Cancel" or the app scope shutting down).
 *
 * This is intentionally free of Android dependencies so it can be unit tested in isolation.
 */
internal suspend fun safeInstall(
    timeout: Duration,
    block: suspend () -> InstallState,
): InstallState {
    val result = runCatching {
        withTimeoutOrNull(timeout) { block() } ?: InstallState.Failed
    }
    // A real cancellation must keep propagating; every other failure becomes a Failed state.
    result.exceptionOrNull()?.let { error ->
        if (error is CancellationException) throw error
    }
    return result.getOrDefault(InstallState.Failed)
}
