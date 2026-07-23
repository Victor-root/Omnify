package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
    // row — icon, text AND the switch — with roomy padding so it never hugs the text, and the switch is
    // display-only (center toggles the row). The phone layout is left exactly as it was: a focusGroup
    // with a separate label click target and an independently interactive switch.
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

    // Not directly focusable/clickable itself: the Switch at the end is its own independently focusable
    // control (Material3's Switch is always interactive), so a row that's ALSO directly focusable/
    // clickable put two focus targets in the same space — confusing on TV (which one is "it"?) and
    // unreachable-by-D-pad for the Switch, since Compose's directional focus search won't descend into
    // the currently-focused node's own subtree. A focusGroup instead, with the label area carrying its
    // own dedicated click target as a true sibling of the Switch.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(vertical = 4.dp),
        ) {
            SettingLeadingIcon(icon, enabled)
            Column(modifier = Modifier.weight(1f)) {
                SwitchTitle(title, enabled)
                SwitchDescription(description, enabled)
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun SwitchTitle(title: String, enabled: Boolean) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
    )
}

@Composable
private fun SwitchDescription(description: String, enabled: Boolean) {
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
    )
}
