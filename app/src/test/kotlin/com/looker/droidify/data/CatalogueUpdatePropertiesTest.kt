package com.looker.droidify.data

import com.looker.droidify.external.compareVersionStrings
import com.looker.droidify.external.dottedVersionOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The update rules stated as properties and thrown a great many inputs, rather than as the handful of
 * worked examples [CatalogueUpdateTest] covers. What is being defended is a bug that reached a user:
 * an update offered for a build its own publisher numbers as older.
 *
 * A version name is free text. It comes off a device or out of a repository index, and across an app
 * store it is dates, channels, build metadata, decoration around a number, and sometimes no number at
 * all. So the generator below deliberately spends most of its time on shapes that are not tidy.
 *
 * Seeded, so a failure is reproducible and the suite never flickers.
 */
class CatalogueUpdatePropertiesTest {

    private val shapes = listOf(
        "", " ", "0", "7", "1.0", "7.1.0", "v7.1.0", "1.10.0", "1.9.0", "2024.05.01", "20240501",
        "1.0.0-rc1", "1.0.0-beta.5", "1.0.0+build.7", "1.0.0_2", "1.0-dev", "nightly", "latest",
        "app-release.apk", "GlassKeep-v1.4.6.apk", "1..2", ".1.2", "1.2.", "-1.2.0", "1.2.3.4.5.6.7",
        "99999999999.0.0", "0.0.0", "00.01.02", "1.0.0 (build 42)", "Version 3.2.1", "3.2.1 stable",
    )

    private fun version(rnd: Random): String = when (rnd.nextInt(6)) {
        0, 1 -> shapes.random(rnd)
        2 -> "${rnd.nextInt(0, 40)}.${rnd.nextInt(0, 40)}.${rnd.nextInt(0, 40)}"
        3 -> "v${rnd.nextInt(0, 20)}.${rnd.nextInt(0, 20)}"
        4 -> "${rnd.nextInt(0, 10)}.${rnd.nextInt(0, 10)}.${rnd.nextInt(0, 10)}-rc${rnd.nextInt(1, 5)}"
        else -> buildString { repeat(rnd.nextInt(0, 12)) { append("0123456789.-+_ vabZ".random(rnd)) } }
    }

    @Test
    fun `the backstop never contradicts itself`() {
        val rnd = Random(20260829)
        repeat(20_000) {
            val a = version(rnd)
            val b = version(rnd)
            assertFalse(catalogueBuildIsOlder(a, a), "[$a] older than itself")
            assertFalse(
                catalogueBuildIsOlder(a, b) && catalogueBuildIsOlder(b, a),
                "[$a] and [$b] are each older than the other",
            )
        }
    }

    @Test
    fun `the backstop says nothing when there is nothing to compare`() {
        // It only ever refuses, so anything it cannot read has to leave the version codes deciding
        // exactly as they did before it existed.
        val rnd = Random(1312)
        repeat(20_000) {
            val a = version(rnd)
            val b = version(rnd)
            val comparable = dottedVersionOrNull(a) != null && dottedVersionOrNull(b) != null
            assertTrue(comparable || !catalogueBuildIsOlder(a, b), "spoke up about [$a] vs [$b]")
        }
    }

    @Test
    fun `no combination of inputs offers a build its publisher numbers as older`() {
        // The reported bug, stated as a property: whatever the codes, the signatures, the ownership or
        // the shape of either version string, an accepted offer is never a step backwards.
        val rnd = Random(7)
        val signers = listOf(null, "", "aa11", "bb22", "AA11")
        var offered = 0
        repeat(200_000) {
            val installedName = if (rnd.nextInt(20) == 0) null else version(rnd)
            val installedCode = if (rnd.nextInt(20) == 0) null else rnd.nextLong(-5, 1000)
            val suggested = if (rnd.nextInt(20) == 0) {
                null
            } else {
                SuggestedVersion(
                    versionCode = rnd.nextLong(-5, 1000),
                    versionName = version(rnd),
                    signers = if (rnd.nextBoolean()) emptySet() else setOf(signers.random(rnd) ?: "aa11"),
                )
            }
            val update = hasCatalogueUpdate(
                installedVersionCode = installedCode,
                installedVersionName = installedName,
                installedSigner = signers.random(rnd),
                isSystemApp = rnd.nextBoolean(),
                installedFromExternalSource = rnd.nextInt(4) == 0,
                suggested = suggested,
            )
            if (!update) return@repeat
            offered++
            val installed = installedName?.let(::dottedVersionOrNull)
            val catalogue = suggested?.versionName?.let(::dottedVersionOrNull)
            if (installed != null && catalogue != null) {
                assertTrue(
                    compareVersionStrings(catalogue, installed) >= 0,
                    "offered $catalogue over an installed $installed",
                )
            }
        }
        // The sweep is worthless if nothing ever got offered, so say that it did.
        assertTrue(offered > 1000, "only $offered offers accepted, the generator has stopped exercising this")
    }

    @Test
    fun `a genuine update is never swallowed`() {
        // The risk that runs the other way, and the worse one of the two: a backstop that hides real
        // updates. Wherever the catalogue is ahead on both the name and the code, the offer must stand.
        val rnd = Random(99)
        repeat(20_000) {
            val a = rnd.nextInt(0, 30)
            val b = rnd.nextInt(0, 30)
            val c = rnd.nextInt(0, 30)
            val installedName = "$a.$b.$c"
            val catalogueName = when (rnd.nextInt(3)) {
                0 -> "${a + 1}.$b.$c"
                1 -> "$a.${b + 1}.$c"
                else -> "$a.$b.${c + 1}"
            }
            val installedCode = rnd.nextLong(0, 1000)
            assertTrue(
                hasCatalogueUpdate(
                    installedVersionCode = installedCode,
                    installedVersionName = installedName,
                    installedSigner = "aa11",
                    isSystemApp = false,
                    installedFromExternalSource = false,
                    suggested = SuggestedVersion(installedCode + 1, catalogueName, setOf("aa11")),
                ),
                "swallowed $installedName -> $catalogueName",
            )
        }
    }

    @Test
    fun `ownership stays the same question however it is asked`() {
        val rnd = Random(555)
        val signers = listOf(null, "", "aa11", "bb22", "AA11")
        repeat(20_000) {
            val owns = rnd.nextBoolean()
            val installed = signers.random(rnd)
            val declared = if (rnd.nextBoolean()) emptySet() else setOf(signers.random(rnd) ?: "aa11")
            assertTrue(
                externalSourceOwns(owns, installed, declared) ==
                    externalSourceOwns(owns, signerMismatch(installed, declared)),
                "the two forms disagreed on $owns / $installed / $declared",
            )
            assertTrue(
                !owns || externalSourceOwns(owns, installed, declared),
                "a recorded install stopped owning its copy",
            )
        }
    }
}
