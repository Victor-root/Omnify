package com.looker.droidify.compose.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.looker.droidify.datastore.DEFAULT_THEME_COLOR
import com.looker.droidify.utility.common.IconAccent
import com.looker.droidify.utility.common.device.isTelevision
import com.looker.droidify.utility.common.wallpaperAccentColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Android TV's default density (matched to the same dp-based layouts phones use, plus this app's own
 *  10-foot-UI size bumps — bigger tiles, bigger buttons, bigger focus scale) rendered noticeably larger
 *  than intended on an actual TV screen. A single uniform shrink here brings the *whole* interface down
 *  a notch — not just individual TV-only sizes sprinkled across screens — since every dp and sp everywhere
 *  is measured against [LocalDensity]. */
private const val TV_UI_SCALE = 0.85f

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)

/**
 * Forces neutral surfaces (pure white in light, dark grey or pure black in dark) and disables the
 * tonal-elevation tint, keeping the accent only on primary/secondary/tertiary roles (buttons, section
 * titles, indicators). Material 3 otherwise derives every surface — and the elevation overlay — from
 * the seed colour, which tinted the whole background with the accent. Mirrors the maintainer's
 * MaterialFiles fork: white/black background, accent only as an accent.
 *
 * [amoled] picks which dark ladder applies: true-black surfaces (the actual point of an AMOLED theme,
 * OLED pixels off) for the Black theme, or a standard Material dark grey for the plain Dark theme —
 * the Dark and Black options rendered identically until this distinction existed.
 */
private fun ColorScheme.withNeutralSurfaces(dark: Boolean, amoled: Boolean): ColorScheme = if (dark) {
    if (amoled) {
        copy(
            background = Color(0xFF000000),
            onBackground = Color(0xFFE6E6E6),
            surface = Color(0xFF000000),
            onSurface = Color(0xFFE6E6E6),
            surfaceVariant = Color(0xFF333333),
            onSurfaceVariant = Color(0xFFC2C2C2),
            surfaceDim = Color(0xFF000000),
            surfaceBright = Color(0xFF3A3A3A),
            surfaceContainerLowest = Color(0xFF000000),
            surfaceContainerLow = Color(0xFF141414),
            surfaceContainer = Color(0xFF1A1A1A),
            surfaceContainerHigh = Color(0xFF242424),
            surfaceContainerHighest = Color(0xFF2E2E2E),
            outline = Color(0xFF8A8A8A),
            outlineVariant = Color(0xFF3A3A3A),
            surfaceTint = Color.Transparent,
        )
    } else {
        copy(
            background = Color(0xFF121212),
            onBackground = Color(0xFFE6E6E6),
            surface = Color(0xFF121212),
            onSurface = Color(0xFFE6E6E6),
            surfaceVariant = Color(0xFF3A3A3A),
            onSurfaceVariant = Color(0xFFC2C2C2),
            surfaceDim = Color(0xFF121212),
            surfaceBright = Color(0xFF3A3A3A),
            surfaceContainerLowest = Color(0xFF0A0A0A),
            surfaceContainerLow = Color(0xFF1C1C1C),
            surfaceContainer = Color(0xFF222222),
            surfaceContainerHigh = Color(0xFF2C2C2C),
            surfaceContainerHighest = Color(0xFF363636),
            outline = Color(0xFF8A8A8A),
            outlineVariant = Color(0xFF3A3A3A),
            surfaceTint = Color.Transparent,
        )
    }
} else {
    copy(
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF1A1A1A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1A1A1A),
        surfaceVariant = Color(0xFFE4E4E4),
        onSurfaceVariant = Color(0xFF474747),
        surfaceDim = Color(0xFFDDDDDD),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6F6F6),
        surfaceContainer = Color(0xFFF1F1F1),
        surfaceContainerHigh = Color(0xFFEBEBEB),
        surfaceContainerHighest = Color(0xFFE5E5E5),
        outline = Color(0xFF787878),
        outlineVariant = Color(0xFFCACACA),
        surfaceTint = Color.Transparent,
    )
}

