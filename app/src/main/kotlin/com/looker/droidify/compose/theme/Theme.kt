package com.looker.droidify.compose.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.looker.droidify.datastore.DEFAULT_THEME_COLOR
import com.looker.droidify.datastore.model.BackgroundStyle
import com.looker.droidify.utility.common.IconAccent
import com.looker.droidify.utility.common.device.isTelevision
import com.looker.droidify.utility.common.wallpaperAccentColor
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeFidelity

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
 * Forces neutral surfaces (white in light, dark grey or pure black in dark) and disables the
 * tonal-elevation tint, keeping the accent only on primary/secondary/tertiary roles (buttons, section
 * titles, indicators). Material 3 otherwise derives every surface — and the elevation overlay — from
 * the seed colour, which tinted the whole background with the accent. The greys are VFiles' own, so
 * both apps look alike: white/black background, accent only as an accent.
 *
 * [amoled] turns the background and the surfaces drawn directly on it true black (OLED pixels off),
 * while the containers keep their dark grey to stay distinguishable: unlike VFiles, which also turns
 * the lower ones black, this app draws its search field and some cards on them.
 */
private fun ColorScheme.withNeutralSurfaces(dark: Boolean, amoled: Boolean): ColorScheme = if (dark) {
    copy(
        background = NEUTRAL_DARK_SURFACE,
        onBackground = Color.White,
        surface = NEUTRAL_DARK_SURFACE,
        onSurface = Color.White,
        surfaceVariant = Color(0xFF3C4043),
        onSurfaceVariant = Color(0xFFBDC1C6),
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFFE8EAED),
        inverseOnSurface = Color(0xFF202124),
        outline = Color(0xFF9AA0A6),
        outlineVariant = Color(0xFF3C4043),
        surfaceBright = Color(0xFF3C4043),
        surfaceDim = NEUTRAL_DARK_SURFACE,
        surfaceContainerLowest = Color(0xFF0E0E0F),
        surfaceContainerLow = Color(0xFF1B1B1C),
        surfaceContainer = Color(0xFF202124),
        surfaceContainerHigh = Color(0xFF2A2B2D),
        surfaceContainerHighest = Color(0xFF35363A),
    ).let { if (amoled) it.toBlack() else it }
} else {
    copy(
        background = Color.White,
        onBackground = Color.Black,
        surface = Color.White,
        onSurface = Color.Black,
        surfaceVariant = Color(0xFFE8EAED),
        onSurfaceVariant = Color(0xFF5F6368),
        surfaceTint = Color.Transparent,
        inverseSurface = Color(0xFF303134),
        inverseOnSurface = Color(0xFFF1F3F4),
        outline = Color(0xFF80868B),
        outlineVariant = Color(0xFFDADCE0),
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFDADCE0),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainer = Color(0xFFF1F3F4),
        surfaceContainerHigh = Color(0xFFECEDEF),
        surfaceContainerHighest = Color(0xFFE8EAED),
    )
}

private val NEUTRAL_DARK_SURFACE = Color(0xFF121212)

private fun ColorScheme.toBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
)

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
    val onAccent = accent.contentColor()
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

/**
 * The accent roles written or drawn on the page itself (primary, secondary, tertiary), each made readable
 * on its [background] ([readableOn]): a navy accent on a dark page, or a yellow one on a white page, was
 * barely visible there. The accent as chosen, or as taken from an icon, stays exact where it fills
 * something: the containers, and the top bar.
 */
private fun ColorScheme.withReadableAccent(): ColorScheme {
    fun readable(role: Color, onRole: Color): Pair<Color, Color> {
        val moved = role.readableOn(background)
        return if (moved == role) role to onRole else moved to moved.contentColor()
    }
    val (primary, onPrimary) = readable(primary, onPrimary)
    val (secondary, onSecondary) = readable(secondary, onSecondary)
    val (tertiary, onTertiary) = readable(tertiary, onTertiary)
    return copy(
        primary = primary,
        onPrimary = onPrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        tertiary = tertiary,
        onTertiary = onTertiary,
    )
}

