package com.looker.droidify.compose.externalApps

import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.components.CompactInstallProgressRow
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusOutline
import com.looker.droidify.external.ApkVariant
import com.looker.droidify.external.Release
import com.looker.droidify.external.ReleaseAsset
import com.looker.droidify.external.apkVariants
import com.looker.droidify.external.compareVersionStrings
import com.looker.droidify.external.releaseVersionLabel
import com.looker.droidify.external.selectApkAsset
import com.looker.droidify.network.DataSize
import com.looker.droidify.utility.apk.ApkBinaryManifest
import com.looker.droidify.utility.common.sdkName
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** What a [ReleaseVersionItem] shows about [release], worked out once per release rather than on every
 *  recomposition of the list: the APK this device would get from it (one [selectApkAsset] pick shared by
 *  every field), whether that is the version installed, and the release's other APKs to choose from. */
class ReleaseVersionRow(
    val release: Release,
    val apkName: String?,
    val apkUrl: String?,
    val apkSize: Long?,
    val apkDate: String?,
    val isInstalled: Boolean,
    /** Every APK of the release when it has more than one, empty otherwise. */
    val variants: List<ApkVariant>,
    /** The APK Omnify picks with no filter, marked as its own choice among [variants]. */
    val autoApkName: String?,
)

/**
 * [ReleaseVersionRow]s for [releases] under an app's [apkFilter], for both the phone and TV version lists.
 * [installedVersion] is the real on-device versionName, not the possibly stale ExternalApp.installedTag:
 * the same self-healing fallback ExternalApp.hasUpdateGiven relies on for the update banner, used when
 * [installedTag] (ExternalApp.ownInstalledTag) doesn't name the installed release exactly. Meant to be
 * remembered on these inputs.
 */
fun releaseVersionRows(
    releases: List<Release>,
    apkFilter: String?,
    installedVersion: String?,
    installedTag: String?,
): List<ReleaseVersionRow> = releases.map { release ->
    val apk = selectApkAsset(release.assets, filter = apkFilter, releaseTag = release.tag)
    val variants = apkVariants(release.assets).takeIf { it.size > 1 }.orEmpty()
    ReleaseVersionRow(
        release = release,
        apkName = apk?.name,
        apkUrl = apk?.downloadUrl,
        apkSize = apk?.size,
        apkDate = apk?.updatedAt,
        isInstalled = if (installedTag != null) {
            release.tag == installedTag
        } else {
            installedVersion != null &&
                compareVersionStrings(releaseVersionLabel(apk?.name, release.tag), installedVersion) == 0
        },
        variants = variants,
        autoApkName = if (variants.isEmpty()) {
            null
        } else {
            selectApkAsset(release.assets, filter = null, releaseTag = release.tag)?.name
        },
    )
}

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
    // The release's APKs to choose from (see ReleaseVersionRow.variants): when there are any, the card
    // ends on a line unfolding them, and picking one calls [onPickFile].
    variants: List<ApkVariant> = emptyList(),
    autoApkName: String? = null,
    filesExpanded: Boolean = false,
    onToggleFiles: () -> Unit = {},
    onPickFile: (ReleaseAsset) -> Unit = {},
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
            // TV only: visible focus ring (no-op on touch), on the same bounds as the card's own border.
            // First in the chain, because a modifier earlier in the chain draws after (over) the ones
            // below it: the ring then covers the border instead of letting it show on top.
            .tvFocusOutline(cardShape)
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
                .clip(cardShape),
        ) {
            Column {
                // The version itself is what a tap installs; the files below it are chosen on their own.
                Row(
                    modifier = Modifier
                        .clickable(onClick = onClick, role = Role.Button)
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        // The chips follow the version on its line while they fit, and go under it once they
                        // don't: three of them in French left the version one letter per line.
                        FlowRow(
                            itemVerticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                // releaseVersionLabel reads identically to the hero card's own "Version" stat
                                // (see ExternalAppDetailScreen's heroVersion) instead of the raw server-side
                                // tag: usually a dotted number pulled from the APK's file name, falling back to
                                // one pulled from the tag itself for a project whose file name is fixed across
                                // every release (confirmed real: RivoPhoneApp's "app-release.apk").
                                text = releaseVersionLabel(apkName, release.tag),
                                style = MaterialTheme.typography.titleSmall,
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
                            if (release.isPrerelease) PrereleaseBadge()
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
                if (variants.isNotEmpty()) {
                    ReleaseFiles(
                        release = release,
                        variants = variants,
                        selectedName = apkName,
                        autoName = autoApkName,
                        expanded = filesExpanded,
                        onToggle = onToggleFiles,
                        onPick = onPickFile,
                    )
                }
            }
        }
    }
}

