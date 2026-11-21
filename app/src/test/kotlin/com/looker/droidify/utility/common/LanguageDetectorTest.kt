package com.looker.droidify.utility.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageDetectorTest {

    @Test
    fun `identifiers and keys tell no language`() {
        // FLauncher's default strings: Firebase's own configuration, no sentence at all.
        val keys = "cc0764f6a482476f88edde9d2e308823 338594298980-un979b2l28hfmrchvhj359uks966qjff" +
            ".apps.googleusercontent.com 338594298980 AIzaSyD17eCvxq392F4Ffel_EItcbM2ZGbYzOJM " +
            "1:338594298980:android:6a8f25a060652f34213371 pc-api-6012911888153360500-895.appspot.com"
        assertNull(LanguageDetector.detectDominant(keys))
    }

    @Test
    fun `sentences still tell theirs, apostrophes and all`() {
        val french = "Ajoutez l'application à vos favoris pour la retrouver plus vite. Vous pouvez aussi " +
            "la masquer de la liste si elle ne vous intéresse pas, et la réafficher dans les réglages."
        assertEquals("fr", LanguageDetector.detectDominant(french))
    }
}
