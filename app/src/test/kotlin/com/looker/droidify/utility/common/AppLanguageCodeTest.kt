package com.looker.droidify.utility.common

import com.looker.droidify.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Turning the locale Android says this app runs under back into the code the language setting is
 * stored with. Getting this wrong is quiet rather than loud: the setting simply goes on naming a
 * language the app is not in, which is how it stayed stuck on "system" for anyone who chose their
 * language in Android's own per-app screen.
 *
 * The cases below run against a small stated set of what an app might ship, so each expectation says
 * what it means on its own. The last test runs against this project's real set instead, since a
 * round trip has to hold for every language actually shipped, not for a chosen few.
 */
class AppLanguageCodeTest {

    private fun codeFor(tag: String?) = localeCodeForTag(tag, OFFERED)

    @Test
    fun `nothing set means the device decides`() {
        assertEquals(SYSTEM_LANGUAGE, codeFor(null))
        assertEquals(SYSTEM_LANGUAGE, codeFor(""))
        assertEquals(SYSTEM_LANGUAGE, codeFor("   "))
    }

    @Test
    fun `a plain language is itself`() {
        assertEquals("fr", codeFor("fr"))
        assertEquals("de", codeFor("de"))
    }

    @Test
    fun `a tag carrying a region the app ships lands on that region`() {
        // Android says "pt-BR", the resource directory is spelled "pt-rBR": the whole reason these
        // are compared as locales and not as text.
        assertEquals("pt-rBR", codeFor("pt-BR"))
        assertEquals("zh-rCN", codeFor("zh-CN"))
        assertEquals("zh-rTW", codeFor("zh-TW"))
    }

    @Test
    fun `the underscored spelling is understood too`() {
        assertEquals("pt-rBR", codeFor("pt_BR"))
        assertEquals("zh-rCN", codeFor("zh_CN"))
    }

    @Test
    fun `a tag carrying a script is still read as its language and region`() {
        // Android names Traditional Chinese "zh-Hant-TW". Split on the hyphen rather than read as a
        // language tag, "Hant" lands where the region belongs and the app comes up in Simplified.
        assertEquals("zh-rTW", codeFor("zh-Hant-TW"))
        assertEquals("zh-rCN", codeFor("zh-Hans-CN"))
    }

    @Test
    fun `a region the app does not ship falls back to the plain language`() {
        assertEquals("fr", codeFor("fr-CA"))
        assertEquals("de", codeFor("de-AT"))
    }

    @Test
    fun `a region the app does not ship takes another region of the same language`() {
        // Better the Chinese this app has than the English it would otherwise fall back to.
        assertEquals("zh-rCN", codeFor("zh-HK"))
        assertEquals("zh-rCN", codeFor("zh"))
    }

    @Test
    fun `the plain language is preferred over a region of it`() {
        assertEquals("pt", codeFor("pt"))
        assertEquals("pt", codeFor("pt-PT"))
        // Whichever order they happen to sit in: a preference that only holds because the plain one
        // was listed first is not a preference.
        assertEquals("pt", localeCodeForTag("pt-PT", listOf(SYSTEM_LANGUAGE, "pt-rBR", "pt")))
    }

    @Test
    fun `a language the app has nothing for leaves the list alone`() {
        // Never "whatever came first": before, that would have handed back a language the user does
        // not read, and the list the app really uses starts with the system entry, which hid it.
        assertEquals(SYSTEM_LANGUAGE, localeCodeForTag("is", listOf("fr", "de", "pt")))
        assertEquals(SYSTEM_LANGUAGE, localeCodeForTag(null, listOf("fr", "de", "pt")))
    }

    @Test
    fun `a three-letter language keeps all three letters, region or not`() {
        // Sliced at fixed offsets, as this used to be, "ckb-rIQ" became the language "ck".
        assertEquals("ckb", localeOfCode("ckb").language)
        assertEquals("ckb", localeOfCode("ckb-rIQ").language)
        assertEquals("IQ", localeOfCode("ckb-rIQ").country)
        assertEquals("ckb", localeCodeForTag("ckb-IQ", listOf(SYSTEM_LANGUAGE, "ckb", "fr")))
    }

    @Test
    fun `Java's own spelling of Indonesian and Hebrew still matches`() {
        // Android reports id and he; Java holds in and iw, which is what this project's directories
        // are named after. As plain text these never match, which is the point of comparing locales.
        assertEquals("in", codeFor("id"))
        assertEquals("in", codeFor("in"))
        assertEquals("iw", codeFor("he"))
        assertEquals("iw", codeFor("iw"))
    }

    @Test
    fun `a language the app has no translation for falls back to the device`() {
        assertEquals(SYSTEM_LANGUAGE, codeFor("is"))
        assertEquals(SYSTEM_LANGUAGE, codeFor("mn-MN"))
    }

    @Test
    fun `nonsense is not mistaken for a language`() {
        assertEquals(SYSTEM_LANGUAGE, codeFor("-"))
        assertEquals(SYSTEM_LANGUAGE, codeFor("---"))
        assertEquals(SYSTEM_LANGUAGE, codeFor("!!"))
    }

    @Test
    fun `the word system is never handed back as a language`() {
        // It is in the list the picker offers, but it is not a locale: reading it as one is how the
        // app ended up pinned to a language named "system".
        assertEquals(SYSTEM_LANGUAGE, codeFor(SYSTEM_LANGUAGE))
    }

    @Test
    fun `a tag in the wrong case is still read the way it means it`() {
        assertEquals("pt-rBR", codeFor("PT-br"))
        assertEquals("fr", codeFor("FR"))
    }

    @Test
    fun `a locale code becomes the locale it names`() {
        assertEquals("fr", localeOfCode("fr").language)
        assertEquals("", localeOfCode("fr").country)
        assertEquals("pt", localeOfCode("pt-rBR").language)
        assertEquals("BR", localeOfCode("pt-rBR").country)
        assertEquals("pt", localeOfCode("pt_BR").language)
        assertEquals("BR", localeOfCode("pt_BR").country)
    }

    @Test
    fun `every language this app really ships maps back to itself`() {
        // The round trip the stored setting rests on: a language is applied by Android, read back
        // here on the next start, and must not be rewritten as a different one. Against the real
        // res/values-* set, so a translation landing tomorrow is covered without touching this file.
        val shipped = BuildConfig.DETECTED_LOCALES.toList()
        assertEquals(true, shipped.isNotEmpty(), "no locales were detected at build time")
        shipped.forEach { code ->
            val tag = localeOfCode(code).toLanguageTag()
            assertEquals(code, localeCodeForTag(tag, shipped), "$code came back wrong through $tag")
        }
    }

    private companion object {
        /** A stated set for the cases above: two plain languages, one with a region beside it, one
         *  known only by its regions, and the two Java spells its own way. */
        val OFFERED = listOf(
            SYSTEM_LANGUAGE,
            "en", "fr", "de", "pt", "pt-rBR", "zh-rCN", "zh-rTW", "in", "iw",
        )
    }
}
