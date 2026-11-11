package com.looker.droidify.data.local.model

import com.looker.droidify.data.model.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a stored repository's fingerprint means to a sync. */
class RepoFingerprintTest {

    private fun repo(fingerprint: String) = RepoEntity(
        address = "https://example.org/repo",
        webBaseUrl = null,
        fingerprint = Fingerprint(fingerprint),
        timestamp = null,
    ).toRepo(
        name = "Example",
        description = "",
        icon = null,
        mirrors = emptyList(),
        enabled = true,
        authentication = null,
    )

    @Test
    fun `a repository added without a fingerprint has none to check the index against`() {
        assertNull(repo("").fingerprint)
    }

    @Test
    fun `a repository added with its fingerprint keeps it`() {
        val fingerprint = "91B3FE31C4F3DD58921EB2FAEABA0AC72DE284F5D3FB6F39463CC0A06B66B06A"
        assertEquals(Fingerprint(fingerprint), repo(fingerprint).fingerprint)
    }
}
