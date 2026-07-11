package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus

/**
 * Live download progress: a wavy linear bar — determinate, filling as the download advances — under
 * "12.3 MB / 45.6 MB · 2.3 MB/s · 56 %", with a Cancel button. Falls back to an indeterminate wavy bar
 * when the server doesn't report a total. Shared by the app-detail screen and the external-apps cards
 * so both show identical progress.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadProgressRow(
    status: DownloadStatus,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // TV: the download/install states replace the primary action button entirely, so its startup-focus
    // requester (see AppDetailScreen's primaryActionFocusRequester) has nothing to attach to unless it's
    // handed down here — landing it on Cancel instead. Null off TV / for callers that don't need it.
    cancelFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (status.hasTotal) {
                        "${status.readLabel} / ${status.totalLabel}"
                    } else {
                        status.readLabel
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val trailing = buildString {
                    status.speedLabel?.let { append(it) }
                    if (status.hasTotal) {
                        if (isNotEmpty()) append("  ·  ")
                        append("${status.percent} %")
                    }
                }
                if (trailing.isNotEmpty()) {
                    Text(
                        text = trailing,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = onCancel,
                modifier = if (cancelFocusRequester != null) {
                    Modifier.focusRequester(cancelFocusRequester).tvFocusScale()
                } else {
                    Modifier
                },
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
        val fraction = status.fraction
        if (fraction != null) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Shown after the download completes, while the system installer does its work. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InstallingRow(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.installing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onCancel,
                modifier = if (cancelFocusRequester != null) {
                    Modifier.focusRequester(cancelFocusRequester).tvFocusScale()
                } else {
                    Modifier
                },
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Compact download/install progress for a single row in a version list — a slimmer variant of
 * [DownloadProgressRow]/[InstallingRow] sized to sit inside a version list item instead of the hero
 * card, so picking an older version to install (or downgrade) shows its progress right on that row
 * instead of only in the hero card, which is out of view once the user has scrolled down to the list.
 * [status] null with [installing] true is the post-download "installing" phase; both null/false means
 * this row isn't the active one (the caller shouldn't compose this at all in that case).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompactInstallProgressRow(
    status: DownloadStatus?,
    installing: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (status != null) {
                    val trailing = buildString {
                        if (status.hasTotal) {
                            append("${status.readLabel} / ${status.totalLabel}")
                            append("  ·  ${status.percent} %")
                        } else {
                            append(status.readLabel)
                        }
                    }
                    trailing
                } else {
                    stringResource(R.string.installing)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        val fraction = status?.fraction
        if (fraction != null) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        }
    }
}
