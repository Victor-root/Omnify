package com.looker.droidify.compose.externalApps

import com.looker.droidify.external.ExternalApp
import com.looker.droidify.utility.common.AppCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalCapabilitiesTest {

    private val app = ExternalApp(owner = "owner", repo = "repo")

    @Test
    fun `an Obtainium button in a README is not a mention of Shizuku`() {
        val readme = """
            <p>A two factor app.</p>
            <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%22shizukuPretendToBeGooglePlay%22">
            <img height="40" src=".github/assets/obtainium-badge.png"></a>
        """.trimIndent()
        assertEquals(AppCapabilities.NONE, app.capabilities(readme, null))
    }

    @Test
    fun `an inlined image is not read`() {
        val readme = """<p>A two factor app.</p><img src="data:image/png;base64,AAArequires rootAAA">"""
        assertFalse(app.capabilities(readme, null).root)
    }

    @Test
    fun `what a README says still counts`() {
        val capabilities = app.capabilities("<p>Works with <strong>Shizuku</strong>, and requires root.</p>", null)
        assertTrue(capabilities.shizuku)
        assertTrue(capabilities.root)
    }

    @Test
    fun `what the APK declares still counts`() {
        val declared = AppCapabilities(root = false, shizuku = true)
        assertTrue(app.capabilities(null, declared).shizuku)
    }
}
