package com.looker.droidify.external

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which APK a release hands the device, stated as properties and thrown a great many release shapes.
 *
 * This is the decision with the least room for error in the app: what it picks is what gets installed.
 * Release assets carry no architecture metadata, only file names, so the choice is made by reading
 * those names, and projects name them however they please. A regression here would install a build for
 * the wrong architecture, or a leftover debug build, and would look like a broken app rather than like
 * a bug in Omnify.
 *
 * Seeded, so a failure is reproducible and the suite never flickers.
 */
class ReleaseSelectionPropertiesTest {

    private val arm64 = "arm64-v8a"
    private val arm32 = "armeabi-v7a"
    private val deviceAbis = listOf(arm64, arm32)

    private fun asset(name: String) = ReleaseAsset(name = name, downloadUrl = "https://x/$name")

    /** Every way a project might spell an arm64 build, and the aliases the app is expected to know. */
    private val arm64Names = listOf(
        "app-arm64-v8a-release.apk", "MyApp_arm64.apk", "thing-aarch64.apk", "v2.1-ARM64-V8A.apk",
    )
    private val arm32Names = listOf("app-armeabi-v7a-release.apk", "MyApp-armv7.apk", "thing-arm32.apk")
    private val foreignNames = listOf("app-x86_64-release.apk", "MyApp-x86.apk", "thing-x64.apk")
    private val universalNames = listOf("app-universal-release.apk", "MyApp-noarch.apk", "all-in-one.apk")
    private val plainNames = listOf("app-release.apk", "Magisk-v30.7.apk", "output.apk", "MyApp.apk")
    private val debugNames = listOf("app-debug.apk", "MyApp-unsigned.apk", "build-DEBUG.apk")
    private val notApks = listOf(
        "source.zip", "README.md", "app.apk.sha256", "checksums.txt", "app.aab", "notes.APK.asc",
    )

    private val everything =
        arm64Names + arm32Names + foreignNames + universalNames + plainNames + debugNames + notApks

    private fun randomAssets(rnd: Random): List<ReleaseAsset> =
        List(rnd.nextInt(0, 7)) { asset(everything.random(rnd)) }

    @Test
    fun `what comes back is always one of the release's own apk files`() {
        // It can only ever hand back something the release actually published, and only an APK: the
        // one thing that would be unambiguously catastrophic is installing a file that is not one.
        val rnd = Random(20260829)
        repeat(30_000) {
            val assets = randomAssets(rnd)
            val picked = selectApkAsset(assets, deviceAbis, filter = null, releaseTag = "v1.2.3")
            if (picked == null) {
                assertTrue(
                    assets.none { it.name.endsWith(".apk", ignoreCase = true) },
                    "refused a release that does ship an APK: ${assets.map { it.name }}",
                )
            } else {
                assertTrue(picked in assets, "invented an asset: ${picked.name}")
                assertTrue(picked.name.endsWith(".apk", ignoreCase = true), "picked ${picked.name}")
            }
        }
    }

    @Test
    fun `the device's own architecture always wins when the release ships one`() {
        // The property that matters. Whatever else is in the release, and however many decoys are
        // sitting next to it, an arm64 build is what an arm64 device gets.
        val rnd = Random(4242)
        repeat(30_000) {
            val wanted = arm64Names.random(rnd)
            val assets = (randomAssets(rnd) + asset(wanted)).shuffled(rnd)
            val picked = selectApkAsset(assets, deviceAbis, filter = null, releaseTag = "v1.2.3")
            assertNotNull(picked)
            assertTrue(
                picked.name in arm64Names,
                "took ${picked.name} while $wanted was on offer",
            )
        }
    }

    @Test
    fun `a foreign architecture is never taken over one the device runs`() {
        val rnd = Random(777)
        repeat(30_000) {
            val compatible = (arm64Names + arm32Names).random(rnd)
            val assets = (
                List(rnd.nextInt(1, 4)) { asset(foreignNames.random(rnd)) } + asset(compatible)
                ).shuffled(rnd)
            val picked = selectApkAsset(assets, deviceAbis, filter = null, releaseTag = null)
            assertNotNull(picked)
            assertFalse(
                picked.name in foreignNames,
                "took the foreign ${picked.name} over $compatible",
            )
        }
    }

    @Test
    fun `a universal build is preferred to a bare one, and both to nothing`() {
        val rnd = Random(31337)
        repeat(20_000) {
            val universal = universalNames.random(rnd)
            val assets = (
                List(rnd.nextInt(1, 3)) { asset(plainNames.random(rnd)) } + asset(universal)
                ).shuffled(rnd)
            val picked = selectApkAsset(assets, deviceAbis, filter = null, releaseTag = null)
            assertNotNull(picked)
            assertTrue(picked.name in universalNames, "took ${picked.name} over $universal")
        }
    }

