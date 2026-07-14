package com.looker.droidify.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import java.util.Locale

/**
 * An app's supported languages, for the detail screen's "supported languages" section. [reliable] is
 * true when [codes] are the app's real UI locales — confirmed either from an installed APK, or by
 * directly inspecting a not-yet-installed APK's own compiled resources; false when they're only an
 * approximation from store-listing metadata. Shared by the F-Droid catalogue and external app detail
 * screens, so the two present this identically.
 *
 * [sourceCrossChecked] is true when [codes] is the default-English-only result (see
 * [SupportedLanguagesSection]'s own doc comment on that case) *and* a second, independent check of the
 * app's own published source code (see [com.looker.droidify.compose.appDetail.AppDetailViewModel]'s
 * source-locale cross-check) agrees no further language exists — upgrading the experimental caveat to
 * an actual confirmation instead of just repeating the same single-method answer. Always false for a
 * result with more than one language: the cross-check's only job is to catch a language the first
 * method missed, and finding one already speaks for itself (folded straight into [codes]).
 *
 * [isLoading] is true while a more reliable answer than [codes]'s current one is still being resolved
 * in the background (the not-yet-installed APK check, or its own source-code cross-check) — the
 * catalogue's [codes]/[reliable] can otherwise visibly flip more than once as each tier resolves (a
 * store-listing guess, then a confirmed answer, then possibly an upgraded caveat), which reads as the
 * section just being wrong the first time rather than still working. [SupportedLanguagesSection] shows
 * a spinner instead of any text while this holds, so whatever's shown once it clears is final.
 */
data class SupportedLanguages(
    val codes: List<String> = emptyList(),
    val reliable: Boolean = false,
    val sourceCrossChecked: Boolean = false,
    val isLoading: Boolean = false,
)

/**
 * Collapsible "supported languages" section. When [SupportedLanguages.reliable] the header states
 * definitely whether the device's language is translated ("Français : traduit"); otherwise (only an
 * approximation) it shows a caveat instead of asserting anything, so it can never wrongly claim a
 * language isn't translated. The full list expands below, device language first and ticked.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SupportedLanguagesSection(languages: SupportedLanguages) {
    var expanded by remember { mutableStateOf(false) }
    val deviceLocale = remember { Locale.getDefault() }
    val reliable = languages.reliable
    val localeCodes = languages.codes
    val languageList = remember(localeCodes) {
        localeCodes
            .map { code ->
                val loc = Locale.forLanguageTag(code.replace('_', '-'))
                val display = loc.getDisplayName(loc)
                    .replaceFirstChar { it.uppercase(loc) }
                    .ifBlank { code }
                Triple(code, display, loc.language.isNotEmpty() && loc.language == deviceLocale.language)
            }
            // Device language first, then alphabetical by display name.
            .sortedWith(
                compareByDescending<Triple<String, String, Boolean>> { it.third }
                    .thenBy { it.second.lowercase(deviceLocale) },
            )
    }
    val deviceSupported = languageList.any { it.third }
    val deviceName = deviceLocale.getDisplayLanguage(deviceLocale)
        .replaceFirstChar { it.uppercase(deviceLocale) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusFill(RoundedCornerShape(12.dp), debugLabel = "supported-languages-row")
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            // Top, not centre: the caveat/confirmation text below the title can wrap to several lines
            // (e.g. "Only English was found, confirmed by independently checking…"), and centring the
            // whole row then put the count badge and chevron awkwardly beside the caption's middle line
            // instead of beside the title — confirmed by a real screenshot. Top keeps them level with the
            // title regardless of how long the caption underneath grows.
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                // The same translate glyph as the top bar's Translate button, so the languages section
                // reads as "translations" at a glance.
                imageVector = Icons.Filled.Translate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.supported_languages),
                    style = MaterialTheme.typography.titleMedium,
                )
                val onlyDefaultEnglish = localeCodes.size == 1 && localeCodes.single().equals("en", ignoreCase = true)
                if (languages.isLoading) {
                    // A more reliable answer than whatever's in [codes] right now is still being
                    // resolved in the background (see SupportedLanguages' own doc comment on
                    // isLoading) — a loading bar instead of that in-between text, so nothing shown
                    // here ever has to be walked back once the real answer comes in.
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                } else if (reliable && onlyDefaultEnglish && languages.sourceCrossChecked) {
                    // A second, independent check (the app's own published source code, not its
                    // compiled resources) agrees no further language exists — see SupportedLanguages'
                    // own doc comment on sourceCrossChecked. Two independent methods agreeing is worth
                    // actually saying, instead of just repeating the single-method caveat below.
                    Text(
                        text = stringResource(R.string.language_only_default_confirmed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (reliable && onlyDefaultEnglish) {
                    // The unqualified default resource config always decodes as "en" (see
                    // ApkResourceLocales' own doc comment), so a result of exactly ["en"] and nothing
                    // else is never a genuine "zero languages" case — it's "nothing beyond the
                    // fallback baseline was found," which is the one place this detection is still
                    // known to sometimes fall short (a real translation present in the APK but missed
                    // by the heuristics that separate it from a bundled library's own strings) —
                    // flagged here rather than asserted as fact. Once at least one language beyond
                    // that baseline is found, there's no comparable mechanism by which that finding
                    // itself would be wrong, so no caveat is shown then.
                    Text(
                        text = stringResource(R.string.language_only_default_experimental),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (reliable) {
                    // Real UI languages, from an installed APK or a directly-inspected one: state it
                    // definitely.
                    Text(
                        text = stringResource(
                            if (deviceSupported) {
                                R.string.language_supported_FORMAT
                            } else {
                                R.string.language_not_supported_FORMAT
                            },
                            deviceName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (deviceSupported) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else {
                    // Only an approximation: don't assert translated/not, just flag it as such.
                    Text(
                        text = stringResource(R.string.language_from_store_listing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!languages.isLoading) {
                // Held back while still loading too: the count itself (not just which caveat/text is
                // shown) can still change once a more reliable tier resolves.
                CountBadge(languageList.size)
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (expanded) {
            languageList.forEach { (_, display, isDevice) ->
                // Only highlight/tick the device language when the data is reliable, so an
                // approximation never implies a certainty it doesn't have.
                val highlight = isDevice && reliable
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = display,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (highlight) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (highlight) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
