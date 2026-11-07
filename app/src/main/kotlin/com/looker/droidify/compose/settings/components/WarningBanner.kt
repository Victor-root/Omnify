package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusOutline

@Composable
private fun Banner(
    title: String,
    description: String,
    // Null for a banner that only tells the user something, with nothing in the app to go and do about
    // it: it is then plain text rather than a tap that ripples and leads nowhere.
    onClick: (() -> Unit)?,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxWidth().background(containerColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // TV only: an accent outline around the focused banner (no-op on touch); a full-width
                // block can't scale without overflowing the screen.
                .tvFocusOutline(RectangleShape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 16.dp, end = if (onDismiss != null) 48.dp else 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    painter = painterResource(R.drawable.ic_cancel),
                    contentDescription = stringResource(R.string.close),
                    tint = contentColor,
                )
            }
        }
    }
}

/** A full-width banner for a real problem needing the user's attention (a rejected GitHub token, say),
 *  errorContainer-toned, so it reads as more than routine information. */
@Composable
fun WarningBanner(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        title = title,
        description = description,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    )
}

/** The same shape as [WarningBanner], for a routine heads-up rather than a real problem (no GitHub
 *  token configured, say), secondaryContainer-toned, so it doesn't read as more alarming than it is. */
@Composable
fun InfoBanner(
    title: String,
    description: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Banner(
        title = title,
        description = description,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
        onDismiss = onDismiss,
    )
}
