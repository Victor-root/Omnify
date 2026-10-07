package com.looker.droidify.compose.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.looker.droidify.datastore.model.BackgroundStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** One full turn of every backdrop's slow drift: long enough to read as calm, never as motion. */
internal const val BackdropCycleMillis = 40_000

/** Width the proportions below were designed at; everything scales from it with the screen width. */
private const val DesignWidth = 300f

/** Side of the tiled grain texture, in pixels. */
private const val GrainSize = 96

/**
 * Every colour a backdrop uses, derived from the user's [accent]: the base the backdrop sits on (a very
 * dark or very light tint of the accent, or pure black for the black theme) and [tone], for the
 * decoration drawn over it. [intensity] scales how strong that decoration is.
 */
internal class BackdropTones(accent: Color, val dark: Boolean, val black: Boolean, private val intensity: Float) {
    private val hue: Float
    private val saturation: Float

    init {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent.toArgb(), hsl)
        hue = hsl[0]
        saturation = hsl[1].coerceAtMost(0.85f)
    }

    val base: Color = when {
        black -> Color.Black
        dark -> Color.hsl(hue, saturation.coerceAtMost(0.35f), 0.07f)
        else -> Color.hsl(hue, saturation.coerceAtMost(0.5f), 0.975f)
    }

    /** [darkValue] in the dark themes, [lightValue] in the light one. */
    fun pick(darkValue: Float, lightValue: Float): Float = if (dark) darkValue else lightValue

    /** The accent's colour turned [shift] degrees around the colour wheel, at [lightness] and [alpha]. */
    fun tone(shift: Float, lightness: Float, alpha: Float): Color = Color.hsl(
        hue = (hue + shift).mod(360f),
        saturation = saturation,
        lightness = lightness.coerceIn(0f, 1f),
        alpha = (alpha * intensity).coerceIn(0f, 1f),
    )
}

/**
 * The drawing of one [BackgroundStyle] at one screen [size], [density] being pixels per dp. Built once
 * per size (see FloatingAppCardsBackground), then drawn every frame by [drawBackdrop] at the current
 * drift phase.
 */
