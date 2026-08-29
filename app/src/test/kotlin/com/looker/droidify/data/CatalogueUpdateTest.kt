package com.looker.droidify.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the catalogue offers an update for an installed app.
 *
 * The case that motivated all of this is Every Door. F-Droid builds it with its own per-architecture
 * version-code recipe (`%c*10+<abi>`), so F-Droid's 6.0.0 carries code 553 while the developer's own
 * 7.1.0, installed straight from the project's releases, carries 60. Nothing about either number is
 * wrong; they are simply not the same numbering. Compared anyway, the Updates tab offered 553 as an
 * upgrade over a build a year newer, and the automatic installer would have acted on it.
 *
 * Three answers, tried in that order. Who owns the installed copy, settled by [externalSourceOwns]
 * rather than by this app's install record alone, since the record can drift and on the device this was
 * reported from it had. Then [catalogueBuildIsOlder], for a copy nothing here can account for at all.
 * Only then the version codes.
 */
class CatalogueUpdateTest {

    private val fdroidSigner = "aa11"
    private val developerSigner = "bb22"

    private fun catalogue(
        versionCode: Long,
        versionName: String = "6.0.0",
        signer: String = fdroidSigner,
    ) = SuggestedVersion(versionCode = versionCode, versionName = versionName, signers = setOf(signer))

    @Test
    fun `a newer catalogue build updates a copy the catalogue installed`() {
        // Ownership is about this copy, not about the package name: a source tracking the same project
        // changes nothing for a user who installed F-Droid's build and stayed on it.
        assertTrue(
            hasCatalogueUpdate(
                installedVersionCode = 551,
                installedVersionName = "5.9.0",
                installedSigner = fdroidSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = catalogue(553),
            ),
        )
    }

    @Test
    fun `the catalogue does not update an app an external source installed`() {
        // Every Door, verbatim: 7.1.0 from the project's own releases against F-Droid's 6.0.0.
        assertFalse(
            hasCatalogueUpdate(
                installedVersionCode = 60,
                installedVersionName = "7.1.0",
                installedSigner = developerSigner,
                isSystemApp = false,
                installedFromExternalSource = true,
                suggested = catalogue(553),
            ),
        )
    }

    @Test
    fun `ownership alone stops an update that nothing else would refuse`() {
        // The test above no longer proves the ownership rule works: the version names refuse that case
        // on their own now, so it would pass with ownership deleted. This isolates it. The catalogue is
        // genuinely ahead here, on the name and on the code, so ownership is the only thing left that
        // can say no.
        val offer = { owned: Boolean ->
            hasCatalogueUpdate(
                installedVersionCode = 60,
                installedVersionName = "6.0.0",
                installedSigner = developerSigner,
                isSystemApp = false,
                installedFromExternalSource = owned,
                suggested = catalogue(553, versionName = "7.0.0"),
            )
        }
        assertFalse(offer(true), "ownership did not stop the update")
        // And the same case unowned is offered, which is what makes the line above about ownership.
        assertTrue(offer(false), "the case was refused for some other reason, so it proves nothing")
    }

    @Test
    fun `an older catalogue release is refused even when nothing claims the copy`() {
        // The backstop: same numbers as Every Door, but with no source tracking the package at all, as
        // for a copy installed from a shop Omnify knows nothing about. The version codes still say
        // "newer"; the versions the two publishers wrote say otherwise, and they are the ones counting
        // the same thing.
        assertFalse(
            hasCatalogueUpdate(
                installedVersionCode = 60,
                installedVersionName = "7.1.0",
                installedSigner = developerSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = catalogue(553),
            ),
        )
    }

    @Test
    fun `a differently signed build is still offered for a normal app`() {
        // Unchanged and deliberate: the conflict is real, but uninstalling clears it, and both the
        // detail screen and the batch updater offer exactly that.
        assertTrue(
            hasCatalogueUpdate(
                installedVersionCode = 551,
                installedVersionName = "5.9.0",
                installedSigner = developerSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = catalogue(553),
            ),
        )
    }

    @Test
    fun `a differently signed build is not offered for a system app`() {
        // A system app can't be uninstalled, so there is no way to clear the conflict and nothing to
        // offer.
        assertFalse(
            hasCatalogueUpdate(
                installedVersionCode = 551,
                installedVersionName = "5.9.0",
                installedSigner = developerSigner,
                isSystemApp = true,
                installedFromExternalSource = false,
                suggested = catalogue(553),
            ),
        )
    }

    @Test
    fun `an older or equal catalogue build is not an update`() {
        assertFalse(
            hasCatalogueUpdate(
                installedVersionCode = 553,
                installedVersionName = "6.0.0",
                installedSigner = fdroidSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = catalogue(553),
            ),
        )
    }

    @Test
    fun `nothing installed and nothing offered are both not updates`() {
        assertFalse(
            hasCatalogueUpdate(
                installedVersionCode = null,
                installedVersionName = "5.9.0",
                installedSigner = fdroidSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = catalogue(553),
            ),
        )
        assertFalse(
            hasCatalogueUpdate(
                installedVersionCode = 551,
                installedVersionName = "5.9.0",
                installedSigner = fdroidSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = null,
            ),
        )
    }

    @Test
    fun `a rebuild under the same version name is still an update`() {
        // The backstop refuses a step backwards, never a step sideways: a project can ship two builds
        // under one name, and the version code is what tells them apart. Refusing this would hide a
        // real update from everyone it happens to, which is why the comparison is strict.
        assertTrue(
            hasCatalogueUpdate(
                installedVersionCode = 551,
                installedVersionName = "6.0.0",
                installedSigner = fdroidSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = catalogue(553, versionName = "6.0.0"),
            ),
        )
    }

