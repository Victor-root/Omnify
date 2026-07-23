package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import androidx.compose.foundation.shape.RoundedCornerShape
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.datastore.model.CustomButtonIcon

@Composable
fun CustomButtonsSettingItem(
    buttons: List<CustomButton>,
    onAddButton: (CustomButton) -> Unit,
    onUpdateButton: (CustomButton) -> Unit,
    onRemoveButton: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEditor by remember { mutableStateOf(false) }
    var editingButton by remember { mutableStateOf<CustomButton?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // On TV the only focusable element used to be the small "+" at the far right, which the D-pad's
        // downward search skipped over (it's not aligned under the full-width rows above), so this whole
        // setting was unreachable. Give the label area its own full-width focus/click target — opening the
        // same add editor as the "+" — so the row is reachable and the highlight matches the other rows.
        // The phone layout keeps the plain, non-focusable label.
        val isTv = LocalIsTelevision.current
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (isTv) {
                            Modifier
                                .tvFocusFill(RoundedCornerShape(12.dp))
                                .clickable {
                                    editingButton = null
                                    showEditor = true
                                }
                                .padding(8.dp)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(
                    text = stringResource(R.string.custom_buttons),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.custom_buttons_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    editingButton = null
                    showEditor = true
                },
                modifier = Modifier.tvFocusScale(),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.custom_button_add),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (buttons.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            buttons.forEach { button ->
                CustomButtonItem(
                    button = button,
                    onEdit = {
                        editingButton = button
                        showEditor = true
                    },
                    onDelete = { onRemoveButton(button.id) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showEditor) {
        CustomButtonEditor(
            existingButton = editingButton,
            onSave = { button ->
                if (editingButton != null) {
                    onUpdateButton(button)
                } else {
                    onAddButton(button)
                }
                showEditor = false
                editingButton = null
            },
            onDismiss = {
                showEditor = false
                editingButton = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CustomButtonItem(
    button: CustomButton,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    // Not directly focusable/clickable itself: the delete IconButton at the end is its own independently
    // focusable control, so a row that's ALSO directly focusable/clickable put two focus targets in the
    // same space — confusing on TV (which one is "it"?) and unreachable-by-D-pad for the delete button,
    // since Compose's directional focus search won't descend into the currently-focused node's own
    // subtree. A focusGroup instead, with the icon/label area carrying its own dedicated click target as
    // a true sibling of the delete button.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .focusGroup()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                // TV only: a soft accent fill layered over the row's own background on focus (no-op on
                // touch).
                .tvFocusFill(MaterialTheme.shapes.medium)
                .clickable(onClick = onEdit)
                .padding(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (button.icon == CustomButtonIcon.TEXT_ONLY) {
                    Text(
                        text = button.label.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    Icon(
                        painter = painterResource(button.icon.toDrawableRes()),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = button.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = button.urlTemplate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box {
            IconButton(
                onClick = { showDeleteConfirmation = true },
                modifier = Modifier.tvFocusScale(),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.confirmation)) },
            text = {
                Text(
                    stringResource(
                        R.string.custom_button_delete_confirmation,
                        button.label,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
