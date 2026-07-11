package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R

/**
 * "Root compatible" pill shown on the detail screen — for an app
 * [com.looker.droidify.utility.common.RootDetection] flags as using root (the legacy superuser
 * permission, or strong root phrasing like Magisk/KernelSU in its own text), on both the F-Droid
 * catalogue and the external (GitHub/GitLab/Codeberg) detail screens. Deliberately worded as a
 * *capability*, not a requirement — an app can perfectly well work with or without root, and this
 * must never be read as "you need root to install/use this." [contentDescription] carries that fuller
 * explanation for screen readers, while the visible label stays short.
 */
@Composable
fun RootBadge(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.root_compatible_DESC)
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Terminal,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.root_compatible),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}
