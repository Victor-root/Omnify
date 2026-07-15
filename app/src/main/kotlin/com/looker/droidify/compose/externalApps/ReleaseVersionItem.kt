package com.looker.droidify.compose.externalApps

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

/**
 * One release in the external app's version list — the same shape and chips (SUGGESTED/INSTALLED) as
 * the F-Droid catalogue's [com.looker.droidify.compose.appDetail.components.PackageItem], but built
 * against [Release]/[apkName]/[apkSize] instead of a catalogue [com.looker.droidify.data.model.Package],
 * since a GitHub/GitLab/Codeberg release carries no repo or SDK range metadata to show, and only
 * GitHub/Codeberg (not GitLab) expose a file size.
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
    // Download/install progress for THIS specific release, when the user picked it from the list —
    // shown inline instead of the file name/size line, so progress is visible right where it was
    // tapped instead of only in the hero card (out of view once scrolled down to this list). Both
    // null/false means this row isn't the active one.
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
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
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
                // A proper chip, not plain text below — so it reads at a glance in a repo that mixes
                // stable and pre-release tags, instead of being easy to miss.
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = apkName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
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