/**
 * The scheme derived from a chosen accent, the way VFiles derives it: Material's fidelity scheme, which
 * keeps the containers close to the accent itself. Computed here rather than read back from the
 * activity's theme, so it is the same on every Android version: the theme is only recoloured from
 * Android 12 on, and with Material's content scheme, whose tertiary roles differ.
 */
private fun accentColorScheme(argb: Int, dark: Boolean): ColorScheme {
    val scheme = SchemeFidelity(Hct.fromInt(argb), dark, 0.0)
    return (if (dark) darkScheme else lightScheme).copy(
        primary = Color(scheme.primary),
        onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        inversePrimary = Color(scheme.inversePrimary),
        secondary = Color(scheme.secondary),
        onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer),
        onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary),
        onTertiary = Color(scheme.onTertiary),
        tertiaryContainer = Color(scheme.tertiaryContainer),
        onTertiaryContainer = Color(scheme.onTertiaryContainer),
        error = Color(scheme.error),
        onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer),
        onErrorContainer = Color(scheme.onErrorContainer),
        scrim = Color(scheme.scrim),
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
    val barColor = accentColor?.let(::Color)
    val baseColorScheme = MaterialTheme.colorScheme
    val scopedColorScheme = remember(baseColorScheme, accentColor) {
        accentColor?.let { baseColorScheme.withVividAccent(it, overrideAccentRoles = true).withReadableAccent() }
    }
    PublishAccentToSystemBar(barColor)
    // The same calls with or without an accent: returning [content] bare when there was none rebuilt the
    // whole page under it the moment its icon's accent was read, after the page had already opened.
    CompositionLocalProvider(
        LocalAccentBarColor provides (barColor ?: LocalAccentBarColor.current),
        LocalOnAccentBarColor provides (barColor?.contentColor() ?: LocalOnAccentBarColor.current),
    ) {
        MaterialTheme(colorScheme = scopedColorScheme ?: baseColorScheme, content = content)
    }
}

/** How long a screen takes to show or leave (see the NavHost in MainComposeActivity). */
const val SCREEN_TRANSITION_MS = 700

/** The enter and exit animation of the screen composed under it (see [AccentScreen]). */
private val LocalScreenTransition = staticCompositionLocalOf<Transition<EnterExitState>?> { null }

/** Composes the destination [content] of a screen that uses [ScopedAccentColor], so that the system bars
 *  take its accent in step with this destination's own enter and exit animation. */
@Composable
fun AnimatedContentScope.AccentScreen(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalScreenTransition provides transition, content = content)
}

/**
 * Gives the system bars [scopedBarColor] as far as the screen calling it is shown: its share follows
 * the screen's own enter and exit animation, so the navigation bar changes with the header above it,
 * leaving or coming back, rather than at a moment of its own (fully, outside an [AccentScreen]).
 * Pausing the app (a dialog over it, the
 * share sheet, going to the background) leaves the screen shown, so the accent stays.
 */
@Composable
private fun PublishAccentToSystemBar(scopedBarColor: Color?) {
    // Followed from the screen's first frame, accent or not: the icon's accent is usually read while the
    // screen is still appearing, and an animation started only then ran late behind the header.
    val shown = LocalScreenTransition.current?.animateFloat(
        transitionSpec = { tween(SCREEN_TRANSITION_MS) },
        label = "scopedBarAccent",
    ) { if (it == EnterExitState.Visible) 1f else 0f } ?: remember { mutableFloatStateOf(1f) }
    if (scopedBarColor == null) return
    val entry = remember { ScopedBarAccents.Entry(scopedBarColor, shown) }
    SideEffect { entry.color = scopedBarColor }
    val accents = LocalScopedBarAccents.current
    DisposableEffect(accents) {
        accents.add(entry)
        onDispose { accents.remove(entry) }
    }
}

