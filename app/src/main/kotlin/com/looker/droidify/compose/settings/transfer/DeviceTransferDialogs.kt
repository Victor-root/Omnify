package com.looker.droidify.compose.settings.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
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
import com.looker.droidify.compose.settings.components.BackupCategoryChecklist
import com.looker.droidify.compose.settings.components.backupCategoryLabel
import com.looker.droidify.data.backup.BackupCategory
import com.looker.droidify.transfer.TransferFailure

/**
 * Receiving: this device shows a code and holds it there until the sending device types it, then
 * shows what arrived and applies it once the user says so.
 *
 * There is nothing to choose here, by design: no list of categories to work through, no direction to
 * settle. The screen tells the user the one thing they need to know, which is where to type the code,
 * and later the one thing they should see before their settings change, which is what turned up.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceTransferReceiveDialog(
    onDismiss: () -> Unit,
    viewModel: DeviceTransferViewModel = hiltViewModel(),
) {
    val state by viewModel.receiving.collectAsStateWithLifecycle()

    // Tied to the dialog being on screen: the port opens when it appears and closes when it leaves,
    // so a session can never outlive the code that is being shown for it.
    DisposableEffect(Unit) {
        viewModel.startReceiving()
        onDispose { viewModel.stopReceiving() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_receive_title)) },
        text = {
            // Scrollable because what arrived can be all seven categories listed one under another,
            // which on a phone is already taller than a dialog.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ReceiveContent(state)
            }
        },
        confirmButton = {
            when (state) {
                is ReceiveState.Confirming -> TextButton(onClick = viewModel::acceptIncoming) {
                    Text(stringResource(R.string.transfer_apply_action))
                }

                // A code that ran out or was guessed at too often is not a dead end: one tap replaces
                // it, without leaving the screen and coming back for the same thing.
                ReceiveState.Abandoned, ReceiveState.Expired -> TextButton(onClick = viewModel::restartReceiving) {
                    Text(stringResource(R.string.transfer_new_code))
                }

                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            when (state) {
                is ReceiveState.Confirming -> TextButton(onClick = viewModel::declineIncoming) {
                    Text(stringResource(R.string.transfer_decline_action))
                }

                ReceiveState.Abandoned, ReceiveState.Expired -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }

                else -> Unit
            }
        },
    )
}

/** Everything the receiving dialog can be showing, from the code itself through to what turned up. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReceiveContent(state: ReceiveState) {
    when (state) {
        ReceiveState.Starting -> WorkingRow(stringResource(R.string.transfer_starting))

        is ReceiveState.ShowingCode -> {
            Text(
                text = stringResource(R.string.transfer_receive_DESC),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = state.code,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                // Monospaced so no two digits can be misread from across a room, which is the whole
                // job of this screen, and pinned to one line so a narrow screen or a large font
                // setting can never wrap a code down the middle: a code broken over two lines is
                // unreadable, which is worse than a slightly smaller one.
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

        is ReceiveState.Confirming -> {
            Text(
                text = stringResource(R.string.transfer_confirm_DESC),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            BackupCategory.entries.filter { it in state.categories }.forEach { category ->
                Text(
                    text = stringResource(R.string.transfer_confirm_item_FORMAT, backupCategoryLabel(category)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        ReceiveState.Applying -> WorkingRow(stringResource(R.string.transfer_applying))

        ReceiveState.Done -> Message(stringResource(R.string.transfer_done))
        ReceiveState.Declined -> Message(stringResource(R.string.transfer_declined))
        ReceiveState.NoNetwork -> Message(stringResource(R.string.transfer_no_network))
        ReceiveState.Abandoned -> Message(stringResource(R.string.transfer_abandoned))
        ReceiveState.Expired -> Message(stringResource(R.string.transfer_expired))
        ReceiveState.Failed -> Message(stringResource(R.string.transfer_failed))
    }
}

/**
 * Sending: the user types the code the receiving device is showing, and that is the whole
 * interaction. Finding the device and handing the data over are one step, because there is nothing
 * to decide in between.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceTransferSendDialog(
    onDismiss: () -> Unit,
    viewModel: DeviceTransferViewModel = hiltViewModel(),
) {
    val state by viewModel.sending.collectAsStateWithLifecycle()
    val categories by viewModel.sendCategories.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }
    val orderedCategories = remember { BackupCategory.entries.toList() }

    // The view model outlives this dialog, so without this, closing it after a transfer and opening
    // it again would show the finished state from last time instead of an empty code field.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetSending() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_send_title)) },
        text = {
            // Scrollable because the category list is the same seven rows the backup dialog shows,
            // and on a phone that is already taller than a dialog.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (val current = state) {
                    is SendState.EnteringCode -> {
                        Text(
                            text = stringResource(R.string.transfer_send_DESC),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = typed,
                            // Digits only, so nothing a numeric keypad cannot produce ever reaches
                            // the field, and pasting something else cannot half-fill it either.
                            onValueChange = { entered -> typed = entered.filter { it.isDigit() } },
                            singleLine = true,
                            label = { Text(stringResource(R.string.transfer_code_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = current.error != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        current.error?.let { failure ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(failure.messageRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.transfer_what_to_send),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        BackupCategoryChecklist(
                            categories = orderedCategories,
                            selected = categories,
                            onSelectedChange = viewModel::setSendCategories,
                        )
                    }

                    SendState.Working -> WorkingRow(stringResource(R.string.transfer_working))
                    SendState.Sent -> Message(stringResource(R.string.transfer_sent))
                }
            }
        },
        confirmButton = {
            if (state is SendState.EnteringCode) {
                TextButton(
                    onClick = { viewModel.sendTo(typed) },
                    enabled = typed.isNotBlank() && categories.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.transfer_send_action))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            if (state is SendState.EnteringCode) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** A spinner beside a line of status text, the shape every waiting state on both dialogs takes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A finished state: one sentence saying what happened, and nothing else to do but close. */
@Composable
private fun Message(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

private fun TransferFailure.messageRes(): Int = when (this) {
    TransferFailure.NOT_FOUND -> R.string.transfer_error_not_found
    TransferFailure.UNREACHABLE -> R.string.transfer_error_unreachable
    TransferFailure.REJECTED -> R.string.transfer_error_rejected
    TransferFailure.IMPOSTOR -> R.string.transfer_error_impostor
}
