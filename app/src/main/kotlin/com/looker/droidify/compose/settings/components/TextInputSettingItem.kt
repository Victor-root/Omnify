package com.looker.droidify.compose.settings.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusFill

@Composable
fun TextInputSettingItem(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    dialogTitle: String = title,
    enabled: Boolean = true,
    // What to show as the subtitle. Defaults to the value itself; pass a masked/status string for
    // secrets (e.g. a token) so the raw value isn't displayed in the settings list.
    valueDisplay: String? = null,
    // Colors valueDisplay as an error (e.g. a token GitHub is actively rejecting) instead of the
    // ordinary subtitle tone, so a problem the user must act on doesn't read as routine status text.
    valueDisplayIsError: Boolean = false,
    // Optional help text shown behind a "Help" toggle inside the edit dialog (e.g. how to create a
    // token). Null hides the help button entirely.
    helpText: String? = null,
    // Pulses the row's background a couple of times right after this becomes true — the landing point
    // after scrolling here from a warning banner elsewhere, on a settings list long enough that where
    // the row ends up isn't otherwise obvious at a glance.
    highlighted: Boolean = false,
) {
    var showDialog by remember { mutableStateOf(false) }
    val highlightAlpha = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        repeat(2) {
            highlightAlpha.animateTo(1f, animationSpec = tween(450, easing = FastOutSlowInEasing))
            highlightAlpha.animateTo(0f, animationSpec = tween(450, easing = FastOutSlowInEasing))
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary.copy(alpha = highlightAlpha.value * 0.24f))
            // TV only: a soft accent fill behind the focused row (no-op on touch).
            .tvFocusFill(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SettingLeadingIcon(icon, enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                text = valueDisplay ?: value.ifEmpty { stringResource(R.string.unspecified) },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    valueDisplayIsError -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    if (showDialog) {
        TextInputDialog(
            title = dialogTitle,
            initialValue = value,
            helpText = helpText,
            onConfirm = {
                onValueChange(it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    initialValue: String,
    helpText: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    var showHelp by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (helpText != null && showHelp) {
                    Text(
                        text = helpText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        // A single button row so the optional Help toggle sits on the same line as Cancel/OK — Help on
        // the left, the actions pushed to the right.
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (helpText != null) {
                    TextButton(onClick = { showHelp = !showHelp }) {
                        Text(text = stringResource(R.string.help))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.cancel))
                }
                TextButton(onClick = { onConfirm(text) }) {
                    Text(text = stringResource(R.string.ok))
                }
            }
        },
    )
}
