package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

/**
 * Leading icon shared by every setting row, so each line is easy to scan at a glance. Emits nothing
 * when [icon] is null. Call it as the first child of the row's `Row` — it renders the icon plus the
 * gap before the title/description, dimmed in step with the row's [enabled] state.
 */
@Composable
internal fun SettingLeadingIcon(icon: Painter?, enabled: Boolean) {
    if (icon == null) return
    Icon(
        painter = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.let {
            if (enabled) it else it.copy(alpha = 0.38f)
        },
        modifier = Modifier.size(24.dp),
    )
    Spacer(Modifier.width(20.dp))
}
