package com.looker.droidify.compose.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import java.text.NumberFormat

/**
 * How far a synchronisation has come ([fraction], 0f..1f), beside its progress bar. As wide as the
 * widest reading (100 %) from the start, so the bar beside it keeps its length as the number climbs.
 * Truncated, so it never reads 100 % before everything is done.
 */
@Composable
fun SyncPercent(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    // A locale change recreates the activity, so one instance is enough.
    val percent = remember { NumberFormat.getPercentInstance() }
    val style = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    val width = with(LocalDensity.current) { textMeasurer.measure(percent.format(1.0), style).size.width.toDp() }
    Text(
        text = percent.format((fraction * 100).toInt() / 100.0),
        style = style,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier.width(width),
    )
}
