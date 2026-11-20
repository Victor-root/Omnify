package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeclaredEntitiesTest {

    private val termuxStrings = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE resources [
            <!ENTITY TERMUX_PACKAGE_NAME "com.termux">
            <!ENTITY TERMUX_APP_NAME "Termux">
            ]>
        <resources>
            <string name="application_name">&TERMUX_APP_NAME;</string>
        </resources>
    """.trimIndent()

    @Test
    fun `a name declared in the file's own DOCTYPE is put in place`() {
        assertEquals("Termux", resolveDeclaredEntities("&TERMUX_APP_NAME;", termuxStrings))
    }

    @Test
    fun `XML's own entities are left for unescaping`() {
        assertEquals("Tom &amp; Jerry", resolveDeclaredEntities("Tom &amp; Jerry", termuxStrings))
    }

    @Test
    fun `a reference declared nowhere gives no name`() {
        assertNull(resolveDeclaredEntities("&APP_NAME;", termuxStrings))
    }
}