internal class Backdrop(
    private val style: BackgroundStyle,
    private val tones: BackdropTones,
    private val size: Size,
    private val density: Float,
) {
    private val w = size.width
    private val h = size.height

    /** One design pixel (see [DesignWidth]) on this screen. */
    private val u = w / DesignWidth

    private val dotBuckets: List<List<Offset>> =
        if (style == BackgroundStyle.DOTS) dotGrid() else emptyList()

    fun DrawScope.drawBackdrop(phase: Float) {
        drawRect(tones.base)
        when (style) {
            BackgroundStyle.CONTOUR -> contour(phase)
            BackgroundStyle.HALO -> halo(phase)
            BackgroundStyle.SILK -> silk(phase)
            BackgroundStyle.DUNES -> dunes(phase)
            BackgroundStyle.BOKEH -> bokeh(phase)
            BackgroundStyle.FACETS -> facets(phase)
            BackgroundStyle.DOTS -> dots(phase)
            BackgroundStyle.RIPPLES -> ripples(phase)
            BackgroundStyle.FLOW -> flow(phase)
            // Drawn by its own composable on the neutral background, never through here.
            BackgroundStyle.AURORA -> Unit
        }
        // A fine grain keeps the large smooth gradients from looking like plastic. Left off on the black
        // theme, whose whole point is pixels that stay off.
        if (!tones.black) drawRect(GrainBrush, alpha = tones.pick(0.05f, 0.035f))
    }

    private fun DrawScope.glow(x: Float, y: Float, radius: Float, color: Color) {
        val center = Offset(x, y)
        drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center, radius), radius, center)
    }

    private fun DrawScope.contour(phase: Float) {
        glow(w * 0.1f, h * 0.05f, w * 1.1f, tones.tone(0f, tones.pick(0.35f, 0.8f), tones.pick(0.65f, 0.7f)))
        glow(w * 0.95f, h * 0.9f, w, tones.tone(30f, tones.pick(0.32f, 0.84f), tones.pick(0.55f, 0.6f)))
        val center = Offset(w * 0.78f, h * 0.22f)
        val stroke = Stroke(width = density)
        repeat(22) { k ->
            val path = Path()
            var angle = 0f
            while (angle <= 2 * PI.toFloat()) {
                val radius = (30 + k * 19) * u + 9 * u * sin(angle * 3 + k * 0.35f + phase) +
                    6 * u * sin(angle * 5 - k * 0.2f)
                val x = center.x + radius * cos(angle) * 1.1f
                val y = center.y + radius * sin(angle)
                if (angle == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                angle += 0.05f
            }
            path.close()
            drawPath(path, tones.tone(k * 2f, tones.pick(0.65f, 0.4f), tones.pick(0.16f, 0.13f)), style = stroke)
        }
    }

    private fun DrawScope.halo(phase: Float) {
        val drift = w * 0.04f
        glow(
            w * 0.15f + drift * sin(phase), h * 0.08f + drift * cos(phase), w,
            tones.tone(0f, tones.pick(0.42f, 0.78f), tones.pick(0.75f, 0.65f)),
        )
        glow(
            w + drift * cos(phase), h * 0.42f + drift * sin(phase), w * 0.95f,
            tones.tone(28f, tones.pick(0.3f, 0.86f), tones.pick(0.6f, 0.55f)),
        )
        glow(
            w * 0.1f - drift * sin(phase), h * 0.95f - drift * cos(phase), w * 1.05f,
            tones.tone(-30f, tones.pick(0.36f, 0.82f), tones.pick(0.5f, 0.6f)),
        )
        if (tones.dark) {
            drawRect(
                Brush.radialGradient(
                    0.2f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.45f),
                    center = Offset(w / 2, h * 0.45f),
                    radius = h * 0.8f,
                ),
            )
        }
    }

    private fun DrawScope.silk(phase: Float) {
        glow(w * 0.9f, h * 0.1f, w * 1.1f, tones.tone(20f, tones.pick(0.3f, 0.85f), tones.pick(0.5f, 0.6f)))
        repeat(5) { k ->
            val offset = k * 0.042f * h
            val wobble = sin(phase + k * 0.7f) * 0.03f * h
            val path = Path().apply {
                moveTo(-0.13f * w, h * 0.62f + offset)
                cubicTo(
                    w * 0.35f, h * 0.3f + offset + wobble,
                    w * 0.55f, h * 0.95f + offset * 0.5f - wobble,
                    w * 1.13f, h * 0.4f + offset * 0.8f,
                )
            }
            val brush = Brush.linearGradient(
                0f to tones.tone(-20f + k * 8, tones.pick(0.45f, 0.7f), 0f),
                0.45f to tones.tone(k * 6f, tones.pick(0.55f, 0.62f), tones.pick(0.32f, 0.3f) - k * 0.04f),
                1f to tones.tone(30f, tones.pick(0.6f, 0.75f), 0f),
                start = Offset(0f, h * 0.2f),
                end = Offset(w, h * 0.8f),
            )
            val width = (46 - k * 6) * u
            // Wider, fainter passes under the ribbon itself soften its edges into a glow.
            for ((spread, alpha) in SilkPasses) {
                drawPath(path, brush, alpha = alpha, style = Stroke(width * spread, cap = StrokeCap.Round))
            }
        }
    }

    private fun DrawScope.dunes(phase: Float) {
        glow(w * 0.3f, 0f, w * 1.2f, tones.tone(-10f, tones.pick(0.35f, 0.82f), tones.pick(0.55f, 0.6f)))
        val step = 8 * density
        repeat(5) { k ->
            val direction = if (k % 2 == 0) 1 else -1
            val path = Path()
            var x = 0f
            while (true) {
                val y = h * (0.5f + 0.1f * k) +
                    0.04f * h * sin(2 * PI.toFloat() * 0.8f * x / w + 1.3f * k + phase * direction)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                if (x >= w) break
                x = (x + step).coerceAtMost(w)
            }
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
            drawPath(
                path,
                tones.tone(-16f + 8 * k, tones.pick(0.12f + 0.035f * k, 0.92f - 0.04f * k), tones.pick(0.6f, 0.55f)),
            )
        }
    }

    private fun DrawScope.bokeh(phase: Float) {
        glow(w * 0.5f, h * 0.3f, w * 1.1f, tones.tone(0f, tones.pick(0.3f, 0.85f), tones.pick(0.5f, 0.55f)))
        val rim = Stroke(width = density)
        BokehSpots.forEachIndexed { index, spot ->
            val center = Offset(w * spot.x, h * spot.y + sin(phase + index) * 0.02f * h)
            val radius = w * spot.radius
            drawCircle(tones.tone(spot.shift, tones.pick(0.55f, 0.7f), spot.alpha), radius, center)
            drawCircle(tones.tone(spot.shift, tones.pick(0.7f, 0.6f), spot.alpha * 0.8f), radius, center, style = rim)
        }
    }

    private fun DrawScope.facets(phase: Float) {
        glow(w * 0.85f, h * 0.1f, w, tones.tone(15f, tones.pick(0.33f, 0.84f), tones.pick(0.55f, 0.6f)))
        FacetSpecs.forEachIndexed { index, facet ->
            val side = w * facet.size
            val center = Offset(w * facet.x, h * facet.y)
            rotate(facet.rotation + sin(phase + index) * 4f, pivot = center) {
                drawRoundRect(
                    color = tones.tone(facet.shift, tones.pick(0.5f, 0.7f), facet.alpha),
                    topLeft = Offset(center.x - side / 2, center.y - side / 2),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.18f),
                )
            }
        }
    }

    private fun DrawScope.dots(phase: Float) {
        val drift = w * 0.04f
        glow(
            w * 0.2f + drift * sin(phase), h * 0.15f + drift * cos(phase), w * 1.1f,
            tones.tone(0f, tones.pick(0.35f, 0.8f), tones.pick(0.6f, 0.65f)),
        )
        glow(w * 0.95f, h * 0.9f, w * 0.9f, tones.tone(30f, tones.pick(0.32f, 0.84f), tones.pick(0.45f, 0.5f)))
        dotBuckets.forEachIndexed { bucket, points ->
            if (bucket == 0 || points.isEmpty()) return@forEachIndexed
            drawPoints(
                points = points,
                pointMode = PointMode.Points,
                color = tones.tone(0f, tones.pick(0.75f, 0.35f), tones.pick(0.45f, 0.35f) * bucket / DotLevels),
                strokeWidth = 2.6f * density,
                cap = StrokeCap.Round,
            )
        }
    }

    /** The grid's dots grouped by how bright they are, from 0 (invisible) to [DotLevels], fading away
     *  from the top-left corner. Grouped so a whole level is one draw call. */
    private fun dotGrid(): List<List<Offset>> {
        val spacing = 18 * density
        val focus = Offset(w * 0.2f, h * 0.15f)
        val reach = hypot(w, h) * 0.9f
        val buckets = List(DotLevels + 1) { mutableListOf<Offset>() }
        var y = spacing / 2
        while (y < h) {
            var x = spacing / 2
            while (x < w) {
                val strength = (1 - hypot(x - focus.x, y - focus.y) / reach).coerceIn(0f, 1f).pow(1.5f)
                buckets[(strength * DotLevels).toInt().coerceAtMost(DotLevels)] += Offset(x, y)
                x += spacing
            }
            y += spacing
        }
        return buckets
    }

    private fun DrawScope.ripples(phase: Float) {
        val origin = Offset(-0.1f * w, 1.05f * h)
        glow(origin.x, origin.y, w * 1.1f, tones.tone(0f, tones.pick(0.35f, 0.8f), tones.pick(0.65f, 0.65f)))
        glow(w, 0f, w, tones.tone(30f, tones.pick(0.3f, 0.85f), tones.pick(0.5f, 0.55f)))
        val step = 28 * density
        val reach = hypot(w * 1.1f, h * 1.05f) + step
        // The rings move outward by one spacing per cycle, so the loop restarts exactly where it began.
        val offset = phase / (2 * PI.toFloat()) * step
        val stroke = Stroke(width = density)
        var k = 0
        while (true) {
            val radius = k * step + offset
            if (radius > reach) break
            val fade = 1 - radius / reach
            drawCircle(
                tones.tone(k * 1.5f, tones.pick(0.62f, 0.42f), tones.pick(0.22f, 0.18f) * fade),
                radius, origin, style = stroke,
            )
            k++
        }
    }

    private fun DrawScope.flow(phase: Float) {
        glow(0f, h * 0.4f, w * 1.1f, tones.tone(-15f, tones.pick(0.33f, 0.82f), tones.pick(0.55f, 0.6f)))
        glow(w, h * 0.85f, w, tones.tone(25f, tones.pick(0.3f, 0.85f), tones.pick(0.5f, 0.55f)))
        val brush = Brush.horizontalGradient(
            0f to tones.tone(-25f, tones.pick(0.62f, 0.45f), tones.pick(0.08f, 0.06f)),
            0.5f to tones.tone(0f, tones.pick(0.62f, 0.45f), tones.pick(0.22f, 0.18f)),
            1f to tones.tone(25f, tones.pick(0.62f, 0.45f), tones.pick(0.08f, 0.06f)),
        )
        val stroke = Stroke(width = density)
        val step = 6 * density
        val lines = 28
        repeat(lines) { k ->
            val baseline = h * (0.12f + 0.028f * k)
            val amplitude = h * 0.05f * sin(PI.toFloat() * k / (lines - 1)) + h * 0.01f
            val path = Path()
            var x = 0f
            while (true) {
                val turn = 2 * PI.toFloat() * x / w
                val y = baseline + amplitude * sin(turn * 0.9f + k * 0.22f + phase) +
                    h * 0.012f * sin(turn * 2.1f - k * 0.15f - phase)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                if (x >= w) break
                x = (x + step).coerceAtMost(w)
            }
            drawPath(path, brush, style = stroke)
        }
    }
}