/**
 * Uses the chosen accent colour RAW (vivid) for the primary roles, like the MaterialFiles fork,
 * instead of the muted tone-40 the Material You generator produces from it. [onPrimary] flips between
 * black and white by the accent's luminance so text stays legible on bright accents (yellow, lime),
 * and [inversePrimary] is set to the same colour so the accent bar is identical in light and dark.
 *
 * [overrideAccentRoles] additionally raw-accents primary/secondary/tertiary's container roles, for
 * [ScopedAccentColor] only: badges, chips and indicators reading those (count badges, the scroll-to-top
 * FAB, the "suggested"/"root"/"installed" pills, the supported-languages loading indicator's track)
 * would otherwise keep the app-wide accent's tone instead of following the icon's colour like
 * everything else in that scope does. Left false for the app-wide theme itself, where the softer
 * Material-generated container tones are still wanted.
 */
private fun ColorScheme.withVividAccent(argb: Int, overrideAccentRoles: Boolean = false): ColorScheme {
    val accent = Color(argb)
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    return copy(
        primary = accent,
        onPrimary = onAccent,
        inversePrimary = accent,
        primaryContainer = if (overrideAccentRoles) accent else primaryContainer,
        onPrimaryContainer = if (overrideAccentRoles) onAccent else onPrimaryContainer,
        secondary = if (overrideAccentRoles) accent else secondary,
        onSecondary = if (overrideAccentRoles) onAccent else onSecondary,
        secondaryContainer = if (overrideAccentRoles) accent else secondaryContainer,
        onSecondaryContainer = if (overrideAccentRoles) onAccent else onSecondaryContainer,
        tertiary = if (overrideAccentRoles) accent else tertiary,
        onTertiary = if (overrideAccentRoles) onAccent else onTertiary,
        tertiaryContainer = if (overrideAccentRoles) accent else tertiaryContainer,
        onTertiaryContainer = if (overrideAccentRoles) onAccent else onTertiaryContainer,
    )
}

/** The two accents a black-and-white icon can wear, picked by which one the theme leaves visible. */
private const val BLACK_ARGB = 0xFF000000.toInt()
private const val WHITE_ARGB = 0xFFFFFFFF.toInt()

/**
 * Overrides just the accent (primary/secondary/tertiary and their on/container roles, and the top bar
 * colour that follows them) within [content], leaving every other role from the enclosing
 * [DroidifyTheme] untouched, for matching a single app's detail page to that app's own icon colour
 * without affecting the rest of the app. A no-op when [accent] is null (the feature is off, or no
 * icon has been read yet).
 *
 * A black-and-white icon ([IconAccent.Monochrome]) is the one case with no answer of its own, and it
 * is settled here because here is where the theme is known. Black and white are each unusable against
 * one of this app's own backgrounds: a black accent vanishes into the AMOLED theme's #000000 surfaces
 * exactly as a white one vanishes into the light theme's #FFFFFF ones. Taking whichever contrasts with
 * the surface actually on screen keeps such an icon's page black-on-white or white-on-black instead of
 * invisible, and is the only place the accent deliberately differs between light and dark.
 */
