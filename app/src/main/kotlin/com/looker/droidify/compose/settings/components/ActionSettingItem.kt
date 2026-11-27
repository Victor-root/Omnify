package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.components.tvFocusFill

@Composable
fun ActionSettingItem(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    descriptionColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // TV only: a soft accent fill behind the focused row (no-op on touch).
            .tvFocusFill(RoundedCornerShape(12.dp))
            .settingRowClick(enabled, onClick)
            .padding(settingRowPadding),
    ) {
        SettingLeadingIcon(icon, enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = settingTitleStyle,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.38f),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) descriptionColor else descriptionColor.copy(alpha = 0.38f),
            )
        }
    }
}
