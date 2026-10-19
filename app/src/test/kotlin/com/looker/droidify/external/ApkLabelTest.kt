package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Naming the apps followed in a repository after their APKs, as long as that tells them apart. */
class ApkLabelTest {

    private fun rvb(variant: String, apkLabel: String?) = ExternalApp(
        owner = "nullcpy",
        repo = "rvb",
        variant = variant,
        label = prettifyRepoName(variant),
        apkLabel = apkLabel,
    )

    private val ricardo = rvb("rikardo-nemlopot-smart123s", "Ricardo nem lopot")
    private val maps = rvb("google-maps-bearinmindcat", "Ungoogled Maps")
    private val mapsUngoogled = rvb("google-maps-bearinmindcat-ungoogled", "Ungoogled Maps")
    private val mapsMicrog = rvb("google-maps-bearinmindcat-ungoogled-microg", null)

    @Test
    fun `an app is named after its APK`() {
        assertEquals("Ricardo nem lopot", ricardo.uninstalledLabel(listOf(ricardo, maps)))
    }

    @Test
    fun `apps whose APKs share a name keep the names of their files`() {
        val followed = listOf(maps, mapsUngoogled, mapsMicrog)
        assertEquals("Google Maps Bearinmindcat", maps.uninstalledLabel(followed))
        assertEquals("Google Maps Bearinmindcat Ungoogled", mapsUngoogled.uninstalledLabel(followed))
        assertEquals("Google Maps Bearinmindcat Ungoogled Microg", mapsMicrog.uninstalledLabel(followed))
    }

    @Test
    fun `a name another app of the repository already shows is taken too`() {
        val installed = rvb("google-maps-bearinmindcat-ungoogled", null).copy(label = "Ungoogled Maps")
        assertEquals("Google Maps Bearinmindcat", maps.uninstalledLabel(listOf(maps, installed)))
    }

    @Test
    fun `the same name in another repository doesn't count`() {
        val elsewhere = ExternalApp(owner = "someone", repo = "maps", apkLabel = "Ungoogled Maps")
        assertEquals("Ungoogled Maps", maps.uninstalledLabel(listOf(maps, elsewhere)))
    }

    @Test
    fun `only a name Omnify picked is replaced`() {
        assertTrue(maps.hasAutomaticLabel)
        assertTrue(maps.copy(label = "Ungoogled Maps").hasAutomaticLabel)
        assertFalse(maps.copy(label = "My maps", nameOverridden = true).hasAutomaticLabel)
        assertFalse(maps.copy(label = "Google Maps").hasAutomaticLabel)
    }
}
