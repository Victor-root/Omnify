package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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

private val IndicatorSize = 36.dp

/**
 * Live download progress: the wavy circular indicator (the same one shown while repos load at first
 * launch) — determinate, filling as the download advances — next to "12.3 MB / 45.6 MB · 2.3 MB/s ·
 * 56 %". Falls back to an indeterminate indicator when the server doesn't report a total. Shared by
 * the app-detail screen and the external-apps cards so both show identical progress.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DownloadProgressRow(
    status: DownloadStatus,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val fraction = status.fraction
        if (fraction != null) {
            CircularWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(IndicatorSize),
            )
        } else {
            CircularWavyProgressIndicator(modifier = Modifier.size(IndicatorSize))
        }
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
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}

/** Shown after the download completes, while the system installer does its work. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InstallingRow(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularWavyProgressIndicator(modifier = Modifier.size(IndicatorSize))
        Text(
            text = stringResource(R.string.installing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
    }
}
