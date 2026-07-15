package com.looker.droidify.compose.components

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The styled frame shared by every "big white card" (hero card, repo settings sections, the
 * version list, …): a thin gradient border sweeping between the user's own accent colour and a
 * hue-shifted sibling of it, instead of a flat single-colour outline or a plain drop shadow — the
 * card itself stays plain white and legible, but its edge reads as deliberately styled rather than
 * a generic Material card that happens to sit on the aurora background (see
 * [FloatingAppCardsBackground]).
 */
@Composable
fun premiumCardBorder(shape: Shape, width: Dp = 1.dp): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    // A wide hue swing (70°, not the original 35°) so the two ends of the gradient actually read
    // as two different colours rather than a single flat tone with a barely-perceptible shift —
    // confirmed too subtle on a saturated green accent, where a 35° shift still looks like plain
    // green. A touch less saturation/value on the sibling too, so it doesn't just look like a
    // brighter or dimmer version of the same hue.
    val sibling = Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(
                (hsv[0] + 70f).mod(360f),
                (hsv[1] * 0.85f).coerceIn(0f, 1f),
                (hsv[2] * 0.9f).coerceIn(0f, 1f),
            ),
        ),
    )
    val brush = Brush.linearGradient(colors = listOf(accent, sibling, accent))
    return Modifier.border(width = width, brush = brush, shape = shape)
}
