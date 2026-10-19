package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How the apps of a repository publishing several in one release stream are told apart. */
class ReleaseFamiliesTest {

    private fun release(tag: String) = Release(tag, null, false, emptyList())

    private fun familiesOf(vararg tags: String) = releaseFamilies(tags.map(::release), unmarkedName = "Repo")

    @Test
    fun `a prefix before the version names the app`() {
        // ente-io/ente, newest first.
        val families = familiesOf(
            "photos-v1.2.3", "auth-v4.4.26-rc", "photos-desktop-v1.7.0", "auth-v4.4.25", "locker-v1.0.9",
        )
        assertEquals(listOf("Photos", "Auth", "Photos Desktop", "Locker"), families.map { it.name })
        assertEquals(listOf("photos-v", "auth-v", "photos-desktop-v", "locker-v"), families.map { it.keyword })
        assertEquals("auth-v4.4.26-rc", families[1].latest.tag)
    }

    @Test
    fun `each keyword keeps its own app and no other`() {
        val tags = listOf("photos-v1.2.3", "photos-desktop-v1.7.0", "photos-offline-v0.0.4", "auth-v4.4.25")
        for (family in familiesOf(*tags.toTypedArray())) {
            assertEquals(
                listOf(family.latest.tag),
                tags.map(::release).filter { it.matchesIncludeFilter(family.keyword) }.map { it.tag },
            )
        }
    }

    @Test
    fun `a suffix after the version names the app when there is no prefix`() {
        // bitwarden/android: Authenticator and Password Manager.
        val families = familiesOf("v2026.9.1-bwa", "v2026.9.1-bwpm", "v2026.9.0-bwa")
        assertEquals(listOf("-bwa", "-bwpm"), families.map { it.keyword })
    }

    @Test
    fun `a regular repository is a single app`() {
        val families = familiesOf(
            "v2.0.0", "2.0.0-rc1", "v1.9.0-beta.2", "release-1.8", "v1.7.0+45", "Version 1.6", "v.1.5.0",
        )
        assertEquals(1, families.size)
        assertEquals(null, families.single().keyword)
        assertEquals("Repo", families.single().name)
    }

    @Test
    fun `a tag without a version is left out`() {
        assertEquals(listOf("v1.0.0"), familiesOf("nightly", "v1.0.0", "latest").map { it.latest.tag })
    }

    @Test
    fun `the unmarked app is reached by excluding the others`() {
        val families = familiesOf("v3.0.0", "wear-v1.0.0", "tv-v2.0.0")
        val main = families.first { it.keyword == null }
        val (_, include, exclude) = families.filtersFor(main, apk = "", include = "", exclude = "nightly")
        assertEquals("", include)
        assertEquals("nightly, wear-v, tv-v", exclude)
        val (_, wearInclude, wearExclude) =
            families.filtersFor(families[1], apk = "", include = "", exclude = "nightly")
        assertEquals("wear-v", wearInclude)
        assertEquals("nightly", wearExclude)
        assertTrue(release("v3.0.0").isAllowedBy(false, include, exclude))
    }

    private fun apks(tag: String, vararg names: String) =
        Release(tag, null, false, names.map { ReleaseAsset(it, "https://example.org/$it") })

    /** nullcpy/rvb, newest first: every release carries several apps, a different set each time. */
    private val rvb = listOf(
        apks(
            "260293",
            "instagram-hushgram-v451.0.0.53.80-arm64-v8a.apk",
            "instagram-hushgram-clone-v451.0.0.53.80-arm64-v8a.apk",
            "linkedin-michii-v4.1.1261-arm-v7a.apk",
            "linkedin-michii-v4.1.1261-arm64-v8a.apk",
        ),
        apks(
            "260256",
            "discord-venus-v348.10-Stable-arm-v7a.apk",
            "discord-venus-v348.10-Stable-arm64-v8a.apk",
            "google-photos-akashsriram-module-v7.96.0.993165104-arm64-v8a.zip",
            "google-photos-akashsriram-v7.96.0.993165104-arm64-v8a.apk",
            "meesho-debakarr-v29.6-arm-v7a.apk",
            "meesho-debakarr-v29.6-arm64-v8a.apk",
        ),
        apks("260251", "offline-games-ggyasin-v3.15.3-arm64-v8a.apk", "offline-games-ggyasin-v3.14.1-arm-v7a.apk"),
    )

    @Test
    fun `apps published side by side are told apart by their APK names`() {
        val families = appFamilies(rvb, unmarkedName = "Rvb")
        assertEquals(
            listOf(
                "Instagram Hushgram",
                "Instagram Hushgram Clone",
                "Linkedin Michii",
                "Discord Venus",
                "Google Photos Akashsriram",
                "Meesho Debakarr",
                "Offline Games Ggyasin",
            ),
            families.map { it.name },
        )
        assertEquals("meesho-debakarr", families.first { it.name == "Meesho Debakarr" }.variant)
        assertEquals("260256", families.first { it.name == "Meesho Debakarr" }.latest.tag)
    }

