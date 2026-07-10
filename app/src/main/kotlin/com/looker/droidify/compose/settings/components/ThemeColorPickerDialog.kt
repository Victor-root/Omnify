package com.looker.droidify.compose.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.looker.droidify.R
import com.looker.droidify.compose.components.tvFocusOutline
import com.looker.droidify.compose.components.tvFocusScale
import com.looker.droidify.compose.theme.accentColorPalette
import com.looker.droidify.datastore.DEFAULT_THEME_COLOR
import com.looker.droidify.utility.common.wallpaperAccentColor

/**
 * "Palette" theme picker: lets the user choose the app's accent color from a grid of Material
 * swatches, plus "Default" (red) and (on Android 12+) "Wallpaper" (Material You). Selecting a
 * color applies it immediately and recreates the activity so the whole app follows the choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeColorPickerDialog(
    selectedColor: Int,
    dynamicEnabled: Boolean,
    showWallpaperOption: Boolean,
    onColorSelected: (Int) -> Unit,
    onWallpaperSelected: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.theme),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LabeledSwatch(
                        color = Color(DEFAULT_THEME_COLOR),
                        label = stringResource(R.string.theme_color_default),
                        selected = !dynamicEnabled && selectedColor == DEFAULT_THEME_COLOR,
                        onClick = { onColorSelected(DEFAULT_THEME_COLOR) },
                    )
                    if (showWallpaperOption) {
                        // Preview the colour from the ACTUAL wallpaper (read via WallpaperManager), so
                        // it's right even on OEM skins like ColorOS where the system dynamic accent
                        // doesn't follow the wallpaper. Falls back to the current primary if unreadable.
                        val context = LocalContext.current
                        val wallpaperColor = remember(context) { context.wallpaperAccentColor() }
                            ?.let { Color(it) }
                            ?: MaterialTheme.colorScheme.primary
                        LabeledSwatch(
                            color = wallpaperColor,
                            label = stringResource(R.string.theme_color_wallpaper),
                            selected = dynamicEnabled,
                            onClick = onWallpaperSelected,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // The default colour is offered as "Default" above, so leave it out of the grid.
                    accentColorPalette.filter { it != DEFAULT_THEME_COLOR }.forEach { argb ->
                        ColorSwatch(
                            color = Color(argb),
                            selected = !dynamicEnabled && selectedColor == argb,
                            onClick = { onColorSelected(argb) },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ColorSwatch(color = color, selected = selected, onClick = onClick)
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            // TV only: the focused swatch grows and gains a ring, so a grid of identical circles stays
            // navigable with the D-pad (no-op on touch).
            .tvFocusScale()
            .tvFocusOutline(CircleShape)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = if (color.luminance() < 0.5f) Color.White else Color.Black,
            )
        }
    }
}
