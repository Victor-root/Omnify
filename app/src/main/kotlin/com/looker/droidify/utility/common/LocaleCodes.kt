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
