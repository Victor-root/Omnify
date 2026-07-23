package com.looker.droidify.compose.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.max

/**
 * The Android TV screen background: a calm, elegant accent wash — two soft radial glows tinted with the
 * user's accent colour (a stronger one anchored top-left, a fainter one bottom-right) fading into the
 * theme background. Deliberately smoother and less busy than the phone's animated multi-blob aurora,
 * which read as messy on a 10-foot screen. Follows the accent and adapts to light / dark.
 *
 * Meant to be the first (bottom) child of a full-screen container, with the real content drawn on top.
 */
@Composable
fun TvAccentBackground(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.background
    // A touch stronger in dark mode, where a faint tint would otherwise vanish against the near-black.
    val dark = isSystemInDarkTheme()
    val strong = if (dark) 0.26f else 0.17f
    val faint = if (dark) 0.15f else 0.10f
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(background)
            .drawBehind {
                val span = max(size.width, size.height)
                // Main accent glow from the top-left.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = strong), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.05f),
                        radius = span * 1.05f,
                    ),
                )
                // A softer echo from the bottom-right so the wash reads as one balanced field, not a
                // single corner spotlight.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = faint), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 1.0f),
                        radius = span * 0.9f,
                    ),
                )
            },
    )
}