@Composable
fun ScopedAccentColor(accent: IconAccent?, content: @Composable () -> Unit) {
    val accentColor = when (accent) {
        null -> null
        is IconAccent.Colour -> accent.argb
        IconAccent.Monochrome ->
            if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) BLACK_ARGB else WHITE_ARGB
    }
    if (accentColor == null) {
        content()
        return
    }
    val scopedColorScheme = MaterialTheme.colorScheme.withVividAccent(accentColor, overrideAccentRoles = true)
    // The system-bar chrome is drawn by DroidifyTheme above every screen, outside the scope below, so
    // publish the accent there too or the navigation bar keeps the app-wide colour while this screen's
    // header wears the icon's one. See [LocalScopedAccentBarColor].
    val systemBarAccent = LocalScopedAccentBarColor.current
    val scopedBarColor = scopedColorScheme.primary
    DisposableEffect(systemBarAccent, scopedBarColor) {
        systemBarAccent.value = scopedBarColor
        // Release only what's still ours: navigating between two detail pages keeps both in composition
        // for the transition, and the outgoing one disposes after the incoming one has already published.
        onDispose { if (systemBarAccent.value == scopedBarColor) systemBarAccent.value = null }
    }
    // ON_PAUSE fires as soon as this destination stops being the current one, well before the exit
    // transition finishes and this composable actually leaves composition (what onDispose above waits
    // for): without this, the navigation bar stayed on the icon accent for the whole transition after
    // pressing back, even though the previous screen's own header had already reverted. ON_RESUME puts
    // it back for the other reason ON_PAUSE fires: the app itself backgrounding while still on this
    // screen, not a real navigation away.
    //
    // That second case also covers a brief system overlay on top of this screen (the share sheet, an
    // app chooser, a permission prompt, …), which pauses this screen without really leaving it. Clearing
    // the accent the instant ON_PAUSE fires flashed the navigation bar back to the app-wide colour for
    // that split second before ON_RESUME put it back, visible precisely because opening one of those
    // takes a moment. Delaying the clear briefly, and cancelling it if ON_RESUME arrives first (the
    // overlay closed, this was never a real navigation away), avoids that flash. A genuine back-navigation
    // still reverts the same way, just a little behind where it used to line up with the exit transition.
    val pauseScope = rememberCoroutineScope()
    val pendingClear = remember { mutableStateOf<Job?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        pendingClear.value = pauseScope.launch {
            delay(300)
            if (systemBarAccent.value == scopedBarColor) systemBarAccent.value = null
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        pendingClear.value?.cancel()
        systemBarAccent.value = scopedBarColor
    }
    CompositionLocalProvider(
        LocalAccentBarColor provides scopedBarColor,
        LocalOnAccentBarColor provides scopedColorScheme.onPrimary,
    ) {
        MaterialTheme(colorScheme = scopedColorScheme, content = content)
    }
}

