package com.looker.droidify.migration

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusScale

/**
 * The one-off prompt that carries an install across from the beta channel to the stable one.
 *
 * Sits above every screen because it isn't about any of them: it is about the app itself having moved,
 * and it has to be seen whichever screen the user happens to open on. It shows at most once per
 * install, and only in the narrow window where the switch is actually happening — see
 * [MigrationViewModel] for what decides that, and [ChannelMigration] for why the switch cannot simply
 * be an update.
 */
@Composable
fun MigrationPrompt(viewModel: MigrationViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The other channel gets installed while this app is in the background, so what there is to say
    // changes between one look at the app and the next. Without this the beta stayed silent until it
    // was force-stopped and reopened.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    when (val current = state) {
        MigrationState.None -> Unit

        // Carries the switch out from here rather than sending the user off to find the source's page:
        // the download is the whole app, so the prompt stays up and shows it running.
        is MigrationState.MoveToStable -> AlertDialog(
            onDismissRequest = { if (!current.installing) viewModel.dismiss() },
            title = { Text(stringResource(R.string.migration_to_stable_title)) },
            text = {
                Column(verticalArrangement = spacedBy(16.dp)) {
                    Text(stringResource(R.string.migration_to_stable_DESC))
                    if (current.installing) {
                        Text(stringResource(R.string.migration_installing_stable))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (!current.installing) {
                    TextButton(
                        onClick = viewModel::installStable,
                        modifier = Modifier.tvFocusScale(),
                    ) { Text(stringResource(R.string.install)) }
                }
            },
            dismissButton = {
                if (!current.installing) {
                    TextButton(onClick = viewModel::dismiss, modifier = Modifier.tvFocusScale()) {
                        Text(stringResource(R.string.migration_later))
                    }
                }
            },
        )

        // The stable build is already installed: nothing else happens in this app, so the way out is
        // the only action offered.
        MigrationState.OpenStable -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.migration_open_stable_title)) },
            text = { Text(stringResource(R.string.migration_open_stable_DESC)) },
            confirmButton = {
                TextButton(onClick = viewModel::openStable, modifier = Modifier.tvFocusScale()) {
                    Text(stringResource(R.string.migration_open_stable_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss, modifier = Modifier.tvFocusScale()) {
                    Text(stringResource(R.string.migration_later))
                }
            },
        )

        is MigrationState.ImportFromBeta -> ImportDialog(
            step = current.step,
            onImport = viewModel::importFromBeta,
            onUninstall = viewModel::uninstallBeta,
            onDismiss = viewModel::dismiss,
        )
    }
}

@Composable
private fun ImportDialog(
    step: ImportStep,
    onImport: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    // While the transfer runs there is nothing to answer, so the dialog stops being dismissable
    // rather than offering buttons that would interrupt it half way.
    val running = step == ImportStep.RUNNING
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = {
            Text(
                stringResource(
                    when (step) {
                        ImportStep.IMPORTED -> R.string.migration_imported_title
                        ImportStep.FAILED -> R.string.migration_failed_title
                        ImportStep.OFFERED, ImportStep.RUNNING -> R.string.migration_import_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = spacedBy(16.dp)) {
                Text(
                    stringResource(
                        when (step) {
                            ImportStep.IMPORTED -> R.string.migration_imported_DESC
                            ImportStep.FAILED -> R.string.migration_failed_DESC
                            ImportStep.OFFERED, ImportStep.RUNNING -> R.string.migration_import_DESC
                        },
                    ),
                )
                if (running) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            when (step) {
                ImportStep.RUNNING -> Unit
                ImportStep.IMPORTED -> TextButton(
                    onClick = onUninstall,
                    modifier = Modifier.tvFocusScale(),
                ) { Text(stringResource(R.string.migration_uninstall_beta)) }

                ImportStep.OFFERED, ImportStep.FAILED -> TextButton(
                    onClick = onImport,
                    modifier = Modifier.tvFocusScale(),
                ) { Text(stringResource(R.string.migration_import_action)) }
            }
        },
        dismissButton = {
            if (!running) {
                TextButton(onClick = onDismiss, modifier = Modifier.tvFocusScale()) {
                    Text(
                        stringResource(
                            if (step == ImportStep.IMPORTED) {
                                R.string.migration_keep_beta
                            } else {
                                R.string.migration_later
                            },
                        ),
                    )
                }
            }
        },
    )
}
