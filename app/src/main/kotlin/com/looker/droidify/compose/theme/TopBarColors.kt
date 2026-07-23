package com.looker.droidify.compose.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The accent colour used for the top bar and the system bars, and the contrasting colour for their
 * title/icons. Provided by [DroidifyTheme] so it stays identical in light and dark mode (Material 3
 * would otherwise lighten the primary in dark mode and the red would not match).
 */
val LocalAccentBarColor = staticCompositionLocalOf { Color.Red }
val LocalOnAccentBarColor = staticCompositionLocalOf { Color.White }

/**
 * Whether the app is drawing edge-to-edge (the "Edge-to-edge" setting). Screens read this to make
 * their header collapse on scroll while edge-to-edge is on, and stay pinned when it's off. Provided
 * by [DroidifyTheme].
 */
val LocalEdgeToEdge = staticCompositionLocalOf { true }

/**
 * Opacity (0..1) of the status-bar scrim — a faint, well-integrated background that keeps the status
 * bar perceptible once a collapsing header has slid away and the app content shows behind it. The
 * current screen drives it from its scroll state; [DroidifyTheme] draws the scrim and resets it to 0.
 */
val LocalStatusBarScrimAlpha = staticCompositionLocalOf<MutableFloatState> { mutableFloatStateOf(0f) }

/**
 * Top-bar colours that follow the user's chosen accent: the bar — and the status-bar area it draws
 * behind under edge-to-edge — takes the accent colour, with a contrasting title and icons. Every
 * screen's TopAppBar uses this so the header and status bar follow the theme colour.
 */
/**
 * Height of the secondary screens' top bar — Settings, Repos, the detail screens and the "see all"
 * pages, i.e. every back-arrow + title header. A little shorter than Material's 64dp default but tall
 * enough to breathe. The home screen is deliberately not included: it uses its own taller HomeBarHeight
 * so the logo + wordmark stay prominent.
 */
val AccentBarHeight = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun accentTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = LocalAccentBarColor.current,
    scrolledContainerColor = LocalAccentBarColor.current,
    navigationIconContentColor = LocalOnAccentBarColor.current,
    titleContentColor = LocalOnAccentBarColor.current,
    actionIconContentColor = LocalOnAccentBarColor.current,
)

/**
 * Android TV variant of [accentTopAppBarColors]: no solid accent bar (which looked out of place next to
 * the rest of the TV UI), just a transparent header with a plain title, matching the other TV screens
 * while keeping the back arrow and any header actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun tvTopAppBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Transparent,
    scrolledContainerColor = Color.Transparent,
    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
    titleContentColor = MaterialTheme.colorScheme.onSurface,
    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
)
