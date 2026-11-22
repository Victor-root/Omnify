package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApkVariantsTest {

    private val phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    private fun assets(vararg names: String) = names.map { ReleaseAsset(it, "https://example.org/$it") }

    // Breezy Weather 6.2.2's real files.
    private val breezy = assets(
        "breezy-weather-arm64-v8a-v6.2.2_standard.apk",
        "breezy-weather-armeabi-v7a-v6.2.2_standard.apk",
        "breezy-weather-v6.2.2_freenet.apk",
        "breezy-weather-v6.2.2_standard.apk",
        "breezy-weather-x86-v6.2.2_standard.apk",
        "breezy-weather-x86_64-v6.2.2_standard.apk",
        "mapping-v6.2.2_freenet.tar.gz",
    )

    // The same project a release later, to check a filter still finds its file.
    private val breezyNext = assets(
        "breezy-weather-arm64-v8a-v6.3.0_standard.apk",
        "breezy-weather-armeabi-v7a-v6.3.0_standard.apk",
        "breezy-weather-v6.3.0_freenet.apk",
        "breezy-weather-v6.3.0_standard.apk",
        "breezy-weather-x86-v6.3.0_standard.apk",
        "breezy-weather-x86_64-v6.3.0_standard.apk",
    )

    private fun named(list: List<ReleaseAsset>, part: String) = list.first { part in it.name }

    @Test
    fun `each file is told apart by its processor and its own words, versions left out`() {
        val variants = apkVariants(breezy, phone).associate { it.asset.name to (it.abi to it.words) }

        assertEquals(6, variants.size, "only APKs")
        assertEquals("arm64-v8a" to listOf("standard"), variants["breezy-weather-arm64-v8a-v6.2.2_standard.apk"])
        assertEquals(null to listOf("freenet"), variants["breezy-weather-v6.2.2_freenet.apk"])
        assertEquals("x86_64" to listOf("standard"), variants["breezy-weather-x86_64-v6.2.2_standard.apk"])
    }

    @Test
    fun `a file built for another processor is marked as not running here`() {
        val compatible = apkVariants(breezy, phone).associate { it.asset.name to it.compatible }

        assertEquals(false, compatible["breezy-weather-x86-v6.2.2_standard.apk"])
        assertEquals(true, compatible["breezy-weather-armeabi-v7a-v6.2.2_standard.apk"])
        assertEquals(true, compatible["breezy-weather-v6.2.2_freenet.apk"])
    }

    @Test
    fun `picking Omnify's own choice clears the filter`() {
        assertNull(variantFilter(breezy, named(breezy, "arm64-v8a"), deviceAbis = phone))
    }

    @Test
    fun `a variant kept by its filter is the one picked in the next release too`() {
        val expected = mapOf(
            "breezy-weather-v6.2.2_freenet.apk" to "breezy-weather-v6.3.0_freenet.apk",
            "breezy-weather-armeabi-v7a-v6.2.2_standard.apk" to "breezy-weather-armeabi-v7a-v6.3.0_standard.apk",
            // The one needing the most: "standard" alone also names every per-processor file.
            "breezy-weather-v6.2.2_standard.apk" to "breezy-weather-v6.3.0_standard.apk",
        )
        for ((now, later) in expected) {
            val chosen = breezy.first { it.name == now }
            val filter = variantFilter(breezy, chosen, deviceAbis = phone)

            assertEquals(chosen, selectApkAsset(breezy, phone, filter), "this release, $now")
            assertEquals(later, selectApkAsset(breezyNext, phone, filter)?.name, "next release, $now")
        }
    }

    @Test
    fun `the filter reads as plain words when they are enough`() {
        assertEquals("freenet", variantFilter(breezy, named(breezy, "_freenet"), deviceAbis = phone))
        assertEquals(
            "^(?=.*standard)(?!.*arm64-v8a)(?!.*armeabi-v7a)(?!.*x86)(?!.*freenet)",
            variantFilter(breezy, named(breezy, "breezy-weather-v6.2.2_standard"), deviceAbis = phone),
        )
    }

    @Test
    fun `a file with nothing of its own is kept by what it lacks`() {
        // Mihon's real files: the plain universal build is the one without a processor or "foss".
        val now = assets(
            "mihon-arm64-v8a-v0.21.3.apk", "mihon-armeabi-v7a-v0.21.3.apk", "mihon-v0.21.3-foss.apk",
            "mihon-v0.21.3.apk", "mihon-x86-v0.21.3.apk", "mihon-x86_64-v0.21.3.apk",
        )
        val next = now.map { ReleaseAsset(it.name.replace("0.21.3", "0.22.0"), it.downloadUrl) }
        val filter = variantFilter(now, now[3], deviceAbis = phone)

        assertEquals("mihon-v0.21.3.apk", selectApkAsset(now, phone, filter)?.name)
        assertEquals("mihon-v0.22.0.apk", selectApkAsset(next, phone, filter)?.name)
    }

    @Test
    fun `a build id stamped on each file is not taken for a variant`() {
        // AutoJs6's real files: a commit id on each, different in every release.
        val release = assets(
            "autojs6-v6.8.0-arm64-v8a-818d75bb.apk", "autojs6-v6.8.0-armeabi-v7a-2609422d.apk",
            "autojs6-v6.8.0-universal-98abef1b.apk",
        )
        val next = assets(
            "autojs6-v6.9.0-arm64-v8a-62db1ff8.apk", "autojs6-v6.9.0-armeabi-v7a-df0b70ee.apk",
            "autojs6-v6.9.0-universal-110e6b64.apk",
        )

        assertEquals(listOf(emptyList<String>()), apkVariants(release, phone).map { it.words }.distinct())
        val filter = variantFilter(release, release[1], deviceAbis = phone)
        assertEquals("autojs6-v6.9.0-armeabi-v7a-df0b70ee.apk", selectApkAsset(next, phone, filter)?.name)
    }

    @Test
    fun `files alike but for a number are told apart by it`() {
        // Termux's real files: each processor's build twice, for Android 5 and for Android 7.
        val release = assets(
            "termux-app_v0.119.0-beta.3+apt-android-5-github-debug_arm64-v8a.apk",
            "termux-app_v0.119.0-beta.3+apt-android-7-github-debug_arm64-v8a.apk",
            "termux-app_v0.119.0-beta.3+apt-android-5-github-debug_x86_64.apk",
            "termux-app_v0.119.0-beta.3+apt-android-7-github-debug_x86_64.apk",
        )

        assertEquals(
            listOf(listOf("android-5"), listOf("android-7"), listOf("android-5"), listOf("android-7")),
            apkVariants(release, phone).map { it.words },
        )
        val next = release.map { ReleaseAsset(it.name.replace("0.119.0-beta.3", "0.119.0-beta.4"), it.downloadUrl) }
        val filter = variantFilter(release, release[1], deviceAbis = phone)
        assertEquals(next[1], selectApkAsset(next, phone, filter))
    }

    @Test
    fun `a processor written the short way is still read as one`() {
        val release = assets("koreader-android-arm-v2026.07.apk", "koreader-android-arm64-v2026.07.apk")

        assertEquals(listOf("armeabi-v7a", "arm64-v8a"), apkVariants(release, phone).map { it.abi })
    }

    @Test
    fun `builds of one app for two stores are told apart by their store`() {
        // The issue that asked for this: a GitHub build wanted, the F-Droid one picked.
        val release = assets("MyApp-2.1.0-fdroid.apk", "MyApp-2.1.0-github.apk")
        val variants = apkVariants(release, phone).map { it.words }

        assertEquals(listOf(listOf("fdroid"), listOf("github")), variants)
        assertEquals("github", variantFilter(release, release[1], deviceAbis = phone))
    }
}
