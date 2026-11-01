package com.looker.droidify.compose.quickshare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.settings.transfer.Message
import com.looker.droidify.compose.settings.transfer.PairedDevicesDialog
import com.looker.droidify.compose.settings.transfer.WorkingRow
import com.looker.droidify.external.ExternalApp
import com.looker.droidify.transfer.QuickShareResult

/**
 * Sends [app] to a paired device in one tap: the devices paired with this one, and which of them it
 * went to. With none paired yet, it says how to pair one and offers to.
 */
@Composable
fun QuickShareSendDialog(
    app: ExternalApp,
    onDismiss: () -> Unit,
    viewModel: QuickShareViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val state by viewModel.sendState.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.resetSend() }
    }

    if (pairing) {
        PairedDevicesDialog(onDismiss = { pairing = false })
        return
    }
    val needsPairing = state is QuickShareSendState.Choosing && devices.isEmpty()
    val failed = (state as? QuickShareSendState.Done)?.result?.let { it != QuickShareResult.SENT } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_share_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (val current = state) {
                    QuickShareSendState.Choosing -> if (devices.isEmpty()) {
                        Message(stringResource(R.string.quick_share_no_device))
                    } else {
                        Text(
                            text = stringResource(R.string.quick_share_DESC, app.label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        devices.forEach { device ->
                            OutlinedButton(
                                onClick = { viewModel.send(app, device) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(device.name)
                            }
                        }
                    }

                    is QuickShareSendState.Sending ->
                        WorkingRow(stringResource(R.string.quick_share_sending_FORMAT, current.to))

                    is QuickShareSendState.Done -> Message(
                        when (current.result) {
                            QuickShareResult.SENT -> stringResource(R.string.quick_share_sent_FORMAT, current.to)
                            QuickShareResult.NOT_FOUND -> stringResource(R.string.quick_share_not_found_FORMAT, current.to)
                            QuickShareResult.FAILED -> stringResource(R.string.quick_share_failed_FORMAT, current.to)
                            QuickShareResult.IMPOSTOR -> stringResource(R.string.transfer_error_impostor)
                        },
                    )
                }
            }
        },
        confirmButton = {
            when {
                needsPairing -> TextButton(onClick = { pairing = true }) {
                    Text(stringResource(R.string.quick_share_pair_action))
                }

                failed -> TextButton(onClick = viewModel::resetSend) { Text(stringResource(R.string.quick_share_retry)) }

                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            if (needsPairing || failed) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

/**
 * Above every screen: keeps this device listening for quick shares while the app is on screen, and
 * asks before adding what a paired device sent. Nothing is added without this answer.
 */
@Composable
fun QuickShareInbox(viewModel: QuickShareViewModel = hiltViewModel()) {
    LifecycleStartEffect(Unit) {
        viewModel.setForeground(true)
        onStopOrDispose { viewModel.setForeground(false) }
    }
    val incoming by viewModel.incoming.collectAsStateWithLifecycle()
    val share = incoming ?: return
    AlertDialog(
        onDismissRequest = viewModel::declineIncoming,
        title = { Text(stringResource(R.string.quick_share_received_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.quick_share_received_FORMAT, share.from),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                share.apps.forEach { app ->
                    Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
                    // Where it comes from, in full, since that is what decides what gets installed.
                    Text(
                        text = "${app.sourceLabel} · ${app.path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::acceptIncoming) { Text(stringResource(R.string.quick_share_add_action)) }
        },
        dismissButton = {
            TextButton(onClick = viewModel::declineIncoming) { Text(stringResource(R.string.transfer_decline_action)) }
        },
    )
}
