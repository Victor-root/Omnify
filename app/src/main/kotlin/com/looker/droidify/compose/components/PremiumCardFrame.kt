package com.looker.droidify.compose.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The frame shared by every "big white card" (hero card, repo settings sections, the version list,
 * the settings sections, …): no outline, the card lifted off the page by a soft halo in the user's
 * own accent, so it reads as deliberately styled rather than a generic Material card that happens to
 * sit on the backdrop (see [FloatingAppCardsBackground]). Drawn by hand, a wide soft layer under a
 * close one: Material's elevation shadows are kept faint by the system whatever colour they are
 * given. A halo barely shows on a dark page, so the dark themes add a faint outline in the accent to
 * keep the card's edge. Clips nothing: the halo is drawn around the card, which draws its own shape.
 */
@Composable
fun premiumCardFrame(shape: Shape): Modifier {
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    return Modifier
        .drawBehind {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                .asAndroidPath()
            drawIntoCanvas { canvas ->
                HaloLayers.forEach { layer ->
                    paint.color = surface.toArgb()
                    paint.setShadowLayer(
                        layer.blur.toPx(),
                        0f,
                        layer.drop.toPx(),
                        accent.copy(alpha = layer.alpha).toArgb(),
                    )
                    canvas.nativeCanvas.drawPath(path, paint)
                }
            }
        }
        .then(if (dark) Modifier.border(1.dp, accent.copy(alpha = DARK_EDGE_ALPHA), shape) else Modifier)
}

/** One layer of the halo: how soft it is, how far below the card it falls, and how strong. */
private class HaloLayer(val blur: Dp, val drop: Dp, val alpha: Float)

private val HaloLayers = listOf(
    HaloLayer(blur = 17.dp, drop = 14.dp, alpha = 0.22f),
    HaloLayer(blur = 4.dp, drop = 3.dp, alpha = 0.1f),
)

private const val DARK_EDGE_ALPHA = 0.3f

/** A plain white card in [premiumCardFrame]'s frame, the app page's own card (see [HeroCard]). */
@Composable
fun PremiumCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.large
    // The frame on a wrapping Box: a Surface paints its own background over a frame it is given.
    Box(modifier = modifier.fillMaxWidth().then(premiumCardFrame(shape))) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}
