package com.looker.droidify.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading a translation's language out of its file name, for the cross-platform projects that keep
 * their strings in their own asset files instead of Android's `res/values-xx/`.
 *
 * Every case below is a real naming convention rather than an invention, because the shapes genuinely
 * collide: "pt_BR" is one language with a region, "app_fr" is one language with a word in front of it,
 * and nothing but the subtags themselves tells them apart. Reading the second as the first is what put
 * "App (France)" and "App (DA)" on Every Door's page, and had it claim not to be translated into
 * French while listing French.
 */
class SourceLocaleTest {

    @Test
    fun `a file named after its string table reports the language, not the table`() {
        // Flutter's own gen-l10n default, verbatim: lib/l10n/app_<lang>.arb, one per language.
        assertEquals("fr", localeFromI18nAssetPath("lib/l10n/app_fr.arb"))
        assertEquals("en", localeFromI18nAssetPath("lib/l10n/app_en.arb"))
        assertEquals("da", localeFromI18nAssetPath("lib/l10n/app_da.arb"))
        assertEquals("pt-BR", localeFromI18nAssetPath("lib/l10n/app_pt_BR.arb"))
    }

    @Test
    fun `a file named after its locale is read as before`() {
        assertEquals("fr", localeFromI18nAssetPath("assets/i18n/fr.json"))
        assertEquals("pt-BR", localeFromI18nAssetPath("assets/translations/pt_BR.json"))
        assertEquals("es-419", localeFromI18nAssetPath("assets/i18n/es-419.json"))
        // slang's double extension, and a script subtag consumed whole rather than mistaken for a region.
        assertEquals("de", localeFromI18nAssetPath("assets/i18n/de.i18n.json"))
        assertEquals("zh-TW", localeFromI18nAssetPath("assets/i18n/zh-Hant-TW.json"))
    }

    @Test
    fun `a lowercase region is still a region`() {
        // The one genuinely ambiguous shape: "pt" is a language, so "br" behind it is its region and
        // not a language of its own, however it was capitalised.
        assertEquals("pt-BR", localeFromI18nAssetPath("assets/i18n/pt_br.json"))
    }

    @Test
    fun `a three-letter language keeps its region`() {
        // "fil" is a real language that the two-letter list can't confirm, so the decision has to rest
        // on "PH" looking like a region rather than on "fil" looking like a language.
        assertEquals("fil-PH", localeFromI18nAssetPath("lib/l10n/fil_PH.arb"))
        assertEquals("fil", localeFromI18nAssetPath("assets/i18n/fil.json"))
    }

    @Test
    fun `a word too long to pass for a language was never the problem`() {
        assertEquals("de", localeFromI18nAssetPath("assets/i18n/messages_de.properties"))
    }

    @Test
    fun `a file outside a translation folder is not a translation`() {
        assertNull(localeFromI18nAssetPath("src/main/fr.json"))
    }

    @Test
    fun `Rust's own module files are not locales`() {
        assertEquals("fr", localeFromI18nAssetPath("src/lang/fr.rs"))
        assertNull(localeFromI18nAssetPath("src/lang/mod.rs"))
    }
}