    @Test
    fun `a debug build is never preferred to a real one`() {
        // Magisk ships app-debug.apk next to its real release. Upload order is not a ranking.
        val rnd = Random(9001)
        repeat(20_000) {
            val real = plainNames.random(rnd)
            val assets = (
                List(rnd.nextInt(1, 3)) { asset(debugNames.random(rnd)) } + asset(real)
                ).shuffled(rnd)
            val picked = selectApkAsset(assets, deviceAbis, filter = null, releaseTag = null)
            assertNotNull(picked)
            assertTrue(picked.name in plainNames, "took the debug build ${picked.name} over $real")
        }
    }

    @Test
    fun `a debug build is still installed when it is all there is`() {
        // Refusing everything over one signal would leave the user with nothing at all, which is worse
        // than an odd-looking file name.
        val picked = selectApkAsset(
            listOf(asset("app-debug.apk"), asset("other-debug.apk")),
            deviceAbis,
            filter = null,
            releaseTag = null,
        )
        assertNotNull(picked)
    }

    @Test
    fun `a broken or unmatched filter never leaves the user with nothing`() {
        // The filter is typed by a person. A typo must not turn into "this app cannot be installed".
        val rnd = Random(5)
        val filters = listOf("[", "(unclosed", "*", "+", "?", "\\", "nothing-matches-this", "", "   ")
        repeat(20_000) {
            val assets = randomAssets(rnd) + asset(plainNames.random(rnd))
            val picked = selectApkAsset(assets, deviceAbis, filters.random(rnd), releaseTag = null)
            assertNotNull(picked, "a valid release became uninstallable over a filter")
            assertTrue(picked.name.endsWith(".apk", ignoreCase = true))
        }
    }

    @Test
    fun `a release with no apk at all is refused rather than guessed at`() {
        val rnd = Random(6)
        repeat(5_000) {
            val assets = List(rnd.nextInt(0, 5)) { asset(notApks.random(rnd)) }
            assertNull(selectApkAsset(assets, deviceAbis, filter = null, releaseTag = null))
        }
    }

    @Test
    fun `the same release always yields the same apk`() {
        // Asked twice, answered twice the same: an install and the update check that offered it must
        // not be able to disagree about which file they mean.
        val rnd = Random(8)
        repeat(20_000) {
            val assets = randomAssets(rnd)
            val once = selectApkAsset(assets, deviceAbis, filter = null, releaseTag = "v1.2.3")
            val twice = selectApkAsset(assets, deviceAbis, filter = null, releaseTag = "v1.2.3")
            assertEquals(once, twice)
        }
    }

    // Which releases a source is allowed to offer at all, the gate every release passes before any of
    // the above is even asked.

    @Test
    fun `a prerelease is only ever offered when the source asked for them`() {
        val rnd = Random(11)
        repeat(20_000) {
            val release = Release(
                tag = "v${rnd.nextInt(9)}.${rnd.nextInt(9)}",
                name = if (rnd.nextBoolean()) null else "Release ${rnd.nextInt(9)}",
                isPrerelease = rnd.nextBoolean(),
                assets = emptyList(),
            )
            assertFalse(
                release.isAllowedBy(includePrereleases = false, versionExcludeFilter = null) &&
                    release.isPrerelease,
                "let the prerelease ${release.tag} through",
            )
        }
    }

    @Test
    fun `an exclude keyword is matched in the tag and in the title alike`() {
        val nightlyTag = Release("v1.2-nightly", "Stable enough", false, emptyList())
        val nightlyTitle = Release("v1.2", "Nightly build", false, emptyList())
        val neither = Release("v1.2", "Release 1.2", false, emptyList())
        for (release in listOf(nightlyTag, nightlyTitle)) {
            assertTrue(release.matchesExcludeFilter("nightly"), "missed ${release.tag}")
            assertTrue(release.matchesExcludeFilter("NIGHTLY"), "case-sensitive on ${release.tag}")
            assertTrue(release.matchesExcludeFilter("beta, nightly ,alpha"), "list form on ${release.tag}")
        }
        assertFalse(neither.matchesExcludeFilter("nightly"))
    }

    @Test
    fun `an empty keyword list never excludes anything`() {
        // A blank field is not "exclude everything": that would empty the source silently.
        val release = Release("v1.2-nightly", "Nightly", true, emptyList())
        for (blank in listOf(null, "", "   ", ",", " , , ")) {
            assertFalse(release.matchesExcludeFilter(blank), "excluded on [$blank]")
        }
    }

    @Test
    fun `filtering a list agrees with asking each release on its own`() {
        // allowedBy exists so the two can never drift; this is that promise, checked.
        val rnd = Random(13)
        repeat(10_000) {
            val releases = List(rnd.nextInt(0, 6)) {
                Release(
                    tag = listOf("v1.0", "v2.0-beta", "nightly-3", "v3.1").random(rnd),
                    name = listOf(null, "Nightly", "Stable", "Beta channel").random(rnd),
                    isPrerelease = rnd.nextBoolean(),
                    assets = emptyList(),
                )
            }
            val includePrereleases = rnd.nextBoolean()
            val exclude = listOf(null, "", "nightly", "beta,nightly").random(rnd)
            assertEquals(
                releases.filter { it.isAllowedBy(includePrereleases, exclude) },
                releases.allowedBy(includePrereleases, exclude),
            )
        }
    }
}
