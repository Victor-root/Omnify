package com.looker.droidify.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether the catalogue offers an update for an installed app.
 *
 * The case that motivated the external-source half is Every Door. F-Droid builds it with its own
 * per-architecture version-code recipe (`%c*10+<abi>`), so F-Droid's 6.0.0 carries code 553 while the
 * developer's own 7.1.0, installed straight from the project's releases, carries 60. Nothing about
 * either number is wrong; they are simply not the same numbering. Compared anyway, the Updates tab
 * offered 553 as an upgrade over a build a year newer, and the automatic installer would have acted
 * on it.
 */
class CatalogueUpdateTest {

    private val fdroidSigner = "aa11"
    private val developerSigner = "bb22"

    private fun catalogue(versionCode: Long, signer: String = fdroidSigner) =
        SuggestedVersion(versionCode = versionCode, signers = setOf(signer))

    @Test
    fun `a newer catalogue build updates a copy the catalogue installed`() {
        // Ownership is about this copy, not about the package name: a source tracking the same project
        // changes nothing for a user who installed F-Droid's build and stayed on it.
        assertTrue(
            hasCatalogueUpdate(
                installedVersionCode = 551,
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
                installedSigner = developerSigner,
                isSystemApp = false,
                installedFromExternalSource = true,
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
                installedSigner = fdroidSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = catalogue(553),
            ),
        )
        assertFalse(
            hasCatalogueUpdate(
                installedVersionCode = 551,
                installedSigner = fdroidSigner,
                isSystemApp = false,
                installedFromExternalSource = false,
                suggested = null,
            ),
        )
    }
}
