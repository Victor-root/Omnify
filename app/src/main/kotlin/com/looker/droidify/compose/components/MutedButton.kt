package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp

/**
 * The secondary button: the accent colour of the main buttons, muted towards the surface. It keeps the
 * colour the person chose, with nothing written in hard, while standing apart from the filled main
 * actions (Install, Next...) it sits next to. Used wherever a grey outlined button used to be.
 */
@Composable
fun MutedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = lerp(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface, 0.6f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}
