package com.looker.droidify.compose.settings.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.theme.LocalAccentBarColor
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.LocalOnAccentBarColor

/*
 * The parts every setting row shares. On a touch screen the rows sit in rounded cards (see
 * settingsCard) and look like AukDialer's: a leading icon on a tile in the full accent, a medium-weight
 * title, a row that rounds off and gives a little when pressed. A television keeps its own plainer rows,
 * built around the focus fill that marks the row the remote is on.
 */

private val TileShape = RoundedCornerShape(14.dp)
private val RowShape = RoundedCornerShape(20.dp)

/** How much a pressed row shrinks. */
private const val PRESSED_SCALE = 0.97f

/** The opacity Material gives a disabled control. */
private const val DISABLED_ALPHA = 0.38f

/**
 * Leading icon shared by every setting row, so each line is easy to scan at a glance. Emits nothing
 * when [icon] is null. Call it as the first child of the row's `Row`: it renders the icon plus the gap
 * before the title and description, dimmed in step with the row's [enabled] state.
 */
@Composable
internal fun SettingLeadingIcon(icon: Painter?, enabled: Boolean) {
    if (icon == null) return
    if (LocalIsTelevision.current) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.let {
                if (enabled) it else it.copy(alpha = DISABLED_ALPHA)
            },
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(20.dp))
        return
    }
    SettingIconTile(LocalAccentBarColor.current, Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = LocalOnAccentBarColor.current,
            modifier = Modifier.size(20.dp),
        )
    }
    Spacer(Modifier.width(14.dp))
}

/** The rounded tile a touch row's leading icon sits on, filled with [color]. */
@Composable
internal fun SettingIconTile(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .background(color, TileShape),
    ) {
        content()
    }
}

/** Around a row's content. */
internal val settingRowPadding: PaddingValues
    @Composable get() = if (LocalIsTelevision.current) {
        PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    } else {
        PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    }

/** A row's title. */
internal val settingTitleStyle: TextStyle
    @Composable get() = if (LocalIsTelevision.current) {
        MaterialTheme.typography.bodyLarge
    } else {
        MaterialTheme.typography.titleMedium
    }

/** A row opening something on a tap: on a touch screen, rounded and giving a little while pressed. */
@Composable
internal fun Modifier.settingRowClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return settingRowPress(interactionSource)
        .clickable(interactionSource, ripple(), enabled = enabled, onClick = onClick)
}

/** A row turning a switch on and off on a tap, the switch itself only showing it. */
@Composable
internal fun Modifier.settingRowToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return settingRowPress(interactionSource).toggleable(
        value = checked,
        interactionSource = interactionSource,
        indication = ripple(),
        enabled = enabled,
        role = Role.Switch,
        onValueChange = onCheckedChange,
    )
}

@Composable
private fun Modifier.settingRowPress(interactionSource: MutableInteractionSource): Modifier {
    if (LocalIsTelevision.current) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, label = "settingRowPress")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clip(RowShape)
}

/** A switch turned on in the accent, as the header and the icon tiles are. */
@Composable
internal fun settingSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = LocalAccentBarColor.current,
    checkedThumbColor = LocalOnAccentBarColor.current,
    checkedIconColor = LocalAccentBarColor.current,
    checkedBorderColor = LocalAccentBarColor.current,
)
