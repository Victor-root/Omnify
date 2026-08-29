package com.looker.droidify.utility.apk

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two halves, and the second matters as much as the first: translations that must be found (a real
 * app's languages are otherwise reported as "English only"), and directories that must NOT be read as
 * translations just because of what they are called.
 *
 * Both halves come from real releases rather than invention. PPSSPP keeps 45 translations as one
 * `.ini` file each under `assets/lang`, and puts not one app-authored string in `resources.arsc`, so
 * every one of its languages was invisible. Markor keeps its syntax-highlighting definitions in
 * `assets/highlight/languages/`, where `cpp.json` is shaped exactly like a locale code, and on a
 * 32-APK corpus that single directory was the only thing the earlier, name-only rule ever matched.
 */
class AssetLocaleDetectionTest {

    private fun detect(vararg entries: String) =
        RemoteApkLocaleReader.assetLocalesFromEntryNames(entries.toList()).sorted()

    @Test
    fun `a native app's per-locale ini files are found`() {
        // PPSSPP's own layout, down to the README sitting alongside the translations.
        val found = detect(
            "assets/lang/README.md",
            "assets/lang/en_US.ini",
            "assets/lang/fr_FR.ini",
            "assets/lang/ja_JP.ini",
            "assets/lang/zh_CN.ini",
        )

        assertEquals(listOf("en-US", "fr-FR", "ja-JP", "zh-CN"), found)
    }

    @Test
    fun `underscore and hyphen spellings are the same language, not two`() {
        // PPSSPP really does mix both (44 files use `_`, lt-LT uses `-`). Reporting a locale twice
        // under two spellings would put the same language on screen twice.
        val found = detect(
            "assets/lang/pt_BR.ini",
            "assets/lang/pt-BR.ini",
            "assets/lang/de_DE.ini",
        )

        assertEquals(listOf("de-DE", "pt-BR"), found)
    }

    @Test
    fun `a directory of syntax highlighting is not a directory of languages`() {
        // Markor, verbatim. `cpp` and `map` are locale-shaped; `java` and `python` are not. Believing
        // the directory's name alone is what made a Markdown editor claim to be translated into "cpp".
        val found = detect(
            "assets/highlight/languages/cpp.json",
            "assets/highlight/languages/java.json",
            "assets/highlight/languages/map.properties",
            "assets/highlight/languages/python.json",
        )

        assertEquals(emptyList(), found)
    }

    @Test
    fun `one locale-shaped file on its own proves nothing`() {
        // A single match is as likely to be a coincidence as a translation, and an app shipping
        // exactly one language has nothing to add: that language is whatever it was written in.
        assertEquals(emptyList(), detect("assets/i18n/fr.json", "assets/i18n/schema.js"))
        // And with nothing beside it either: the case above is also refused by the majority rule, so on
        // its own it would still pass with the minimum lowered to one. This is the minimum itself.
        assertEquals(emptyList(), detect("assets/i18n/fr.json"))
        assertEquals(emptyList(), detect("assets/lang/de.ini"))
    }

    @Test
    fun `a string-table name is dropped even when the language is written in capitals`() {
        // "app_DE" alone is genuinely ambiguous: a capitalised subtag in second place normally means
        // the first one is the language, and "app" is a real language code besides. Beside its
        // siblings it is not ambiguous at all, since no directory holds one language in several
        // regions and nothing else.
        val found = detect(
            "assets/translations/app_DE.json",
            "assets/translations/app_FR.json",
            "assets/translations/app_IT.json",
        )
        assertEquals(listOf("de", "fr", "it"), found)
    }

    @Test
    fun `a language in several regions keeps its language`() {
        // The other side of the rule above, and the reason it is not simply "drop a repeated word":
        // here the repeated subtag really is the language.
        assertEquals(
            listOf("pt-BR", "pt-PT"),
            detect("assets/translations/pt_BR.json", "assets/translations/pt_PT.json"),
        )
        // And where the second subtag is no language at all, nothing is dropped either: "ceb" is the
        // language, "PH" and "ID" are where it is spoken.
        assertEquals(
            listOf("ceb-ID", "ceb-PH"),
            detect("assets/lang/ceb_ID.ini", "assets/lang/ceb_PH.ini"),
        )
    }

    @Test
    fun `a repeated word only gives itself away when the language behind it varies`() {
        // Two files of one language in two formats. The word repeats, but so does everything after it,
        // so nothing here says which of the two is the language: "fil" is a language, and dropping it
        // would report Indonesian for a set of Filipino translations.
        assertEquals(
            listOf("fil-ID"),
            detect("assets/lang/fil_ID.json", "assets/lang/fil_ID.po"),
        )
    }

    @Test
    fun `words that differ from one file to the next are not string-table names`() {
        // The rule reads a word that repeats across the whole set. Where each name starts with
        // something different, there is no such word, and these are two ordinary locale codes that
        // happen not to be two-letter ones.
        assertEquals(
            listOf("fil-ID", "pt-BR"),
            detect("assets/lang/fil_ID.json", "assets/lang/pt_BR.json"),
        )
    }

    @Test
    fun `translations still win when a few odd files sit beside them`() {
        // The rule is a majority, not purity: a real translation directory routinely carries a README,
        // an index, or a template alongside the translations themselves.
        val found = detect(
            "assets/translations/en.json",
            "assets/translations/fr.json",
            "assets/translations/de.json",
            "assets/translations/README.md",
            "assets/translations/index.js",
        )

        assertEquals(listOf("de", "en", "fr"), found)
    }

    @Test
    fun `a Flutter app's translation assets are still found`() {
        // The case this detection was originally built for, kept covered while the shape widened.
        val found = detect(
            "assets/flutter_assets/assets/translations/en.json",
            "assets/flutter_assets/assets/translations/fr.json",
            "assets/flutter_assets/assets/translations/zh-Hant-TW.json",
        )

        assertEquals(listOf("en", "fr", "zh-Hant-TW"), found)
    }

    @Test
    fun `a set named after its string table reports the language, not the table`() {
        // Flutter's own gen-l10n names every file after the template rather than the locale, so the
        // word in front is not part of the code. Read as one, it takes the language's place and leaves
        // the language standing in the region's, which is how an app came to list "App (France)".
        val found = detect(
            "assets/translations/app_en.json",
            "assets/translations/app_fr.json",
            "assets/translations/app_pt_BR.json",
        )

        assertEquals(listOf("en", "fr", "pt-BR"), found)
    }

    @Test
    fun `a Chromium locale bundle is still found`() {
        val found = detect(
            "assets/locales/en-US.pak",
            "assets/locales/fr.pak",
            "assets/locales/es-419.pak",
            "assets/locales/resources.pak",
            "assets/locales/chrome_100_percent.pak",
        )

        assertEquals(listOf("en-US", "es-419", "fr"), found)
    }

    @Test
    fun `a single-language config split still reports its one language`() {
        // The reason Chromium's bundles are matched on their own terms rather than by the majority
        // rule: an Android App Bundle config split (split_config.fr.apk) holds exactly one .pak, and
        // one file is all the evidence there will ever be. Judging it by its neighbours would report
        // nothing at all for every split-installed browser.
        assertEquals(listOf("fr"), detect("assets/locales/fr.pak", "AndroidManifest.xml"))
    }

    @Test
    fun `game data named like locale codes is left alone`() {
        // Dolphin ships ~970 per-game config files under assets/Sys/GameSettings/, plenty of them
        // three characters long. Nothing about that directory says translations, so nothing is read
        // from it however locale-shaped the names look.
        val found = detect(
            "assets/Sys/GameSettings/GXX.ini",
            "assets/Sys/GameSettings/RMC.ini",
            "assets/Sys/GameSettings/SOU.ini",
        )

        assertEquals(emptyList(), found)
    }

    @Test
    fun `wordlists filed under their language are left alone`() {
        // FairEmail's BIP39 wordlists are named exactly like languages and are not translations of
        // the app. Their directory doesn't claim to hold translations, which is what keeps them out.
        val found = detect(
            "assets/bip39/en.txt",
            "assets/bip39/fr.txt",
            "assets/bip39/ja.txt",
        )

        assertEquals(emptyList(), found)
    }

    @Test
    fun `an empty APK yields nothing rather than failing`() {
        assertEquals(emptyList(), detect())
    }
}
