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
    fun `a re-uploaded asset for the same tracked version is not an update`() {
        // Same idea as above, but tracked: installedTag/installedApkToken/installedVersionName are all
        // set (this source was installed through the app) and agree with the device, so this exercises
        // the token/label-based provenance check (hasUpdate) instead of the plain dotted-version
        // fallback. Brave can replace a release's asset under the same tag after publish (a respin),
        // which changes installedApkToken vs latestApkToken even though the version is unchanged; with
        // no version in the file name, the label comparison has to fall back to the tag to catch this.
        val same = source(
            latestApkName = "Bravearm64Universal.apk",
            latestTag = "v1.93.134",
            installedTag = "v1.93.134",
            installedApkToken = "2026-08-01T00:00:00Z",
            latestApkToken = "2026-08-09T00:00:00Z",
            installedVersionName = "1.93.134",
        )

        assertFalse(
            same.hasUpdateGiven("1.93.134"),
            "Offered an update for a re-uploaded asset of the exact version already installed",
        )
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

    // isUpdatePending is what the Updates tab lists and what the automatic installer acts on, so these
    // cover the two opt-outs it adds on top of the comparison above. Getting one wrong no longer just
    // shows a wrong row: it would install something behind the user's back, or on a source they
    // deliberately disabled.

    @Test
    fun `a disabled source offers nothing, however new its release`() {
        val disabled = source(latestApkName = "BraveMonoarm64.apk", latestTag = "v1.94.0")
            .copy(enabled = false)

        assertTrue(disabled.hasUpdateGiven("1.93.130"), "Test setup no longer describes a real update")
        assertFalse(
            disabled.isUpdatePending("1.93.130"),
            "Listed an update for a source the user had switched off",
        )
    }

    @Test
    fun `a track-only source offers nothing, however new its release`() {
        val tracked = source(latestApkName = "BraveMonoarm64.apk", latestTag = "v1.94.0")
            .copy(muteUpdates = true)

        assertFalse(
            tracked.isUpdatePending("1.93.130"),
            "Listed an update for a source set to track only",
        )
    }

    @Test
    fun `a source with nothing installed is never an update`() {
        // The auto-installer only ever updates what is on the device. Passing no on-device version is
        // how "not installed" reaches here, and it has to come back false or an app the user merely
        // follows would be installed for them.
        val notInstalled = source(latestApkName = "BraveMonoarm64.apk", latestTag = "v1.94.0")

        assertFalse(
            notInstalled.isUpdatePending(null),
            "Treated an app that isn't installed as having an update waiting",
        )
    }

    @Test
    fun `an enabled, unmuted source with a newer release is an update`() {
        val normal = source(latestApkName = "BraveMonoarm64.apk", latestTag = "v1.94.0")

        assertTrue(normal.isUpdatePending("1.93.130"), "Missed a plain, ordinary update")
    }
}