    @Test
    fun `each APK filter keeps its own app and no other`() {
        val families = appFamilies(rvb, unmarkedName = "Rvb")
        val allApks = rvb.flatMap { it.assets }.map { it.name }.filter { it.endsWith(".apk") }
        for (family in families) {
            val stem = family.variant
            val regex = Regex(family.apkFilter!!, RegexOption.IGNORE_CASE)
            val matched = allApks.filter { regex.containsMatchIn(it) }
            assertTrue(matched.isNotEmpty(), "${family.name} matches nothing")
            assertTrue(
                matched.all { it.startsWith("$stem-v") },
                "${family.name} (${family.apkFilter}) also matches ${matched.filterNot { it.startsWith("$stem-v") }}",
            )
        }
        // Each release offers exactly the apps it carries.
        val instagram = families.first { it.name == "Instagram Hushgram" }
        assertTrue(rvb[0].hasApkMatchingFilter(instagram.apkFilter))
        assertEquals(false, rvb[1].hasApkMatchingFilter(instagram.apkFilter))
        assertEquals(
            "instagram-hushgram-v451.0.0.53.80-arm64-v8a.apk",
            rvb[0].apkFileName(deviceAbis = listOf("arm64-v8a"), filter = instagram.apkFilter),
        )
    }

    @Test
    fun `builds of one app for several architectures or flavours are one app`() {
        val releases = listOf(
            apks("v2.0", "MyApp-2.0-arm64-v8a.apk", "MyApp-2.0-armeabi-v7a.apk", "MyApp-2.0-x86_64.apk"),
            apks("v1.9", "MyApp-1.9-fdroid.apk", "MyApp-1.9-play.apk", "MyApp-1.9-universal.apk"),
            apks("v1.8", "app-arm64-v8a-release-1.8.apk", "app-x86-release-1.8.apk"),
            apks("v1.7", "MyApp-fdroid-1.7.apk", "MyApp-play-1.7.apk", "MyApp_foss_v1.7.apk"),
        )
        assertTrue(apkFamilies(releases).isEmpty())
    }

    @Test
    fun `flavours of each app stay with their app`() {
        val releases = listOf(apks("r1", "appA-fdroid-1.0.apk", "appA-1.0.apk", "appB-play-2.0.apk", "appB-2.0.apk"))
        val families = apkFamilies(releases)
        assertEquals(listOf("appA", "appB"), families.map { it.name })
        val names = releases.single().assets.map { it.name }
        for (family in families) {
            val regex = Regex(family.apkFilter!!, RegexOption.IGNORE_CASE)
            assertEquals(names.filter { it.startsWith(family.name) }, names.filter(regex::containsMatchIn))
        }
    }

    @Test
    fun `an APK renamed over the years is still one app`() {
        val releases = listOf(apks("v3.0", "NewName-3.0.apk"), apks("v2.0", "OldName-2.0.apk"))
        assertTrue(apkFamilies(releases).isEmpty())
    }

    @Test
    fun `an APK without a version says nothing about which app it is`() {
        // Magisk ships a debug build next to the real one.
        val releases = listOf(apks("v30.7", "app-debug.apk", "Magisk-v30.7.apk"))
        assertTrue(apkFamilies(releases).isEmpty())
    }

    @Test
    fun `tags win over APK names when they tell apps apart`() {
        val releases = listOf(
            apks("photos-v1.2.3", "ente-photos-1.2.3.apk", "ente-photos-fdroid-1.2.3.apk"),
            apks("auth-v4.4.25", "ente-auth-4.4.25.apk"),
        )
        assertEquals(listOf("photos-v", "auth-v"), appFamilies(releases, unmarkedName = "Ente").map { it.keyword })
    }

    @Test
    fun `an APK family is pinned by its APK filter, leaving the keywords typed alone`() {
        val families = appFamilies(rvb, unmarkedName = "Rvb")
        val meesho = families.first { it.name == "Meesho Debakarr" }
        assertEquals(
            Triple(meesho.apkFilter!!, "stable", "beta"),
            families.filtersFor(meesho, apk = "arm64", include = "stable", exclude = "beta"),
        )
    }

    @Test
    fun `a family is known as followed by its variant, or by the filter of an older source`() {
        val families = appFamilies(rvb, unmarkedName = "Rvb")
        val meesho = families.first { it.name == "Meesho Debakarr" }
        val discord = families.first { it.name == "Discord Venus" }
        val source = ExternalApp(owner = "nullcpy", repo = "rvb")
        assertTrue(meesho.isFollowedBy(source.copy(variant = "meesho-debakarr")))
        assertEquals(false, discord.isFollowedBy(source.copy(variant = "meesho-debakarr")))
        assertTrue(meesho.isFollowedBy(source.copy(apkFilter = meesho.apkFilter)))
        assertEquals(false, meesho.isFollowedBy(source))
        val auth = familiesOf("auth-v4.4.25", "photos-v1.2.3").first()
        assertTrue(auth.isFollowedBy(ExternalApp(owner = "ente-io", repo = "ente", versionIncludeFilter = "auth-v")))
    }
}
