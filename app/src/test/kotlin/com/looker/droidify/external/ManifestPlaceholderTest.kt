package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An app whose name changes per build type writes a placeholder into its manifest and gives it a value
 * in the build file, so the manifest on its own never carries the name. Confirmed on
 * fluxerapp/flutter_client, whose source page showed the raw placeholder where its name should be.
 *
 * Covers the reading of that value out of a build file ([manifestPlaceholderValue]) and the putting of
 * it back into the label ([substituteManifestPlaceholders]).
 */
class ManifestPlaceholderTest {

    private val kotlinDslBuildFile = """
        android {
            defaultConfig {
                applicationId = "com.fluxer"
                manifestPlaceholders["appLabel"] = "Fluxer"
            }
            buildTypes {
                create("canary") { manifestPlaceholders["appLabel"] = "Fluxer Canary" }
                create("beta") { manifestPlaceholders["appLabel"] = "Fluxer Beta" }
            }
        }
    """.trimIndent()

    @Test
    fun `the defaultConfig value wins over the build-type overrides written after it`() {
        assertEquals("Fluxer", manifestPlaceholderValue(kotlinDslBuildFile, "appLabel"))
    }

    @Test
    fun `the put form is read too`() {
        val build = """manifestPlaceholders.put("appLabel", "Fluxer")"""
        assertEquals("Fluxer", manifestPlaceholderValue(build, "appLabel"))
    }

    @Test
    fun `groovy's property form is read too`() {
        val build = """manifestPlaceholders.appLabel = 'Fluxer'"""
        assertEquals("Fluxer", manifestPlaceholderValue(build, "appLabel"))
    }

    @Test
    fun `both map forms are read too`() {
        val groovy = """manifestPlaceholders = [appLabel: "Fluxer", pushProvider: "fcm"]"""
        assertEquals("Fluxer", manifestPlaceholderValue(groovy, "appLabel"))
        val kotlin = """manifestPlaceholders += mapOf("appLabel" to "Fluxer")"""
        assertEquals("Fluxer", manifestPlaceholderValue(kotlin, "appLabel"))
    }

    @Test
    fun `a value that is not a plain string is left unanswered rather than guessed`() {
        val build = """manifestPlaceholders["appLabel"] = appName"""
        assertNull(manifestPlaceholderValue(build, "appLabel"))
    }

    @Test
    fun `a name the build file never sets is unanswered`() {
        assertNull(manifestPlaceholderValue(kotlinDslBuildFile, "somethingElse"))
    }

    @Test
    fun `a placeholder is replaced by its value`() {
        assertEquals("Fluxer", substituteManifestPlaceholders("\${appLabel}") { "Fluxer" })
    }

    @Test
    fun `a label mixing text and placeholders keeps the text`() {
        val label = "\${brand} Reader \${channel}"
        val resolved = substituteManifestPlaceholders(label) {
            if (it == "brand") "Fluxer" else "Beta"
        }
        assertEquals("Fluxer Reader Beta", resolved)
    }

    @Test
    fun `a label with no placeholder is returned untouched`() {
        assertEquals("Omnify", substituteManifestPlaceholders("Omnify") { null })
    }

    @Test
    fun `one unresolved placeholder gives up on the whole label`() {
        // Half a name on the page would be worse than falling back to the repo name.
        assertNull(substituteManifestPlaceholders("\${brand} Reader") { if (it == "brand") null else "x" })
    }
}
