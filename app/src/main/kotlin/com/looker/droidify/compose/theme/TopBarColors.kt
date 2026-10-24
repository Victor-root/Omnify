package com.looker.droidify.compose.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
 * Whether a screen's own content, rather than its header, now fills the status bar: under edge-to-edge
 * a collapsing header slides off the top as the page scrolls (see
 * [com.looker.droidify.compose.components.CollapsibleHeader]). [DroidifyTheme] reads it to keep
 * the status-bar icons legible over that content instead of over the header's accent.
 *
 * Held per owner, like [LocalScopedBarAccents]: two screens are composed together while one slides
 * over the other, and the leaving one releases only after the arriving one has published, so a release
 * clears nothing but what is still its own.
 */
@Stable
class StatusBarContent {
    private var owner: Any? by mutableStateOf(null)
    private var contentShown by mutableStateOf(false)

    /** True while most of the status bar shows a screen's content rather than its header. */
    val showsContent: Boolean get() = owner != null && contentShown

    fun publish(owner: Any, showsContent: Boolean) {
        this.owner = owner
        contentShown = showsContent
    }

    fun release(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            contentShown = false
        }
    }
}

val LocalStatusBarContent = staticCompositionLocalOf { StatusBarContent() }

/**
 * The accents screens have taken over with [ScopedAccentColor], each with how far its screen is shown
 * (0 to 1, following its own enter and exit animation). Only the system-bar chrome needs this:
 * [DroidifyTheme] draws the navigation-bar overlay, and sets both bars' icon appearance, above every
 * screen, so it sits outside the scope [ScopedAccentColor] provides [LocalAccentBarColor] in and cannot
 * read it there. Everything else inside a screen reads [LocalAccentBarColor] as usual.
 *
 * Several at once while one such screen gives way to another: the bar blends them in the order they
 * arrived, so it passes from one to the next exactly as the two screens do.
 */
@Stable
class ScopedBarAccents {
    class Entry(color: Color, val shown: State<Float>) {
        var color by mutableStateOf(color)
    }

    private val entries = mutableStateListOf<Entry>()

    fun add(entry: Entry) {
        entries += entry
    }

    fun remove(entry: Entry) {
        entries -= entry
    }

    /** [base] with every accent laid over it as far as its screen is shown. */
    fun over(base: Color): Color = entries.fold(base) { color, entry -> lerp(color, entry.color, entry.shown.value) }
}

val LocalScopedBarAccents = staticCompositionLocalOf { ScopedBarAccents() }

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
