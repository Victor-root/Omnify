package com.looker.droidify.utility.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppCapabilitiesTest {

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

    @Test
    fun `Shizuku and Sui are recognised, a passing sui is not`() {
        assertTrue(ShizukuDetection.textIndicatesShizuku("Uses Shizuku to freeze apps."))
        assertTrue(ShizukuDetection.textIndicatesShizuku("Fonctionne avec le module Sui."))
        assertFalse(ShizukuDetection.textIndicatesShizuku("Je suis une appli, suivez le guide."))
        assertFalse(ShizukuDetection.textIndicatesShizuku("Works without Shizuku."))
    }

    @Test
    fun `an APK's permissions and the app's text both count`() {
        val declared = AppCapabilities.declaredBy(setOf("android.permission.INTERNET", ShizukuDetection.PERMISSION))
        assertEquals(AppCapabilities(root = false, shizuku = true), declared)
        val described = AppCapabilities.describedBy("Requires root to back up apps.")
        assertEquals(AppCapabilities(root = true, shizuku = true), described or declared)
        assertFalse(AppCapabilities.declaredBy(setOf("android.permission.INTERNET")).any)
    }
}
