package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.utility.common.AppCapabilities

/**
 * The pills for [capabilities] shown on an app's card, side by side and wrapping when they do not fit,
 * then [more] (a pre-release badge): "Root compatible" and "Shizuku compatible". Both are worded as a *capability*, not
 * a requirement: an app can perfectly well work without either, and this must never be read as "you
 * need it to install or use this". Each pill's content description carries that fuller explanation for
 * screen readers, while the visible label stays short. [centred] for a card that centres its content.
 */
@Composable
fun CapabilityBadges(
    capabilities: AppCapabilities,
    modifier: Modifier = Modifier,
    centred: Boolean = false,
    more: @Composable () -> Unit = {},
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = if (centred) {
            Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.spacedBy(8.dp)
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Both at once: the short names, so the two fit side by side on a phone, read together as
        // "compatible with root and Shizuku". Each still says so in full to screen readers.
        val short = capabilities.root && capabilities.shizuku
        if (capabilities.root) {
            CapabilityBadge(
                icon = Icons.Filled.Terminal,
                label = stringResource(if (short) R.string.root_compatible_short else R.string.root_compatible),
                description = stringResource(R.string.root_compatible_DESC),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        if (capabilities.shizuku) {
            CapabilityBadge(
                icon = Icons.Filled.Adb,
                label = stringResource(
                    if (short) R.string.shizuku_compatible_short else R.string.shizuku_compatible,
                ),
                description = stringResource(R.string.shizuku_compatible_DESC),
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        more()
    }
}

@Composable
private fun CapabilityBadge(
    icon: ImageVector,
    label: String,
    description: String,
    container: Color,
    content: Color,
) {
    Row(
        modifier = Modifier
            .background(container, shape = RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = content,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}
