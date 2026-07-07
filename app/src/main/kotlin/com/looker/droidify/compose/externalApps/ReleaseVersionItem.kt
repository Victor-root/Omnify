package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.looker.droidify.compose.components.tvFocusOutline
import com.looker.droidify.external.Release

/**
 * One release in the external app's version list — the same shape and chips (SUGGESTED/INSTALLED) as
 * the F-Droid catalogue's [com.looker.droidify.compose.appDetail.components.PackageItem], but built
 * against [Release]/[apkName] instead of a catalogue [com.looker.droidify.data.model.Package], since a
 * GitHub/GitLab/Codeberg release carries no repo, SDK range or upload-size metadata to show.
 */
@Composable
fun ReleaseVersionItem(
    release: Release,
    apkName: String?,
    isSuggested: Boolean,
    isInstalled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isSuggested) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            // TV only: visible focus ring (no-op on touch).
            .tvFocusOutline(MaterialTheme.shapes.large)
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
            }
            if (apkName != null) {
                Text(
                    text = apkName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (release.isPrerelease) {
                Text(
                    text = stringResource(R.string.external_prerelease_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
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
