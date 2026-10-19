package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Telling a source's own build from another one installed under the same package name. */
class ForeignCopyTest {

    private val source = ExternalApp(
        owner = "nullcpy",
        repo = "rvb",
        variant = "gboard",
        packageName = "com.google.android.inputmethod.latin",
        latestApkSigners = listOf("aa11", "bb22"),
    )

    @Test
    fun `a copy signed by one of the source's signers is its own`() {
        assertFalse(source.isForeignCopy("bb22"))
        assertFalse(source.isForeignCopy("AA11"))
    }

    @Test
    fun `a copy signed by anyone else is another build`() {
        // Google's own Gboard, next to a source publishing a modified one under its package name.
        assertTrue(source.isForeignCopy("cc33"))
    }

    @Test
    fun `nothing is judged while either side is unknown`() {
        assertFalse(source.isForeignCopy(null))
        assertFalse(source.copy(latestApkSigners = emptyList()).isForeignCopy("cc33"))
    }
}
