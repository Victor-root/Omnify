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
import androidx.compose.material3.Icon
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
 */
data class SupportedLanguages(
    val codes: List<String> = emptyList(),
    val reliable: Boolean = false,
)

/**
 * Collapsible "supported languages" section. When [SupportedLanguages.reliable] the header states
 * definitely whether the device's language is translated ("Français : traduit"); otherwise (only an
 * approximation) it shows a caveat instead of asserting anything, so it can never wrongly claim a
 * language isn't translated. The full list expands below, device language first and ticked.
 */
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
            verticalAlignment = Alignment.CenterVertically,
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
                if (reliable && onlyDefaultEnglish) {
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
            Text(
                text = languageList.size.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
