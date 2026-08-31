package com.looker.droidify.utility.common

import java.util.Locale

/**
 * Drops the leading word from a translation file's locale code when that word isn't part of the locale.
 *
 * Most conventions name such a file after its locale ("fr.json", "pt_BR.ini"), but several name it
 * after the string set instead and put the locale last. Flutter's own `gen-l10n` default is exactly
 * that: `lib/l10n/app_en.arb`, `app_fr.arb`, one per language. Read as a locale, "app" takes the
 * language's place and the real language is left standing in the region's, so Every Door's 43
 * translations came out as "App (France)", "App (DA)", and the app claimed not to be translated into
 * the very languages it was listing.
 *
 * Deliberately conservative, because "xx_yy" really is ambiguous: the first word is dropped only when
 * the second subtag can be neither a region nor a script, the first is not a language, and the second
 * is. Everything else comes back untouched, so `pt_BR`, `pt_br`, `fil_PH`, `zh-Hant-TW`, `es-419` and
 * `en@pirate` all keep the reading they already had.
 */
internal fun withoutNonLocalePrefix(code: String): String {
    val parts = code.split('-', '_')
    if (parts.size < 2) return code
    val first = parts[0]
    val second = parts[1]
    if (second.isRegionOrScriptShaped() || first.isKnownLanguage() || !second.isKnownLanguage()) {
        return code
    }
    return parts.drop(1).joinToString("-")
}

/**
 * [withoutNonLocalePrefix] applied to every code read from one directory at once, which settles a case
 * no single code can.
 *
 * "app_DE" on its own is genuinely ambiguous. A region in second place normally means the first subtag
 * really is the language, which is the reading [withoutNonLocalePrefix] keeps, and "app" is a real
 * language code besides. Seen beside its siblings it stops being ambiguous: a word that repeats
 * unchanged across a whole translation set while the subtag after it changes is the name of the set,
 * not a language, because no directory holds one language in several regions and nothing else.
 *
 * So the prefix is dropped from the set only when it is the same in every name, is not itself a known
 * language, and every subtag behind it is one, with at least two different ones. `pt_BR`/`pt_PT` keeps
 * its "pt" on the second condition, `ceb_ID`/`ceb_PH` its "ceb" on the third. Anything the set as a
 * whole cannot settle falls back to reading each name on its own, exactly as before.
 *
 * The language is lowercased on the way out, since it was written where a region belongs and carries
 * that spelling with it.
 */
internal fun withoutNonLocalePrefixes(codes: List<String>): List<String> {
    val parts = codes.map { it.split('-', '_') }
    val prefixes = parts.mapTo(mutableSetOf()) { it.firstOrNull()?.lowercase().orEmpty() }
    val seconds = parts.map { it.getOrNull(1) }
    val sharedPrefix = prefixes.singleOrNull()?.takeIf { it.isNotEmpty() && !it.isKnownLanguage() }
    val everySecondIsALanguage = seconds.all { it != null && it.isKnownLanguage() }
    val distinctSeconds = seconds.filterNotNull().mapTo(mutableSetOf()) { it.lowercase() }.size
    if (sharedPrefix == null || !everySecondIsALanguage || distinctSeconds < 2) {
        return codes.map(::withoutNonLocalePrefix)
    }
    return parts.map { code ->
        code.drop(1)
            .mapIndexed { index, part -> if (index == 0) part.lowercase() else part }
            .joinToString("-")
    }
}

/** A region subtag is two uppercase letters or three digits, a script four letters. Any of those in
 *  second place means the first subtag really is the language and nothing should be dropped. */
private fun String.isRegionOrScriptShaped(): Boolean = when (length) {
    2 -> all { it.isUpperCase() }
    3 -> all { it.isDigit() }
    4 -> all { it.isLetter() }
    else -> false
}

/** Every language code Java knows, which is ISO 639-1's two-letter set (its superseded spellings, `iw`
 *  and `in` among them, included). A three-letter code is therefore never "known" here, which is what
 *  keeps this from answering on `fil`/`ceb` either way: those only ever appear alone or before a real
 *  region, both of which are already left alone above. */
private val ISO_LANGUAGES: Set<String> = Locale.getISOLanguages().toSet()

private fun String.isKnownLanguage(): Boolean = lowercase() in ISO_LANGUAGES

/** The stored language meaning "whatever the device is set to", rather than one the user picked. */
internal const val SYSTEM_LANGUAGE = "system"

/**
 * A resource-directory locale code as a [Locale]. Three spellings reach this: the plain language
 * ("fr"), Android's own resource form ("pt-rBR"), and the underscored one older settings were stored
 * with ("pt_BR").
 *
 * One parser, where the settings view model and the settings screen had each written their own and
 * [localeCodeForTag] below would have made a third. Three readings of one format is three chances
 * for them to disagree.
 *
 * Split, where both of those sliced at fixed offsets. A three-letter language with a region beside it
 * came out of that as its first two letters, a language that exists nowhere, and this app already
 * ships four three-letter languages (ckb, got, jbo, ryu) that a region could land next to.
 */
// Locale.of(...), the suggested replacement, needs Java 19 and isn't backported by this project's core
// library desugaring: calling it would crash below that on this app's minSdk 23 devices.
@Suppress("DEPRECATION")
internal fun localeOfCode(code: String): Locale {
    val parts = code.split('-', '_')
    // "rBR" is the resource spelling of the region "BR"; the underscored form already reads "BR". A
    // region is uppercase in both, so dropping a leading lowercase r can never eat a real letter.
    val region = parts.getOrNull(1)?.removePrefix("r").orEmpty()
    return Locale(parts[0], region)
}

/**
 * Which of the [available] codes the language tag [tag] means, or [SYSTEM_LANGUAGE] when nothing is
 * set and when the app ships nothing for it.
 *
 * Android names a locale with a language tag ("pt-BR") while this list is spelled the way the
 * resource directories are ("pt-rBR"), so the two are compared as [Locale]s and not as text. That is
 * also what settles the two languages Java still spells its own way internally, Indonesian and
 * Hebrew: as plain strings, Android's "id" and this app's "in" would never match at all.
 *
 * The region is preferred when the app ships it, then the plain language, then any other region of
 * it: a device set to Portuguese (Portugal) is better served the Brazilian Portuguese this app does
 * have than the English it would otherwise fall back to.
 */
internal fun localeCodeForTag(tag: String?, available: List<String>): String {
    // A tag that is missing, blank, or not a language at all parses to no language, and nothing the
    // app ships has none either, so it matches nothing and the device is left to decide. That covers
    // [SYSTEM_LANGUAGE] itself, which sits in this list and is a word rather than a locale.
    val wanted = Locale.forLanguageTag(tag.orEmpty().replace('_', '-'))
    return available
        .map { it to localeOfCode(it) }
        .filter { (_, locale) -> locale.language == wanted.language }
        .minByOrNull { (_, locale) ->
            when {
                locale.country == wanted.country -> 0
                locale.country.isEmpty() -> 1
                else -> 2
            }
        }
        ?.first
        ?: SYSTEM_LANGUAGE
}
