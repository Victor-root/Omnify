package com.looker.droidify.compose.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

/**
 * The styled frame shared by every "big white card" (hero card, repo settings sections, the
 * version list, the settings sections, …): a thin gradient border sweeping from the user's own
 * accent to a lighter (or, for an already light accent, deeper) tone of that same hue and back,
 * instead of a flat single-colour outline or a plain drop shadow. The card itself stays plain white
 * and legible, but its edge reads as deliberately styled rather than a generic Material card that
 * happens to sit on the backdrop (see [FloatingAppCardsBackground]). It keeps to the accent's own
 * hue: a sibling turned round the colour wheel put green into a red theme's border.
 */
@Composable
fun premiumCardBorder(shape: Shape, width: Dp = 1.dp): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    val brush = remember(accent) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent.toArgb(), hsl)
        val lightness = if (hsl[2] > LIGHT_ACCENT) hsl[2] - TONE_STEP else hsl[2] + TONE_STEP
        val tone = Color.hsl(hsl[0], hsl[1] * TONE_SATURATION, lightness.coerceIn(0f, 1f))
        Brush.linearGradient(colors = listOf(accent, tone, accent))
    }
    return Modifier.border(width = width, brush = brush, shape = shape)
}

/** Above this lightness an accent's border sweeps to a deeper tone of it rather than a lighter one. */
private const val LIGHT_ACCENT = 0.6f

/** How far the border's middle tone sits from the accent, in lightness. */
private const val TONE_STEP = 0.2f

/** The middle tone's share of the accent's saturation: a deep teal turned neon cyan at its full one. */
private const val TONE_SATURATION = 0.75f

/** A plain white card in [premiumCardBorder]'s frame, the app page's own card (see [HeroCard]). */
@Composable
fun PremiumCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    // The border on a wrapping Box: a Surface paints its own background over a border it is given.
    Box(modifier = modifier.fillMaxWidth().then(premiumCardBorder(shape))) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}
