package com.looker.droidify.utility.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundControlsTest {

    @Test
    fun `a maker with its own setting is recognised however it writes its name`() {
        assertTrue(hasOwnBackgroundControls("OPPO"))
        assertTrue(hasOwnBackgroundControls("Xiaomi"))
        assertTrue(hasOwnBackgroundControls("OnePlus"))
    }

    @Test
    fun `Android's own prompt does for everyone else`() {
        assertFalse(hasOwnBackgroundControls("Google"))
        assertFalse(hasOwnBackgroundControls("samsung"))
        assertFalse(hasOwnBackgroundControls(""))
    }
}
