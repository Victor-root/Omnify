package com.looker.droidify.data

import com.looker.droidify.data.model.ApkFile
import com.looker.droidify.data.model.Html
import com.looker.droidify.data.model.Manifest
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Platforms
import com.looker.droidify.data.model.Repo
import com.looker.droidify.data.model.SDKs
import com.looker.droidify.network.DataSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which repository an app several offer is shown as: the followed one, else the installed copy's. */
class PresentingRepositoryTest {

    private fun repo(id: Int) = Repo(
        id = id,
        enabled = true,
        address = "https://repo$id.example/repo",
        icon = null,
        name = "Repo $id",
        description = Html(""),
        fingerprint = null,
        authentication = null,
        versionInfo = null,
        mirrors = emptyList(),
    )

    private fun build(repoId: Int, versionCode: Long, signer: String) = Package(
        id = repoId * 1000L + versionCode,
        installed = false,
        added = 0L,
        apk = ApkFile(name = "app_$versionCode.apk", hash = "h$versionCode", size = DataSize(1L)),
        platforms = Platforms(emptyList()),
        features = emptyList(),
        isPrerelease = false,
        antiFeatures = emptyList(),
        manifest = Manifest(
            versionCode = versionCode,
            versionName = "$versionCode",
            usesSDKs = SDKs(),
            signer = setOf(signer),
            permissions = emptyList(),
        ),
        whatsNew = "",
    ) to repo(repoId)

    /** Upstream in F-Droid (1), signed by F-Droid; the user's fork in their own repository (7). */
    private val packages = listOf(build(1, 20, "fdroid"), build(7, 10, "mine"))

    @Test
    fun `an installed copy is shown as the repository signing it, even an older one`() {
        assertEquals(7, presentingRepository(followed = null, installedSigner = "mine", packages = packages))
    }

    @Test
    fun `a followed repository wins over the installed copy's`() {
        assertEquals(
            1,
            presentingRepository(FollowedSource.Repository(1), installedSigner = "mine", packages = packages),
        )
    }

    @Test
    fun `a followed repository no longer offering the app is ignored`() {
        assertEquals(
            7,
            presentingRepository(FollowedSource.Repository(3), installedSigner = "mine", packages = packages),
        )
    }

    @Test
    fun `nothing followed nor installed leaves the default`() {
        assertNull(presentingRepository(followed = null, installedSigner = null, packages = packages))
        assertNull(presentingRepository(followed = null, installedSigner = "other", packages = packages))
    }

    @Test
    fun `lists take the followed repository first, then the installed copy's`() {
        assertEquals(
            mapOf("a" to 1, "b" to 7, "c" to 5),
            presentingRepositories(
                followed = mapOf("a" to "repo:1", "c" to "repo:5", "d" to "external:x"),
                installedKeyRepositories = mapOf("a" to 7, "b" to 7),
            ),
        )
    }
}
