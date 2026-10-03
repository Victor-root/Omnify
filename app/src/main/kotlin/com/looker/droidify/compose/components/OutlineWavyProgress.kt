package com.looker.droidify.compose.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val RingStroke = 4.dp
private val WaveAmplitude = 1.5.dp
private val WaveLength = 15.dp

/** Distance between two sampled points of the wave: small enough to read as a smooth curve. */
private val WaveStep = 2.dp
private const val WaveCycleMillis = 1000
private const val SpinMillis = 1600
private const val SweepMillis = 800
private const val MinSweep = 0.15f
private const val MaxSweep = 0.65f

/** Share of the progress at each end over which the wave flattens, so a full ring closes cleanly. */
private const val FlatEdge = 0.1f

/**
 * The wavy progress ring of [androidx.compose.material3.CircularWavyProgressIndicator], but drawn along
 * a rounded rectangle instead of a circle, so it can run all the way around a square-ish app icon.
 * A [cornerSize] of 50 % gives back a plain circle.
 *
 * It fills the space it is given and stays inside it. [progress] null means an indeterminate ring (an
 * arc that spins and breathes), otherwise the ring fills clockwise from the top. The track is a plain
 * neutral outline, like the other wavy indicators of the app.
 */
@Composable
fun OutlineWavyProgressIndicator(
    cornerSize: CornerSize,
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val transition = rememberInfiniteTransition(label = "outlineWavy")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2 * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(WaveCycleMillis, easing = LinearEasing)),
        label = "wavePhase",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SpinMillis, easing = LinearEasing)),
        label = "spin",
    )
    val sweep by transition.animateFloat(
        initialValue = MinSweep,
        targetValue = MaxSweep,
        animationSpec = infiniteRepeatable(
            animation = tween(SweepMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokePx = RingStroke.toPx()
        val amplitudePx = WaveAmplitude.toPx()
        // Keeps the stroke and the wave's crests inside the box instead of being clipped by it.
        val inset = strokePx / 2 + amplitudePx
        val bounds = Rect(inset, inset, size.width - inset, size.height - inset)
        val radius = (cornerSize.toPx(size, this) - inset).coerceIn(0f, bounds.minDimension / 2)
        val outline = roundedRectPath(bounds, radius)
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(outline, trackColor, style = stroke)

        val measure = PathMeasure().apply { setPath(outline, true) }
        val fraction = progress?.invoke()?.coerceIn(0f, 1f)
        val from: Float
        val to: Float
        val amplitude: Float
        if (fraction == null) {
            from = spin * measure.length
            to = from + sweep * measure.length
            amplitude = amplitudePx
        } else {
            from = 0f
            to = fraction * measure.length
            amplitude = amplitudePx * minOf(1f, fraction / FlatEdge, (1f - fraction) / FlatEdge)
        }
        drawPath(
            path = wavyPath(measure, from, to, amplitude, WaveLength.toPx(), wavePhase, WaveStep.toPx()),
            color = color,
            style = stroke,
        )
    }
}

/** A rounded rectangle traced clockwise from the middle of its top edge. */
private fun roundedRectPath(bounds: Rect, radius: Float): Path = Path().apply {
    val diameter = radius * 2
    moveTo(bounds.center.x, bounds.top)
    lineTo(bounds.right - radius, bounds.top)
    arcTo(Rect(bounds.right - diameter, bounds.top, bounds.right, bounds.top + diameter), -90f, 90f, false)
    lineTo(bounds.right, bounds.bottom - radius)
    arcTo(Rect(bounds.right - diameter, bounds.bottom - diameter, bounds.right, bounds.bottom), 0f, 90f, false)
    lineTo(bounds.left + radius, bounds.bottom)
    arcTo(Rect(bounds.left, bounds.bottom - diameter, bounds.left + diameter, bounds.bottom), 90f, 90f, false)
    lineTo(bounds.left, bounds.top + radius)
    arcTo(Rect(bounds.left, bounds.top, bounds.left + diameter, bounds.top + diameter), 180f, 90f, false)
    close()
}

/**
 * The stretch of [measure]'s outline between the distances [from] and [to], bent into a sine wave
 * across the outline. [to] may run past the outline's length: the stretch then wraps around the start,
 * and the wave stays continuous across it.
 */
private fun wavyPath(
    measure: PathMeasure,
    from: Float,
    to: Float,
    amplitude: Float,
    wavelength: Float,
    phase: Float,
    step: Float,
): Path {
    val path = Path()
    var distance = from
    while (true) {
        val d = minOf(distance, to)
        val at = d % measure.length
        val position = measure.getPosition(at)
        val tangent = measure.getTangent(at)
        val offset = amplitude * sin(2 * PI.toFloat() * d / wavelength + phase)
        val point = Offset(position.x - tangent.y * offset, position.y + tangent.x * offset)
        if (d == from) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        if (d >= to) break
        distance += step
    }
    return path
}
