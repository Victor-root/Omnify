package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.theme.LocalIsTelevision

@Composable
fun SwitchSettingItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    enabled: Boolean = true,
) {
    // On Android TV the whole row is a single D-pad focus target: the accent highlight spans the entire
    // row (icon, text and the switch) with roomy padding so it never hugs the text, and the switch is
    // display-only (center toggles the row).
    if (LocalIsTelevision.current) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .tvFocusFill(RoundedCornerShape(18.dp))
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            SettingLeadingIcon(icon, enabled)
            Column(modifier = Modifier.weight(1f)) {
                SwitchTitle(title, enabled)
                SwitchDescription(description, enabled)
            }
            Spacer(modifier = Modifier.width(16.dp))
            // Display-only (onCheckedChange = null) so it isn't a second focus target; the row toggles it.
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        return
    }

    // The whole row toggles the switch, which only shows the state: one target, as on TV. The title
    // runs the row's whole width; the switch sits beside the description under it, so a title doesn't
    // wrap early for a column the switch only needs further down.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .settingRowToggle(checked, enabled, onCheckedChange)
            .padding(settingRowPadding),
    ) {
        SettingLeadingIcon(icon, enabled)
        Column(modifier = Modifier.weight(1f)) {
            SwitchTitle(title, enabled)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SwitchDescription(description, enabled, Modifier.weight(1f))
                Spacer(modifier = Modifier.width(16.dp))
                Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = settingSwitchColors())
            }
        }
    }
}

@Composable
private fun SwitchTitle(title: String, enabled: Boolean) {
    Text(
        text = title,
        style = settingTitleStyle,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
    )
}

@Composable
private fun SwitchDescription(description: String, enabled: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = description,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
    )
}
