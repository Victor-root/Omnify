package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

/** Where a corner of the icon is probed, as a share of its side: deep enough to sit inside a rounded
 *  square's corner arc, yet still outside a circle's edge. */
private const val CornerProbe = 0.1f

/** At or below this alpha a probed pixel counts as empty. */
private const val EmptyAlpha = 16

/** Room left around the widest percentage (100 %) inside the round badge of an updating icon. */
private val PercentBadgePadding = 3.dp

/** Whether this rendered icon is a round logo: something is drawn at its centre, but all four corners
 *  are empty. A full square icon, or the card every icon sits on on Android TV, fills its corners. */
private fun ImageBitmap.isRoundLogo(): Boolean {
    val pixel = IntArray(1)
    fun alphaAt(x: Float, y: Float): Int {
        val startX = (x * (width - 1)).toInt()
        val startY = (y * (height - 1)).toInt()
        readPixels(pixel, startX = startX, startY = startY, width = 1, height = 1)
        return pixel[0] ushr 24
    }
    val ends = listOf(CornerProbe, 1f - CornerProbe)
    return alphaAt(0.5f, 0.5f) > EmptyAlpha && ends.all { x -> ends.all { y -> alphaAt(x, y) <= EmptyAlpha } }
}

/**
 * An app's [icon] as an update shows it: while [isUpdating] (downloaded or installed by "update all",
 * or installed from the app's page), dimmed under a wavy ring that runs all the way around it, filling
 * with [updateFraction] and carrying the percentage while a download's size is known, spinning otherwise.
 * [idleBadge] (the installed check) is drawn over it the rest of the time. Shared by the phone's tiles
 * and the TV's cards, so an update reads the same on both.
 */
@Composable
fun UpdateRingIcon(
    isUpdating: Boolean,
    updateFraction: Float?,
    modifier: Modifier = Modifier,
    idleBadge: @Composable BoxScope.() -> Unit = {},
    icon: @Composable () -> Unit,
) {
    // The ring follows the icon's outline: a rounded square, or a circle for a round logo. The icon is a
    // caller-supplied slot with no shape to read, so it is recorded here and its corners are looked at
    // when the update starts.
    val iconLayer = rememberGraphicsLayer()
    var roundIcon by remember { mutableStateOf(false) }
    if (isUpdating) {
        LaunchedEffect(Unit) {
            // An icon that appears mid-update has not been drawn yet: wait for that first frame.
            withFrameNanos { }
            roundIcon = iconLayer.size != IntSize.Zero && iconLayer.toImageBitmap().isRoundLogo()
        }
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        Box(
            modifier = (if (isUpdating) Modifier.graphicsLayer { alpha = 0.35f } else Modifier)
                // Inside the dimming, so the recording keeps the icon's own alpha.
                .drawWithContent {
                    iconLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(iconLayer)
                },
        ) {
            icon()
        }
        if (isUpdating) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.matchParentSize()) {
                OutlineWavyProgressIndicator(
                    cornerSize = if (roundIcon) CornerSize(50) else MaterialTheme.shapes.large.topStart,
                    progress = updateFraction?.let { fraction -> { fraction } },
                )
                if (updateFraction != null) {
                    // On an opaque round badge, as the dimmed icon underneath would otherwise show
                    // through the digits. The circle is sized once for the widest reading (100 %), so
                    // it keeps the same size as the number climbs. Truncated like the ring, so it
                    // never reads 100 % before the ring is full.
                    // A locale change recreates the activity, so one instance per icon is enough.
                    val percent = remember { NumberFormat.getPercentInstance() }
                    val style = MaterialTheme.typography.labelSmall
                    val textMeasurer = rememberTextMeasurer()
                    val diameter = with(LocalDensity.current) {
                        val widest = textMeasurer.measure(percent.format(1.0), style).size
                        maxOf(widest.width, widest.height).toDp() + PercentBadgePadding * 2
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(diameter)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    ) {
                        Text(
                            text = percent.format((updateFraction * 100).toInt() / 100.0),
                            style = style,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        } else {
            idleBadge()
        }
    }
}
