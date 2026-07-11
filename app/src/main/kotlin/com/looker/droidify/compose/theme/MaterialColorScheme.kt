package com.looker.droidify.compose.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.MaterialColors
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MatR

/**
 * Reads the Material 3 color roles from this (already themed) [Context] and turns them into a
 * Compose [ColorScheme].
 *
 * The activity's theme is recolored at runtime from the user's chosen accent color (or Material
 * You) via [com.google.android.material.color.DynamicColors], so reading it back here keeps the
 * Compose screens perfectly in sync with the classic View screens — a single source of truth.
 *
 * [fallback] supplies a value for any role the theme does not define (e.g. scrim).
 */
fun Context.toComposeColorScheme(fallback: ColorScheme): ColorScheme {
    fun color(attr: Int, default: Color): Color =
        Color(MaterialColors.getColor(this, attr, default.toArgb()))
    return lightColorScheme(
        primary = color(AppCompatR.attr.colorPrimary, fallback.primary),
        onPrimary = color(MatR.attr.colorOnPrimary, fallback.onPrimary),
        primaryContainer = color(MatR.attr.colorPrimaryContainer, fallback.primaryContainer),
        onPrimaryContainer = color(MatR.attr.colorOnPrimaryContainer, fallback.onPrimaryContainer),
        secondary = color(MatR.attr.colorSecondary, fallback.secondary),
        onSecondary = color(MatR.attr.colorOnSecondary, fallback.onSecondary),
        secondaryContainer = color(MatR.attr.colorSecondaryContainer, fallback.secondaryContainer),
        onSecondaryContainer = color(
            MatR.attr.colorOnSecondaryContainer,
            fallback.onSecondaryContainer,
        ),
        tertiary = color(MatR.attr.colorTertiary, fallback.tertiary),
        onTertiary = color(MatR.attr.colorOnTertiary, fallback.onTertiary),
        tertiaryContainer = color(MatR.attr.colorTertiaryContainer, fallback.tertiaryContainer),
        onTertiaryContainer = color(
            MatR.attr.colorOnTertiaryContainer,
            fallback.onTertiaryContainer,
        ),
        error = color(AppCompatR.attr.colorError, fallback.error),
        onError = color(MatR.attr.colorOnError, fallback.onError),
        errorContainer = color(MatR.attr.colorErrorContainer, fallback.errorContainer),
        onErrorContainer = color(MatR.attr.colorOnErrorContainer, fallback.onErrorContainer),
        background = color(android.R.attr.colorBackground, fallback.background),
        onBackground = color(MatR.attr.colorOnBackground, fallback.onBackground),
        surface = color(MatR.attr.colorSurface, fallback.surface),
        onSurface = color(MatR.attr.colorOnSurface, fallback.onSurface),
        surfaceVariant = color(MatR.attr.colorSurfaceVariant, fallback.surfaceVariant),
        onSurfaceVariant = color(MatR.attr.colorOnSurfaceVariant, fallback.onSurfaceVariant),
        outline = color(MatR.attr.colorOutline, fallback.outline),
        outlineVariant = color(MatR.attr.colorOutlineVariant, fallback.outlineVariant),
        scrim = fallback.scrim,
        inverseSurface = color(MatR.attr.colorSurfaceInverse, fallback.inverseSurface),
        inverseOnSurface = color(MatR.attr.colorOnSurfaceInverse, fallback.inverseOnSurface),
        inversePrimary = color(MatR.attr.colorPrimaryInverse, fallback.inversePrimary),
        surfaceDim = color(MatR.attr.colorSurfaceDim, fallback.surfaceDim),
        surfaceBright = color(MatR.attr.colorSurfaceBright, fallback.surfaceBright),
        surfaceContainerLowest = color(
            MatR.attr.colorSurfaceContainerLowest,
            fallback.surfaceContainerLowest,
        ),
        surfaceContainerLow = color(
            MatR.attr.colorSurfaceContainerLow,
            fallback.surfaceContainerLow,
        ),
        surfaceContainer = color(MatR.attr.colorSurfaceContainer, fallback.surfaceContainer),
        surfaceContainerHigh = color(
            MatR.attr.colorSurfaceContainerHigh,
            fallback.surfaceContainerHigh,
        ),
        surfaceContainerHighest = color(
            MatR.attr.colorSurfaceContainerHighest,
            fallback.surfaceContainerHighest,
        ),
    )
}
