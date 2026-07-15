package com.looker.droidify.compose.appDetail.components

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus
import com.looker.droidify.compose.components.CompactInstallProgressRow
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.tvFocusOutline
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Repo
import com.looker.droidify.utility.common.sdkName
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun PackageItem(
    item: Package,
    repo: Repo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    // The one version this screen wants to draw the eye to (typically "suggested") — white like
    // every other row, but with the same gradient border as the hero card (see premiumCardBorder)
    // instead of the old flat grey fill, so it doesn't drown out in a long version list.
    highlighted: Boolean = false,
    // Download/install progress for THIS specific version, when the user picked it from the list —
    // shown inline instead of the repo/SDK detail lines, so progress is visible right where it was
    // tapped instead of only in the hero card (out of view once scrolled down to this list). Both null/
    // false means this row isn't the active one.
    downloadStatus: DownloadStatus? = null,
    installing: Boolean = false,
    onCancel: (() -> Unit)? = null,
    label: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    // The border lives on this outer Box, not inside Surface's own modifier: Surface paints its
    // background as part of its internal implementation, which chains after whatever modifier
    // it's given — a border passed straight into Surface's modifier ended up painted OVER by
    // that internal fill and was never actually visible. Drawn on a wrapping Box instead, it's
    // guaranteed to render on top of the Surface, not underneath it. The caller's own `modifier`
    // (TV focus requesters, focus-change logging) stays on the actually-clickable Surface below,
    // not this Box, so focus targeting keeps landing on the real focusable element.
    // padding lives here, not on the Surface below: the border is drawn around THIS Box's bounds,
    // so if the Surface were the one inset from it, the border would trace a bigger rectangle than
    // the actual visible white card and look like it's floating outside it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .then(if (highlighted) premiumCardBorder(shape) else Modifier),
    ) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                // TV only: visible focus ring (no-op on touch).
                .tvFocusOutline(shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    role = Role.Button,
                ),
        ) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
            Column(Modifier.weight(1F)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.version_FORMAT, item.manifest.versionName).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        // Take only the space left after the chip (fill = false) so a very long version
                        // name wraps instead of crushing the "suggested"/"installed" chip to one letter
                        // per line.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    label()
                }
                if ((downloadStatus != null || installing) && onCancel != null) {
                    CompactInstallProgressRow(
                        status = downloadStatus,
                        installing = installing,
                        onCancel = onCancel,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.provided_by_FORMAT, repo.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = stringResource(
                            R.string.label_sdk_version,
                            sdkName[item.manifest.usesSDKs.target]!!,
                            sdkName[item.manifest.usesSDKs.min]!!,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val context = LocalContext.current
                val date = remember { formatDate(context, item.added) }
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = item.apk.size.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        }
    }
}

private fun formatDate(context: Context, instant: Long): String {
    val dateTime = LocalDateTime.ofEpochSecond(instant / 1000, 0, ZoneOffset.UTC)
    return try {
        dateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    } catch (_: Exception) {
        DateFormat.getDateFormat(context).format(instant)
    }
}
