package com.looker.droidify.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.data.model.Fingerprint
import com.looker.droidify.data.signerMismatch

/**
 * Collapsible "certificate" section, the same accordion shape as [SupportedLanguagesSection]: a header
 * expanding to one tappable row per certificate that copies its fingerprint to the clipboard.
 * [installedSigner] is the app's real, on-device signing certificate (lowercase hex, null when not
 * installed); [expectedSigners] is what the source declares for the version that would actually be
 * installed/updated to (a repository index entry, or a release APK's own signing block for an external
 * source). When both are known, the header states plainly whether they match, using the same
 * [signerMismatch] check every other install/update decision in the app already relies on, so a user who
 * has no idea what a fingerprint means still gets a clear answer instead of two walls of hex to compare
 * by eye. Callers gate on both being null themselves, exactly like [SupportedLanguagesSection]'s own
 * callers gate on an empty language list.
 */
@Composable
fun CertificateSection(
    installedSigner: String?,
    expectedSigners: Set<String>?,
    modifier: Modifier = Modifier,
) {
    val installed = remember(installedSigner) {
        installedSigner?.uppercase()?.let(::Fingerprint)?.takeIf { it.isValid }
    }
    val expected = remember(expectedSigners) {
        expectedSigners?.firstOrNull()?.uppercase()?.let(::Fingerprint)?.takeIf { it.isValid }
    }
    val entries = remember(installed, expected) {
        buildList {
            installed?.let { add(R.string.installed_certificate_title to it) }
            expected?.let { add(R.string.expected_certificate_title to it) }
        }
    }
    // Null (no verdict shown) whenever either side is still unknown, since an unconfirmed match is
    // worse than a missed one, the same rule signerMismatch's own doc comment establishes for every
    // other caller of it.
    val matches = remember(installedSigner, expectedSigners) {
        if (installedSigner == null || expectedSigners.isNullOrEmpty()) {
            null
        } else {
            !signerMismatch(installedSigner, expectedSigners)
        }
    }
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusFill(RoundedCornerShape(12.dp), debugLabel = "certificate-row")
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            // Top, not centre: matches SupportedLanguagesSection's own reasoning. The caption below the
            // title only appears once matches is known, and shouldn't shift the badge/chevron when it does.
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = when (matches) {
                    true -> Icons.Filled.Check
                    false -> Icons.Filled.ErrorOutline
                    null -> Icons.Filled.Security
                },
                contentDescription = null,
                tint = when (matches) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.certificate_section_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                when (matches) {
                    true -> Text(
                        text = stringResource(R.string.certificate_match_confirmed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    false -> Text(
                        text = stringResource(R.string.certificate_mismatch_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    null -> {}
                }
            }
            CountBadge(entries.size)
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (expanded) {
            entries.forEach { (titleRes, fingerprint) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusFill(RoundedCornerShape(12.dp), debugLabel = "certificate-entry")
                        .clickable { copyFingerprintToClipboard(context, fingerprint) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(titleRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = fingerprintContent(fingerprint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
