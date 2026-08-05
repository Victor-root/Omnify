package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a tracked source offers an update, against the app's real on-device version.
 *
 * The case that motivated these is Brave: its GitHub APKs are named per architecture with no version
 * in the file name ("BraveMonoarm64.apk"), and its newest build reaches Play before the matching
 * GitHub release stops being a pre-release. So the newest release a source can see is genuinely
 * *older* than what is installed, and the app has to say nothing rather than offer a way backwards.
 */
class ExternalAppUpdateTest {

    private fun source(
        latestApkName: String? = null,
        latestTag: String? = null,
        installedTag: String? = null,
        installedApkToken: String? = null,
        latestApkToken: String? = null,
        installedVersionName: String? = null,
    ) = ExternalApp(
        owner = "brave",
        repo = "brave-browser",
        latestApkName = latestApkName,
        latestTag = latestTag,
        installedTag = installedTag,
        installedApkToken = installedApkToken,
        latestApkToken = latestApkToken,
        installedVersionName = installedVersionName,
    )

    @Test
    fun `a release older than the installed version is not an update`() {
        val brave = source(latestApkName = "BraveMonoarm64.apk", latestTag = "v1.93.129")

        assertFalse(
            brave.hasUpdateGiven("1.93.130"),
            "Offered 1.93.129 over an installed 1.93.130, which is a downgrade",
        )
    }

    @Test
    fun `an unversioned file name does not read as newer than everything`() {
        // The file name carries no version, so the tag is what gets compared. Without that, the name
        // itself was compared as text against a version number, and a leading letter sorts after any
        // digit, so every such release looked newer than whatever was installed.
        val noVersionAnywhere = source(latestApkName = "app-release.apk", latestTag = "latest")

        assertFalse(
            noVersionAnywhere.hasUpdateGiven("1.93.130"),
            "Claimed an update with no version number on either side to compare",
        )
    }

    @Test
    fun `a genuinely newer release is still offered`() {
        val fromTag = source(latestApkName = "BraveMonoarm64.apk", latestTag = "v1.93.131")
        val fromFileName = source(latestApkName = "Brave-1.94.0-arm64.apk", latestTag = "v1.94.0")

        assertTrue(fromTag.hasUpdateGiven("1.93.130"), "Missed a newer release named only in the tag")
        assertTrue(fromFileName.hasUpdateGiven("1.93.130"), "Missed a newer release named in the file")
    }

    @Test
    fun `the same version is not an update`() {
        val same = source(latestApkName = "BraveMonoarm64.apk", latestTag = "v1.93.130")

        assertFalse(same.hasUpdateGiven("1.93.130"), "Offered the version already installed")
    }

    @Test
    fun `a downgrade stays blocked even when the record matches the device`() {
        // This source was installed through the app, so its record agrees with the device and the
        // provenance check (a changed APK identity) would normally be trusted outright. A changed APK
        // still must not mean "update" when the version behind it went backwards.
        val installedHere = source(
            latestApkName = "BraveMonoarm64.apk",
            latestTag = "v1.93.129",
            installedTag = "v1.93.130",
            installedApkToken = "token-of-the-installed-one",
            latestApkToken = "token-of-an-older-rebuild",
            installedVersionName = "1.93.130",
        )

        assertFalse(
            installedHere.hasUpdateGiven("1.93.130"),
            "A different APK was taken as an update even though its version is older",
        )
    }

    @Test
    fun `an app installed outside the source is still updated when a newer release exists`() {
        // Nothing recorded (installed by hand, or from another store), so the decision rests entirely
        // on the version comparison. This is the path most users of a tracked source are on.
        val untracked = source(latestApkName = "BraveMonoarm64.apk", latestTag = "v1.94.0")

        assertTrue(untracked.hasUpdateGiven("1.93.130"), "Missed an update for an untracked install")
    }
}
