package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R

/**
 * A choice among [values] in a dialog, one radio row each. [message] is said once above them, and
 * [valueDescription] under a value's own name when it needs explaining; [recommended] is marked as such.
 */
@Composable
fun <T> SelectionDialog(
    title: String,
    selectedValue: T,
    values: List<T>,
    valueToString: @Composable (T) -> String,
    onValueSelected: (T) -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    recommended: T? = null,
    message: String? = null,
    valueDescription: @Composable (T) -> String? = { null },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(imageVector = it, contentDescription = null) } },
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                values.forEach { value ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // TV only: a soft accent fill behind the focused option (no-op on touch).
                            .tvFocusFill(RoundedCornerShape(8.dp))
                            .clickable { onValueSelected(value) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = value == selectedValue,
                            onClick = { onValueSelected(value) },
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = valueToString(value), style = MaterialTheme.typography.bodyLarge)
                            valueDescription(value)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (value == recommended) RecommendedBadge(Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RecommendedBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.recommended),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