@Composable
fun DroidifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    dynamicColor: Boolean = false,
    accentColor: Int = DEFAULT_THEME_COLOR,
    edgeToEdge: Boolean = true,
    content:
    @Composable()
    () -> Unit,
) {
    val context = LocalContext.current
    // Detected once: drives TV-only behaviour (visible D-pad focus, overscan) via LocalIsTelevision.
    val isTelevision = remember { context.isTelevision() }
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // The "wallpaper" option derives the accent from the ACTUAL wallpaper colour (read via
    // WallpaperManager), so it's right even on OEM skins like ColorOS where the system dynamic accent
    // doesn't follow the wallpaper. The system dynamic scheme is only a fallback when it can't be read.
    val wallpaperAccent = remember(useDynamic) {
        if (useDynamic) context.wallpaperAccentColor() else null
    }
    val colorScheme = when {
        useDynamic && wallpaperAccent != null ->
            context.toComposeColorScheme(if (darkTheme) darkScheme else lightScheme)
                .withVividAccent(wallpaperAccent)

        useDynamic ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        // The activity theme is recolored from the chosen accent at runtime (see
        // MainComposeActivity.applyAccentColor); read it back for the surface/container roles, but use
        // the accent RAW (vivid) for primary instead of the muted tone-40 Material You derives from it
        // — that washed-out tone is what made the colours look dull. Mirrors the MaterialFiles fork.
        else -> context.toComposeColorScheme(if (darkTheme) darkScheme else lightScheme)
            .withVividAccent(accentColor)
    }.withNeutralSurfaces(darkTheme, amoled)

    // The header and system bars use one fixed accent red in BOTH light and dark mode. Material 3
    // lightens `primary` in dark mode, but `inversePrimary` there is exactly the light-mode primary,
    // so the bar colour stays identical. Title/icons use a colour that contrasts with it.
    val barColor = if (darkTheme) colorScheme.inversePrimary else colorScheme.primary
    val onBarColor = if (barColor.luminance() > 0.5f) Color.Black else Color.White

    // A detail screen matching its app's icon publishes its own accent here (see ScopedAccentColor);
    // the system bars follow it so they stay part of that screen's header rather than keeping the
    // app-wide colour. Falls back to the app-wide accent on every other screen.
    val scopedAccentBarColor = remember { mutableStateOf<Color?>(null) }
    // Taking on a screen's accent (icon finishes loading, or ON_RESUME restores it) applies the moment
    // the header itself does, so it snaps instantly to stay in sync with it. Falling back to the
    // app-wide colour instead happens on ON_PAUSE (see ScopedAccentColor), earlier than that screen's
    // own header and content actually finish sliding away, so a hard cut there read as switching back
    // too soon; only that direction eases into the change, to hide that gap.
    val scopedTarget = scopedAccentBarColor.value
    val systemBarColor by animateColorAsState(
        targetValue = scopedTarget ?: barColor,
        animationSpec = if (scopedTarget == null) tween(durationMillis = 300) else snap(),
    )

    // System-bar icons must stay legible. The status bar always sits behind the accent-coloured top
    // bar, so its icons contrast with the accent. The navigation bar is an opaque accent overlay when
    // edge-to-edge is off (so its icons contrast with the accent), but transparent over the app
    // background when on (so they must contrast with that background instead).
    val view = LocalView.current
    if (!view.isInEditMode) {
        val accentIsDark = systemBarColor.luminance() <= 0.5f
        SideEffect {
            // Find the Activity safely (some OEM contexts are wrappers); never crash on a bad cast.
            val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
                ?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !accentIsDark
                controller.isAppearanceLightNavigationBars =
                    if (edgeToEdge) !darkTheme else !accentIsDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        // Opacity of the status-bar scrim, driven by the current screen's scroll (see
        // LocalStatusBarScrimAlpha). Held here so the scrim itself can live above every screen.
        val statusBarScrimAlpha = remember { mutableFloatStateOf(0f) }
        val baseDensity = LocalDensity.current
        // TV only: a uniform shrink of every dp/sp in the app (see TV_UI_SCALE), not just this or that
        // screen's own TV-specific sizes. No-op on touch (baseDensity itself, untouched).
        val scaledDensity = if (isTelevision) {
            Density(
                density = baseDensity.density * TV_UI_SCALE,
                fontScale = baseDensity.fontScale * TV_UI_SCALE,
            )
        } else {
            baseDensity
        }
        CompositionLocalProvider(
            LocalAccentBarColor provides barColor,
            LocalOnAccentBarColor provides onBarColor,
            LocalEdgeToEdge provides edgeToEdge,
            LocalStatusBarScrimAlpha provides statusBarScrimAlpha,
            LocalScopedAccentBarColor provides scopedAccentBarColor,
            LocalIsTelevision provides isTelevision,
            LocalDensity provides scaledDensity,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()
                // When edge-to-edge is OFF, paint an opaque accent bar over the navigation-bar area so
                // it looks like a solid coloured bar matching the top bar. When ON, leave it out so the
                // app shows through the transparent navigation bar (immersive, content behind it) — this
                // only actually works, though, when every screen's own content genuinely reaches that
                // area instead of stopping short of it: see
                // [com.looker.droidify.compose.components.forFloatingBackground]'s doc comment for a real
                // bug that looked exactly like an OEM/vendor navigation-bar limitation (confirmed opaque
                // and theme-independent on a real device) but was actually every screen's own Scaffold
                // painting its default, theme-neutral containerColor under the bar while their real
                // content stayed padded away from it.
                if (!edgeToEdge) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .windowInsetsBottomHeight(WindowInsets.navigationBars)
                            .background(systemBarColor),
                    )
                }
                // Under edge-to-edge, a faint scrim over the status bar keeps it perceptible once a
                // collapsing header has slid away and the content sits behind it. It stays invisible
                // (alpha 0) while the accent header still covers the status bar, and fades in only as
                // the content takes over — so the red header is never tinted. A light scrim in light
                // mode and a light-on-dark one in dark mode keep it integrated with the background.
                if (edgeToEdge) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .graphicsLayer { alpha = statusBarScrimAlpha.floatValue }
                            .background(
                                if (darkTheme) Color.White.copy(alpha = 0.10f)
                                else Color.Black.copy(alpha = 0.14f),
                            ),
                    )
                }
            }
        }
    }
}
