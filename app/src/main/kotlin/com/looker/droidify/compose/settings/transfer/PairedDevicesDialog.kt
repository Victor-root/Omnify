package com.looker.droidify.compose.settings.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.rememberTvTyping
import com.looker.droidify.compose.components.tvTypeOnOk

/**
 * The devices this one is paired with, for quick shares, and pairing another: one device shows a code,
 * the other types it, once.
 */
@Composable
fun PairedDevicesDialog(
    onDismiss: () -> Unit,
    viewModel: PairedDevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }

    // A code shown here is only valid while the dialog is: leaving closes the session it belongs to.
    DisposableEffect(Unit) {
        onDispose { viewModel.stop() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paired_devices_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (val current = state) {
                    PairingState.Idle -> {
                        Text(
                            text = stringResource(R.string.paired_devices_DESC),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (devices.isEmpty()) {
                            Message(stringResource(R.string.paired_devices_none))
                        }
                        devices.forEach { device ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { viewModel.forget(device) }) {
                                    Text(stringResource(R.string.paired_devices_forget))
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = viewModel::showCode, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.paired_devices_show_code))
                        }
                        OutlinedButton(
                            onClick = {
                                typed = ""
                                viewModel.enterCode()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.paired_devices_enter_code))
                        }
                    }

                    PairingState.Starting -> WorkingRow(stringResource(R.string.transfer_starting))

                    is PairingState.ShowingCode -> {
                        Text(
                            text = stringResource(R.string.paired_devices_show_code_DESC),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        // Same reading as the transfer code: monospaced, one line, from across a room.
                        Text(
                            text = current.code,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 32.sp,
                            letterSpacing = 2.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(20.dp))
                        WorkingRow(stringResource(R.string.transfer_waiting))
                    }

                    is PairingState.EnteringCode -> {
                        Text(
                            text = stringResource(R.string.paired_devices_enter_code_DESC),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        val codeTyping = rememberTvTyping()
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { entered -> typed = entered.filter { it.isDigit() } },
                            singleLine = true,
                            label = { Text(stringResource(R.string.transfer_code_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = current.error != null,
                            keyboardActions = KeyboardActions(onAny = { codeTyping.stop() }),
                            readOnly = codeTyping.readOnly,
                            modifier = Modifier.tvTypeOnOk(codeTyping).fillMaxWidth(),
                        )
                        current.error?.let { failure ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(failure.messageRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    is PairingState.Confirming -> Message(
                        stringResource(R.string.paired_devices_confirm_FORMAT, current.device.name),
                    )

                    PairingState.Working -> WorkingRow(stringResource(R.string.paired_devices_pairing))

                    is PairingState.Paired -> Message(stringResource(R.string.paired_devices_paired_FORMAT, current.name))

                    is PairingState.Failed -> Message(
                        stringResource(
                            when (current.reason) {
                                PairingFailure.NO_NETWORK -> R.string.transfer_no_network
                                PairingFailure.EXPIRED -> R.string.transfer_expired
                                PairingFailure.ABANDONED -> R.string.transfer_abandoned
                                PairingFailure.NOT_STORED -> R.string.paired_devices_not_stored
                                PairingFailure.ALREADY_PAIRED -> R.string.paired_devices_already_paired
                                PairingFailure.FULL -> R.string.paired_devices_full
                            },
                        ),
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is PairingState.EnteringCode -> TextButton(
                    onClick = { viewModel.pairWith(typed) },
                    enabled = typed.isNotBlank(),
                ) {
                    Text(stringResource(R.string.paired_devices_pair_action))
                }

                is PairingState.Confirming -> TextButton(onClick = viewModel::confirm) {
                    Text(stringResource(R.string.paired_devices_pair_action))
                }

                PairingState.Idle -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }

                is PairingState.Paired, is PairingState.Failed -> TextButton(onClick = viewModel::stop) {
                    Text(stringResource(R.string.ok))
                }

                // Waiting: the one thing to do is stop, which goes back to the list.
                else -> TextButton(onClick = viewModel::stop) { Text(stringResource(R.string.cancel)) }
            }
        },
        dismissButton = {
            when (state) {
                is PairingState.EnteringCode -> TextButton(onClick = viewModel::stop) {
                    Text(stringResource(R.string.cancel))
                }

                is PairingState.Confirming -> TextButton(onClick = viewModel::stop) {
                    Text(stringResource(R.string.transfer_decline_action))
                }

                else -> Unit
            }
        },
    )
}
