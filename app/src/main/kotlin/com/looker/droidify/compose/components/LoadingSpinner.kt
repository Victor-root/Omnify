package com.looker.droidify.compose.components

import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * [CircularWavyProgressIndicator] with an explicit, neutral track colour instead of the default
 * container role: that role reads the same raw-vivid colour as the indicator itself when
 * ScopedAccentColor's icon-matching mode is on (see Theme.kt's withVividAccent), making the track and
 * the indicator indistinguishable. A plain surface tone keeps the track visible in every mode and
 * scope, the same fix already applied to the download/install progress bars (see
 * DownloadWavyProgressIndicator in InstallProgress.kt) and the supported-languages one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingSpinner(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    CircularWavyProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

/**
 * The straight-line counterpart of [LoadingSpinner], with the same track-colour fix, for waits that
 * span a row rather than sit inside one: a bar drawn across the available width reads as "this whole
 * strip is busy" where a small circle reads as one detail loading.
 *
 * Pass a width-constraining modifier ([androidx.compose.foundation.layout.fillMaxWidth] and any
 * padding); the indicator's own height is left to Material so it matches every other wavy bar in the
 * app (download and install progress, sync, supported languages).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingBar(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearWavyProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}