/** (width multiplier, alpha) of each pass that draws one silk ribbon, widest and faintest first. */
private val SilkPasses = listOf(1.7f to 0.25f, 1.3f to 0.45f, 1f to 0.7f)

/** How many brightness levels the dot grid is split into. */
private const val DotLevels = 5

private class BokehSpot(val x: Float, val y: Float, val radius: Float, val shift: Float, val alpha: Float)

/** Fixed (seeded) so the discs stay where they are from one frame, and one launch, to the next. */
private val BokehSpots: List<BokehSpot> = Random(7).let { random ->
    List(16) {
        BokehSpot(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = 0.04f + random.nextFloat() * 0.12f,
            shift = -35f + random.nextFloat() * 70f,
            alpha = 0.1f + random.nextFloat() * 0.16f,
        )
    }
}

private class FacetSpec(
    val x: Float,
    val y: Float,
    val size: Float,
    val rotation: Float,
    val shift: Float,
    val alpha: Float,
)

private val FacetSpecs = listOf(
    FacetSpec(0.85f, 0.12f, 0.75f, 18f, 0f, 0.14f),
    FacetSpec(1.05f, 0.35f, 0.6f, 38f, 20f, 0.1f),
    FacetSpec(0.55f, 0.02f, 0.5f, 28f, -15f, 0.08f),
    FacetSpec(0.1f, 0.78f, 0.8f, 24f, -25f, 0.13f),
    FacetSpec(-0.1f, 0.55f, 0.55f, 40f, 10f, 0.09f),
    FacetSpec(0.4f, 0.98f, 0.6f, 15f, 30f, 0.1f),
    FacetSpec(0.95f, 0.85f, 0.45f, 32f, -5f, 0.08f),
)

/** A small tile of random grey levels, repeated across the screen as film-like grain. */
private val GrainBrush: ShaderBrush by lazy {
    val random = Random(11)
    val pixels = IntArray(GrainSize * GrainSize) {
        val level = random.nextInt(256)
        android.graphics.Color.rgb(level, level, level)
    }
    val bitmap = Bitmap.createBitmap(pixels, GrainSize, GrainSize, Bitmap.Config.ARGB_8888)
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