    // Who owns the installed copy, which is what the ownership argument above is answered from. Either
    // half alone settles it: this app's own record of the install, or a signature the catalogue cannot
    // account for.

    @Test
    fun `a recorded install owns the copy whatever the signature says`() {
        assertTrue(
            externalSourceOwns(
                ownsInstalled = true,
                installedSigner = fdroidSigner,
                catalogueSigners = setOf(fdroidSigner),
            ),
        )
    }

    @Test
    fun `a signature the catalogue cannot account for owns the copy with no record`() {
        // The reported case: Every Door installed from the project's own releases on a device where the
        // record had drifted, so ownsInstalled answered false and F-Droid's older build was offered
        // again. F-Droid signs its own rebuilds, so a copy carrying the developer's key was never one.
        assertTrue(
            externalSourceOwns(
                ownsInstalled = false,
                installedSigner = developerSigner,
                catalogueSigners = setOf(fdroidSigner),
            ),
        )
    }

    @Test
    fun `a copy the catalogue signed is the catalogue's, even where a source tracks it`() {
        assertFalse(
            externalSourceOwns(
                ownsInstalled = false,
                installedSigner = fdroidSigner,
                catalogueSigners = setOf(fdroidSigner),
            ),
        )
    }

    @Test
    fun `handing the signature comparison in already made asks the same question`() {
        // What an app's own page does: it compares against every version the entry has ever declared,
        // which a package listing cannot, so it settles that half itself and passes the answer.
        assertTrue(externalSourceOwns(ownsInstalled = false, catalogueDidNotSignIt = true))
        assertTrue(externalSourceOwns(ownsInstalled = true, catalogueDidNotSignIt = false))
        assertFalse(externalSourceOwns(ownsInstalled = false, catalogueDidNotSignIt = false))
    }

    @Test
    fun `a signature that differs only in case is the same signature`() {
        // A fingerprint is hex text, and which case it arrives in depends on where it was read from.
        // Reading the two as different keys would hand every app to an external source and stop the
        // catalogue updating anything at all.
        assertFalse(signerMismatch("AA11", setOf("aa11")))
        assertFalse(signerMismatch("aa11", setOf("AA11")))
        assertFalse(
            externalSourceOwns(
                ownsInstalled = false,
                installedSigner = "AA11",
                catalogueSigners = setOf("aa11"),
            ),
        )
    }

    @Test
    fun `an unknown signature on either side settles nothing`() {
        // signerMismatch answers false whenever either side is unknown, so an unreadable signature or an
        // unsynced catalogue leaves the catalogue entry in place rather than rerouting on a guess.
        assertFalse(
            externalSourceOwns(
                ownsInstalled = false,
                installedSigner = null,
                catalogueSigners = setOf(fdroidSigner),
            ),
        )
        assertFalse(
            externalSourceOwns(
                ownsInstalled = false,
                installedSigner = developerSigner,
                catalogueSigners = emptySet(),
            ),
        )
    }

    // The backstop itself. It only ever refuses, so every case that isn't a plain step backwards has to
    // answer false, including every shape of version string that isn't really a number.

    @Test
    fun `a plainly older catalogue release is recognised`() {
        assertTrue(catalogueBuildIsOlder(installedVersionName = "7.1.0", catalogueVersionName = "6.0.0"))
        // Numerically, not alphabetically: "9" reads as greater than "10" as text.
        assertTrue(catalogueBuildIsOlder(installedVersionName = "1.10.0", catalogueVersionName = "1.9.0"))
    }

    @Test
    fun `a newer or equal catalogue release is not older`() {
        assertFalse(catalogueBuildIsOlder(installedVersionName = "6.0.0", catalogueVersionName = "7.1.0"))
        assertFalse(catalogueBuildIsOlder(installedVersionName = "7.1.0", catalogueVersionName = "7.1.0"))
    }

    @Test
    fun `a version carrying more parts than the other is ordered by them`() {
        // "1.2" and "1.2.3" are not the same release, and the shorter one is the earlier: a project
        // that ships 1.2 then 1.2.3 must not have the first read as the newer of the two.
        assertTrue(catalogueBuildIsOlder(installedVersionName = "1.2.3", catalogueVersionName = "1.2"))
        assertFalse(catalogueBuildIsOlder(installedVersionName = "1.2", catalogueVersionName = "1.2.3"))
    }

    @Test
    fun `a version with nothing to compare is left alone`() {
        assertFalse(catalogueBuildIsOlder(installedVersionName = null, catalogueVersionName = "6.0.0"))
        assertFalse(catalogueBuildIsOlder(installedVersionName = "7.1.0", catalogueVersionName = null))
        // Brave publishes releases with no version in them at all, and a single bare number is not a
        // dotted version either: neither side is a number this can order, so it says nothing.
        assertFalse(catalogueBuildIsOlder(installedVersionName = "7.1.0", catalogueVersionName = "nightly"))
        assertFalse(catalogueBuildIsOlder(installedVersionName = "", catalogueVersionName = "6.0.0"))
        assertFalse(catalogueBuildIsOlder(installedVersionName = "7", catalogueVersionName = "6"))
    }

    @Test
    fun `decoration around the number does not change the answer`() {
        // Version names are free text and carry all sorts of things around the number itself.
        assertTrue(catalogueBuildIsOlder(installedVersionName = "v7.1.0", catalogueVersionName = "v6.0.0"))
        assertTrue(
            catalogueBuildIsOlder(installedVersionName = "7.1.0-fork", catalogueVersionName = "6.0.0"),
        )
        assertFalse(
            catalogueBuildIsOlder(installedVersionName = "6.0.0", catalogueVersionName = "6.0.1-rc1"),
        )
    }
}
