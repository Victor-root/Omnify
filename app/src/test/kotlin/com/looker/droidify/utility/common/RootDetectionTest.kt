package com.looker.droidify.utility.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootDetectionTest {

    @Test
    fun `a French translation is recognised`() {
        assertTrue(RootDetection.textIndicatesRoot("Nécessite root : il copie les fichiers de /data/data."))
        assertTrue(RootDetection.textIndicatesRoot("Fonctionne sur un appareil rooté."))
    }

    @Test
    fun `the English original counts when the translation says it another way`() {
        assertTrue(RootDetection.textIndicatesRoot("Exige les droits administrateur.", "Requires root: copies /data/data."))
    }

    @Test
    fun `a negation only cancels its own text`() {
        assertFalse(RootDetection.textIndicatesRoot("Fonctionne sans root, accès root facultatif."))
        assertFalse(RootDetection.textIndicatesRoot("Works without root access."))
        assertTrue(RootDetection.textIndicatesRoot("Fonctionne sans root.", "Requires root for backups."))
    }

    @Test
    fun `a bare mention of root is not enough`() {
        assertFalse(RootDetection.textIndicatesRoot("Calcule la racine carrée", "Square root calculator"))
    }
}
