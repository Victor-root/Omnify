package com.looker.droidify.compose.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.looker.droidify.compose.theme.LocalIsTelevision

/**
 * Ambient, aurora-style glow behind a screen's real content — a handful of large, softly overlapping
 * colour fields drifting very slowly and blending into one another, like the diffuse colour wash on
 * Aurora Store's own icon rather than any literal shape or a handful of separate dots. Purely
 * cosmetic: ignores touch, never intercepts input.
 *
 * Meant to be the FIRST child inside a screen's own content [Box] (right after applying
 * `contentPadding`, before the real list/content), so whatever the screen composes afterwards in
 * that same Box draws on top and is never obscured by this.
 *
 * Off on TV: a 10-foot UI already asks a lot of weaker set-top GPUs, and this is pure decoration
 * the user isn't looking closely at from the couch — not worth the extra always-on compositing.
 */
@Composable
fun FloatingAppCardsBackground(modifier: Modifier = Modifier) {
    if (LocalIsTelevision.current) return
    val dark = isSystemInDarkTheme()
    val alpha = if (dark) DarkAlpha else LightAlpha
    // The user's own chosen accent (MaterialTheme.colorScheme.primary is that raw accent, not a
    // muted derived tone — see DroidifyTheme.withVividAccent) as the seed for the whole palette,
    // instead of a fixed rainbow unrelated to their theme.
    val accent = MaterialTheme.colorScheme.primary
    val palette = remember(accent) { auroraPaletteFrom(accent) }
    val transition = rememberInfiniteTransition(label = "aurora")
    // One State pair per blob, all driven by the same shared transition — read directly inside the
    // Canvas draw lambda below (not hoisted via `by`), so only that draw phase re-runs on each frame
    // instead of the whole composable recomposing.
    val drifts = AURORA_BLOB_SPECS.map { spec -> spec.driftXState(transition) to spec.driftYState(transition) }
    // Everything is drawn into ONE Canvas/layer with additive blending, not one Box per blob: several
    // separately composited layers (even borderless ones) still showed as visually distinct dots
    // where they overlapped instead of merging into a continuous colour field. A single draw pass
    // blending every gradient with BlendMode.Plus is what actually reads as one flowing multi-colour
    // wash.
    // BlendMode.Plus needs an alpha channel to blend against — drawn straight onto the screen's own
    // opaque backing surface it composites wrong (nothing visible at all). Forcing this Canvas into
    // its own offscreen ARGB layer first gives it one; that whole layer is then composited normally
    // on top of the real background.
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        AURORA_BLOB_SPECS.forEachIndexed { index, spec ->
            val (driftX, driftY) = drifts[index]
            val center = Offset(
                x = size.width * (0.5f + spec.xBias / 2f) + spec.driftDp.dp.toPx() * driftX.value,
                y = size.height * (0.5f + spec.yBias / 2f) + spec.driftDp.dp.toPx() * driftY.value,
            )
            val radius = spec.sizeDp.dp.toPx()
            val color = palette[index]
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
                blendMode = BlendMode.Plus,
            )
        }
    }
}

private data class AuroraBlobSpec(
    val xBias: Float,
    val yBias: Float,
    val sizeDp: Int,
    val driftDp: Int,
    val durationMs: Int,
    val delayMs: Int,
)

/** [sizeDp] is large on purpose (well beyond a typical phone's width): with several such fields
 *  overlapping across most of the screen and blending additively, the result reads as one
 *  continuous colour wash rather than a handful of separate glowing dots. One entry per colour in
 *  [auroraPaletteFrom]'s output — matched by index in the draw loop above. */
private val AURORA_BLOB_SPECS = listOf(
    AuroraBlobSpec(-0.85f, -0.9f, 340, 42, 18000, 0),
    AuroraBlobSpec(0.9f, -0.75f, 300, 38, 21000, 3000),
    AuroraBlobSpec(-0.8f, 0.15f, 360, 46, 19500, 6000),
    AuroraBlobSpec(0.85f, 0.4f, 310, 40, 23000, 1500),
    AuroraBlobSpec(-0.6f, 0.9f, 330, 44, 20000, 4500),
    AuroraBlobSpec(0.65f, 0.92f, 290, 36, 22000, 7500),
)

/** Builds an analogous palette (one entry per [AURORA_BLOB_SPECS] item) around the user's own
 *  accent colour by rotating its hue in HSV space — the same "harmonious variations of one seed
 *  colour" technique most palette generators use, rather than picking unrelated hues. Saturation
 *  and value are nudged too (not just hue) so the palette has some lightness variety instead of
 *  every blob reading as the exact same brightness. */
private fun auroraPaletteFrom(seed: Color): List<Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    return HUE_OFFSETS.map { (hueOffset, satFactor, valueFactor) ->
        val shifted = floatArrayOf(
            (hsv[0] + hueOffset).mod(360f),
            (hsv[1] * satFactor).coerceIn(0.35f, 1f),
            (hsv[2] * valueFactor).coerceIn(0.55f, 1f),
        )
        Color(android.graphics.Color.HSVToColor(shifted))
    }
}

/** (hue shift in degrees, saturation multiplier, value multiplier) per blob — spread around the
 *  colour wheel rather than clustered, so the palette reads as varied even from a fairly narrow
 *  seed hue. */
private val HUE_OFFSETS = listOf(
    Triple(-50f, 0.9f, 1.05f),
    Triple(-24f, 1f, 0.9f),
    Triple(0f, 1f, 1f),
    Triple(20f, 0.85f, 1.1f),
    Triple(42f, 1f, 0.95f),
    Triple(65f, 0.8f, 1f),
)

// Additive blending brightens every overlap, so the base strength stays a little lower than the
// single-blob version did to avoid a washed-out look where three or four fields stack up.
private const val LightAlpha = 0.14f
private const val DarkAlpha = 0.22f

/** Two independent, oddly-ratioed axes (this duration vs. its own *1.3) so the drift traces a slow,
 *  organic loop rather than a straight line back and forth — an aurora doesn't pulse on a metronome.
 *  initialStartOffset staggers each blob's phase, same idea as a negative CSS animation-delay. */
@Composable
private fun AuroraBlobSpec.driftXState(transition: InfiniteTransition): State<Float> =
    transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = durationMs, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(delayMs, StartOffsetType.FastForward),
        ),
        label = "driftX",
    )

@Composable
private fun AuroraBlobSpec.driftYState(transition: InfiniteTransition): State<Float> =
    transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (durationMs * 1.3f).toInt(), easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(delayMs + 900, StartOffsetType.FastForward),
        ),
        label = "driftY",
    )
