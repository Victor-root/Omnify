package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.data.backup.BackupCategory

/** Localised label for a backup category's checkbox row. */
@Composable
fun backupCategoryLabel(category: BackupCategory): String = stringResource(
    when (category) {
        BackupCategory.SETTINGS -> R.string.backup_category_settings
        BackupCategory.REPOSITORIES -> R.string.backup_category_repositories
        BackupCategory.EXTERNAL_SOURCES -> R.string.backup_category_external_sources
        BackupCategory.FAVOURITES -> R.string.backup_category_favourites
        BackupCategory.CUSTOM_BUTTONS -> R.string.backup_category_custom_buttons
    },
)

/** Localised description for a backup category's checkbox row. */
@Composable
fun backupCategoryDescription(category: BackupCategory): String = stringResource(
    when (category) {
        BackupCategory.SETTINGS -> R.string.backup_category_settings_DESC
        BackupCategory.REPOSITORIES -> R.string.backup_category_repositories_DESC
        BackupCategory.EXTERNAL_SOURCES -> R.string.backup_category_external_sources_DESC
        BackupCategory.FAVOURITES -> R.string.backup_category_favourites_DESC
        BackupCategory.CUSTOM_BUTTONS -> R.string.backup_category_custom_buttons_DESC
    },
)

/**
 * The single checkbox-selection dialog shared by both the backup and the restore flow (see
 * [com.looker.droidify.compose.settings.SettingsScreen]) — which categories to write into a new backup
 * zip, or which ones to apply out of an inspected one. [availableCategories] is every category the
 * caller can offer right now (all five when creating a backup; only whatever
 * [com.looker.droidify.data.backup.BackupInspection.availableCategories] found in the archive when
 * restoring), and starts fully checked so the common case — everything — is a single confirm tap.
 */
@Composable
fun BackupCategoryDialog(
    title: String,
    confirmLabel: String,
    availableCategories: Set<BackupCategory>,
    onConfirm: (Set<BackupCategory>) -> Unit,
    onDismiss: () -> Unit,
) {
    val orderedCategories = remember(availableCategories) {
        BackupCategory.entries.filter { it in availableCategories }
    }
    var selected by remember { mutableStateOf(availableCategories) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                orderedCategories.forEach { category ->
                    val checked = category in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // TV only: a soft accent fill behind the focused option (no-op on touch).
                            .tvFocusFill(RoundedCornerShape(8.dp))
                            .clickable {
                                selected = if (checked) selected - category else selected + category
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                selected = if (it) selected + category else selected - category
                            },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                text = backupCategoryLabel(category),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = backupCategoryDescription(category),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