/**
 * The end of a release's card when it ships several APKs: a line unfolding them, then each one with what
 * it is built for, the one Omnify installs for this release selected and its own pick marked. A file
 * this device can't run is shown, so the list is the release's whole, but can't be picked.
 */
@Composable
private fun ReleaseFiles(
    release: Release,
    variants: List<ApkVariant>,
    selectedName: String?,
    autoName: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: (ReleaseAsset) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tvFocusFill(MaterialTheme.shapes.small)
            .clickable(
                onClickLabel = stringResource(if (expanded) R.string.release_files_hide else R.string.release_files_show),
                role = Role.Button,
                onClick = onToggle,
            )
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = pluralStringResource(R.plurals.release_files_COUNT, variants.size, variants.size),
            style = MaterialTheme.typography.labelLarge,
            color = accent,
            modifier = Modifier.weight(1f),
        )
        val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "releaseFilesChevron")
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.rotate(turn),
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            val anyAbi = variants.any { it.abi != null }
            variants.forEach { variant ->
                ReleaseFileRow(
                    variant = variant,
                    label = apkVariantLabel(variant, anyAbi),
                    note = apkVariantNote(variant, anyAbi),
                    selected = variant.asset.name == selectedName,
                    isAuto = variant.asset.name == autoName,
                    onClick = { onPick(variant.asset) },
                )
            }
            val hidden = release.assets.size - variants.size
            if (hidden > 0) {
                Text(
                    text = pluralStringResource(R.plurals.release_files_hidden_COUNT, hidden, hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ReleaseFileRow(
    variant: ApkVariant,
    label: String,
    note: String?,
    selected: Boolean,
    isAuto: Boolean,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    // Material's disabled look, for a file this device can't run.
    val primaryText = if (variant.compatible) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    val secondaryText = if (variant.compatible) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(colors.primary.copy(alpha = 0.08f), shape) else Modifier)
            .tvFocusFill(shape)
            .selectable(
                selected = selected,
                enabled = variant.compatible,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = variant.compatible)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(
                itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.titleSmall, color = primaryText)
                if (isAuto) {
                    VersionChip(
                        text = stringResource(R.string.release_file_auto),
                        container = colors.primaryContainer,
                        content = colors.onPrimaryContainer,
                    )
                }
            }
            if (note != null) {
                Text(text = note, style = MaterialTheme.typography.bodySmall, color = secondaryText)
            }
            // Cut in the middle: what tells a release's files apart is mostly near the end of their names.
            Text(
                text = variant.asset.name,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryText,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        variant.asset.size?.let { size ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = DataSize(size).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = secondaryText,
            )
        }
    }
}

/** What [variant] is, in words: its processor ("arm64-v8a", or "Universal" among files built for
 *  given processors) and its own words ("freenet"), the file name when neither says anything. */
@Composable
internal fun apkVariantLabel(variant: ApkVariant, anyAbi: Boolean): String {
    val processor = variant.abi ?: if (anyAbi) stringResource(R.string.release_file_universal) else null
    return (listOfNotNull(processor) + variant.words).joinToString(" · ").ifEmpty { variant.asset.name }
}

@Composable
private fun apkVariantNote(variant: ApkVariant, anyAbi: Boolean): String? = when {
    !variant.compatible -> stringResource(R.string.release_file_incompatible)
    variant.abi == null -> if (anyAbi) stringResource(R.string.release_file_any_processor) else null
    variant.abi == Build.SUPPORTED_ABIS.firstOrNull() -> stringResource(R.string.release_file_for_device)
    else -> stringResource(R.string.release_file_compatible)
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

/** Marks a pre-release, in the version list and on the app's card when it is the version offered.
 *  On the card it sits beside the capability badges
 *  ([com.looker.droidify.compose.components.CapabilityBadges]) and takes their size ([onCard]). */
@Composable
internal fun PrereleaseBadge(onCard: Boolean = false) {
    VersionChip(
        text = stringResource(R.string.external_prerelease_label),
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
        onCard = onCard,
    )
}

/** A version's chip, no taller than the version's own title line in the list ([onCard] aside). */
@Composable
private fun VersionChip(text: String, container: Color, content: Color, onCard: Boolean = false) {
    Text(
        text = text.uppercase(),
        style = if (onCard) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
        color = content,
        modifier = Modifier
            .background(container, shape = CircleShape)
            .padding(horizontal = if (onCard) 10.dp else 8.dp, vertical = if (onCard) 4.dp else 2.dp),
    )
}