@Composable
fun DroidifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    dynamicColor: Boolean = false,
    accentColor: Int = DEFAULT_THEME_COLOR,
    edgeToEdge: Boolean = true,
    backgroundStyle: BackgroundStyle = BackgroundStyle.HALO,
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
    // Kept across recompositions: ColorScheme compares by identity, so a fresh one on every pass (this
    // recomposes while a header collapses or the system-bar colour animates) recomposed the whole app.
    // Every theme setting change recreates the activity, so these keys cover everything read here.
    val palette = remember(context, darkTheme, amoled, useDynamic, wallpaperAccent, accentColor) {
        when {
            useDynamic && wallpaperAccent != null ->
                context.toComposeColorScheme(if (darkTheme) darkScheme else lightScheme)
                    .withVividAccent(wallpaperAccent)

            useDynamic ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            // The roles are derived from the chosen accent (see accentColorScheme), but the accent itself is
            // used RAW (vivid) for primary instead of the muted tone-40 Material derives from it; that
            // washed-out tone is what made the colours look dull. Mirrors VFiles.
            else -> accentColorScheme(accentColor, darkTheme).withVividAccent(accentColor)
        }.withNeutralSurfaces(darkTheme, amoled)
    }

    // The header and system bars use one fixed accent red in BOTH light and dark mode. Material 3
    // lightens `primary` in dark mode, but `inversePrimary` there is exactly the light-mode primary,
    // so the bar colour stays identical. Title/icons use a colour that contrasts with it.
    val barColor = if (darkTheme) palette.inversePrimary else palette.primary
    val onBarColor = barColor.contentColor()
    // The bar above keeps the accent exact; what the page writes or draws with it is made readable.
    val colorScheme = remember(palette) { palette.withReadableAccent() }

    // A detail screen matching its app's icon publishes its own accent here (see ScopedAccentColor);
    // the system bars follow it so they stay part of that screen's header rather than keeping the
    // app-wide colour. Falls back to the app-wide accent on every other screen.
    val scopedBarAccents = remember { ScopedBarAccents() }
    val currentBarColor by rememberUpdatedState(barColor)
    val systemBarColor = remember { derivedStateOf { scopedBarAccents.over(currentBarColor) } }

    // A screen whose header has collapsed off the top says so here (see StatusBarContent).
    val statusBarContent = remember { StatusBarContent() }

    SystemBarAppearance(systemBarColor, statusBarContent, darkTheme, edgeToEdge)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        val baseDensity = LocalDensity.current
        // TV only: a uniform shrink of every dp/sp in the app (see TV_UI_SCALE), not just this or that
        // screen's own TV-specific sizes. No-op on touch (baseDensity itself, untouched). An sp is a dp
        // times the font scale, so the density alone shrinks text by as much as the rest: shrinking the
        // font scale too drew text at 72 %, too few pixels to stay sharp once a TV upscales its 1080p
        // interface to 4K.
        val scaledDensity = if (isTelevision) {
            Density(
                density = baseDensity.density * TV_UI_SCALE,
                fontScale = baseDensity.fontScale,
            )
        } else {
            baseDensity
        }
        CompositionLocalProvider(
            LocalAccentBarColor provides barColor,
            LocalOnAccentBarColor provides onBarColor,
            LocalEdgeToEdge provides edgeToEdge,
            LocalBackgroundStyle provides backgroundStyle,
            LocalScopedBarAccents provides scopedBarAccents,
            LocalStatusBarContent provides statusBarContent,
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
                            .drawBehind { drawRect(systemBarColor.value) },
                    )
                }
            }
        }
    }
}

/**
 * System-bar icons must stay legible. The status bar sits behind the accent-coloured top bar, so its
 * icons contrast with the accent, except once a collapsing header has slid away and the screen's
 * own content shows there instead, when they contrast with that background. The navigation bar is
 * an opaque accent overlay when edge-to-edge is off (so its icons contrast with the accent), but
 * transparent over the app background when on (so they must contrast with that background instead).
 *
 * Its own scope, so the animated bar colour and the scrolling header only recompose this.
 */
@Composable
private fun SystemBarAppearance(
    systemBarColor: State<Color>,
    statusBarContent: StatusBarContent,
    darkTheme: Boolean,
    edgeToEdge: Boolean,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val accentIsDark = systemBarColor.value.luminance() <= 0.5f
        val contentUnderStatusBar = statusBarContent.showsContent
        SideEffect {
            // Find the Activity safely (some OEM contexts are wrappers); never crash on a bad cast.
            val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
                ?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars =
                    if (contentUnderStatusBar) !darkTheme else !accentIsDark
                controller.isAppearanceLightNavigationBars =
                    if (edgeToEdge) !darkTheme else !accentIsDark
            }
        }
    }
}
