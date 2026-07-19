package com.looker.droidify.compose.externalApps

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.components.CompactInstallProgressRow
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.tvFocusOutline
import com.looker.droidify.external.Release
import com.looker.droidify.network.DataSize
import com.looker.droidify.utility.apk.ApkBinaryManifest
import com.looker.droidify.utility.common.sdkName
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * One release in the external app's version list — the same shape, chips (SUGGESTED/INSTALLED) and
 * trailing date/size column as the F-Droid catalogue's
 * [com.looker.droidify.compose.appDetail.components.PackageItem], but built against
 * [Release]/[apkName]/[apkSize]/[apkDate] instead of a catalogue [com.looker.droidify.data.model.Package],
 * since a GitHub/GitLab/Codeberg release carries no repo or SDK range metadata to show, and only
 * GitHub/Codeberg (not GitLab) expose a file size or an upload date.
 */
@Composable
fun ReleaseVersionItem(
    release: Release,
    apkName: String?,
    apkSize: Long?,
    isSuggested: Boolean,
    isInstalled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // ISO-8601 upload time of the selected APK (null on providers/releases that don't expose one) —
    // shown top-right the same way the catalogue's PackageItem shows a version's added date.
    apkDate: String? = null,
    // Declared min/target SDK, once fetched from the APK's own manifest (see ExternalAppsViewModel.
    // loadSdkInfo) — null while unrequested/in flight, or when the fetch found nothing.
    sdkInfo: ApkBinaryManifest.UsesSdk? = null,
    // Download/install progress for THIS specific release, when the user picked it from the list —
    // shown inline instead of the file name line, so progress is visible right where it was tapped
    // instead of only in the hero card (out of view once scrolled down to this list). Both null/false
    // means this row isn't the active one.
    downloadStatus: DownloadStatus? = null,
    installing: Boolean = false,
    onCancel: (() -> Unit)? = null,
) {
    val cardShape = MaterialTheme.shapes.large
    // The border lives on this outer Box, not inside Surface's own modifier: Surface paints its
    // background as part of its internal implementation, which chains after whatever modifier
    // it's given — a border passed straight into Surface's modifier ended up painted OVER by
    // that internal fill and was never actually visible. Drawn on a wrapping Box instead, it's
    // guaranteed to render on top of the Surface, not underneath it.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // Same treatment as the hero card, only for the one suggested release — that's the
            // pick this screen wants to draw the eye to, not every entry in a long version list,
            // which would drown the highlight out.
            .then(if (isSuggested) premiumCardBorder(cardShape) else Modifier),
    ) {
        Surface(
            shape = cardShape,
            // White for every card, suggested included — the border above (not a grey fill) is
            // what now marks the suggested one, matching the hero card's own white-plus-border
            // look.
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                // TV only: visible focus ring (no-op on touch).
                .tvFocusOutline(cardShape)
                .clickable(onClick = onClick, role = Role.Button),
        ) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = release.tag,
                        style = MaterialTheme.typography.titleSmall,
                        // Take only the space left after the chips (fill = false) so a very long tag
                        // wraps instead of crushing the chips to one letter per line.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isSuggested) {
                        VersionChip(
                            text = stringResource(R.string.suggested),
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    if (isInstalled) {
                        VersionChip(
                            text = stringResource(R.string.installed),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    // A proper chip, not plain text below — so it reads at a glance in a repo that
                    // mixes stable and pre-release tags, instead of being easy to miss.
                    if (release.isPrerelease) {
                        VersionChip(
                            text = stringResource(R.string.external_prerelease_label),
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                if ((downloadStatus != null || installing) && onCancel != null) {
                    CompactInstallProgressRow(
                        status = downloadStatus,
                        installing = installing,
                        onCancel = onCancel,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else if (apkName != null) {
                    Text(
                        text = apkName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val target = sdkInfo?.targetSdkVersion
                    val min = sdkInfo?.minSdkVersion
                    if (target != null && min != null) {
                        Text(
                            text = stringResource(R.string.label_sdk_version, sdkLabel(target), sdkLabel(min)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            if (apkDate != null || apkSize != null) {
                Column(horizontalAlignment = Alignment.End) {
                    if (apkDate != null) {
                        val context = LocalContext.current
                        val date = remember(apkDate) { formatReleaseDate(context, apkDate) }
                        if (date != null) {
                            Text(
                                text = date,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    if (apkSize != null) {
                        Text(
                            text = DataSize(apkSize).toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
        }
    }
}

/** Parses [iso] (an [Release.apkUpdatedAt]-shaped ISO-8601 timestamp) into the same short localised
 *  date format the catalogue's PackageItem uses, so a version row reads identically whether it came
 *  from a repo or a tracked GitHub/Codeberg source. Null on anything that fails to parse rather than
 *  showing a raw timestamp or crashing. */
private fun formatReleaseDate(context: Context, iso: String): String? {
    val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val dateTime = LocalDateTime.ofInstant(instant, ZoneOffset.UTC)
    return try {
        dateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    } catch (_: Exception) {
        DateFormat.getDateFormat(context).format(Date.from(instant))
    }
}

/** [sdkName]'s label for [version], falling back to the raw number for one outside that known table —
 *  unlike the catalogue's index-sourced value, this comes from parsing an arbitrary remote APK's
 *  manifest, so an unrecognised (future, or malformed) level is a real possibility, not just theoretical. */
private fun sdkLabel(version: Int): String = sdkName[version] ?: version.toString()

@Composable
private fun VersionChip(text: String, container: Color, content: Color) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .background(container, shape = CircleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
