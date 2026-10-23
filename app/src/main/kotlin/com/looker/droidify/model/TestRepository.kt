package com.looker.droidify.model

import com.looker.droidify.BuildConfig

/**
 * The F-Droid repository the test build installs its own updates from. Its address comes from the
 * machine the build is made on (see app/build.gradle.kts), so it is never in the source, and every
 * other build gets none: outside the test build, nothing of it exists.
 *
 * The app keeps the address out of sight too: the repository's row can't be opened, apps from it
 * can't be shared, and neither backups nor the first-start page include it.
 */
object TestRepository {
    val address: String? = BuildConfig.TEST_REPO_ADDRESS.trimEnd('/').ifEmpty { null }

    /** Without the spaces or colons it is often copied with. */
    val fingerprint: String = BuildConfig.TEST_REPO_FINGERPRINT.filter { it.isLetterOrDigit() }

    fun hides(address: String): Boolean = this.address != null && address.trimEnd('/') == this.address
}
