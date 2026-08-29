package com.looker.droidify.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.looker.droidify.compose.theme.LocalAccentBarColor

/**
 * Properties for a [androidx.compose.ui.window.Dialog] used as a full-screen page: the whole width, and
 * drawn behind the system bars exactly as [com.looker.droidify.compose.MainComposeActivity] draws every
 * screen.
 *
 * A dialog opens a window of its own, laid out inside the system bars unless it is built otherwise. What
 * shows in those bars then stays the Activity's, darkened by the dim the dialog puts over everything
 * behind it: that is where the changelog's clear accent header above a darker status bar and a darker
 * navigation bar came from. Colouring the dialog's own bars instead only moves the problem, since the
 * system reserves the right to tint what it draws there and from Android 15 ignores those colours
 * outright.
 *
 * `decorFitsSystemWindows = false` is the only way to move that window, and it has to be set here rather
 * than on the window afterwards: Compose reads it while the dialog is being built, to pick the window's
 * theme, to add FLAG_LAYOUT_IN_SCREEN and FLAG_LAYOUT_INSET_DECOR before the decor is generated, and to
 * clear the inset types the window manager keeps a window clear of (which no flag overrides). The status
 * bar then becomes the header's own pixels rather than a copy of its colour, and nothing can drift
 * between the two because there is only one of them.
 *
 * Pair with [DialogSystemBarIcons] and [AccentNavigationBarOverlay].
 */
val FullScreenDialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)

/**
 * Status- and navigation-bar icon colours for a dialog opened with [FullScreenDialogProperties], set on
 * the dialog's own window: it sits above the Activity, so the system takes their appearance from it and
 * not from what [com.looker.droidify.compose.theme.DroidifyTheme] set on the Activity's window.
 *
 * Both bars show the accent here, so both follow it: dark icons on a light accent, light ones otherwise.
 */
@Composable
fun DialogSystemBarIcons() {
    val view = LocalView.current
    val lightAccent = LocalAccentBarColor.current.luminance() > 0.5f
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightAccent
            isAppearanceLightNavigationBars = lightAccent
        }
    }
}

/**
 * The accent bar under the navigation bar, matching the header at the other end of the screen. These
 * pages are a header and one column of content, with nothing of their own worth showing through the
 * bar, so it carries the header's colour whether or not the app is drawing edge to edge.
 *
 * Overlaid on the dialog's content rather than laid out beside it: a [androidx.compose.material3.Scaffold]
 * already keeps its content clear of the navigation bar, and its own container colour is what would
 * otherwise be seen through it.
 */
@Composable
fun AccentNavigationBarOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .background(LocalAccentBarColor.current),
    )
}
