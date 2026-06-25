package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.DownloadStatus

/**
 * Live download progress: a determinate bar with "12.3 MB / 45.6 MB · 2.3 MB/s · 56 %" (falls back
 * to an indeterminate bar when the server doesn't report a total). Shared by the app-detail screen
 * and the external-apps cards so both show identical progress.
 */
@Composable
fun DownloadProgressRow(
    status: DownloadStatus,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
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
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            val fraction = status.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    // No "stop indicator" dot at the end of the track (the default in M3).
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}

/** Shown after the download completes, while the system installer does its work. */
@Composable
fun InstallingRow(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.installing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}
