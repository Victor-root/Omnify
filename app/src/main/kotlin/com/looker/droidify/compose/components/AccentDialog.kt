package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * The ground every dialog of the app shares: the surface tinted with the accent at the top, fading
 * to the plain surface lower down, inside a fine outline in the accent. Give it to the dialog's own
 * container, with a transparent colour of its own so the gradient shows through.
 */
@Composable
fun accentDialogBackground(shape: Shape = AlertDialogDefaults.shape): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val tinted = accent.copy(alpha = TOP_TINT).compositeOver(surface)
    return Modifier
        .background(Brush.verticalGradient(0f to tinted, FADE_END to surface), shape)
        .border(1.dp, accent.copy(alpha = OUTLINE_ALPHA), shape)
}

/** How much of the accent the top of a dialog takes. */
private const val TOP_TINT = 0.16f

/** Where, down a dialog, the tint has faded into the plain surface. */
private const val FADE_END = 0.6f

private const val OUTLINE_ALPHA = 0.45f

/** Material's [AlertDialog] on the app's dialog ground ([accentDialogBackground]). */
@Composable
fun AccentAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.then(accentDialogBackground(shape)),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        properties = properties,
    )
}
