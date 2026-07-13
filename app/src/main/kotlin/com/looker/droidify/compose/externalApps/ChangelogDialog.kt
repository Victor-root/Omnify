package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.looker.droidify.R
import com.looker.droidify.compose.components.BackButton
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.components.tvPageScroll
import com.looker.droidify.compose.theme.AccentBarHeight
import com.looker.droidify.compose.theme.LocalAccentBarColor
import com.looker.droidify.compose.theme.LocalIsTelevision
import com.looker.droidify.compose.theme.accentTopAppBarColors
import kotlinx.coroutines.delay

/**
 * The project's changelog, rendered in its own full-screen in-app page — exactly like the README, so
 * checking what's new in a release doesn't send the user out to the browser. [html] null with
 * [unavailable] false means still loading; null with [unavailable] true means the repo genuinely has no
 * changelog file.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChangelogDialog(
    html: String?,
    unavailable: Boolean,
    baseUrl: String,
    javaScriptEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A Dialog opens its own separate Android Window, which doesn't inherit the Activity's
        // edge-to-edge status/navigation bar styling — left alone, it shows the app theme's default
        // (accent red) instead of the current screen's own accent (e.g. green for a per-app colour),
        // and mismatches the Activity behind it entirely once it's dismissed. Match this dialog's own
        // window to the same accent bar colour the top bar uses.
        val barColor = LocalAccentBarColor.current
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.statusBarColor = barColor.toArgb()
            window.navigationBarColor = barColor.toArgb()
            val lightIcons = barColor.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightIcons
                isAppearanceLightNavigationBars = lightIcons
            }
        }
        val isTelevision = LocalIsTelevision.current
        // Android TV: this dialog opens its own window with nothing else focused. The WebView content is
        // deliberately non-focusable (see ReadmeWebView), so without this the D-pad would have nowhere to
        // land at all once loaded — a remote press then times out input dispatch and kills the app. Lands
        // on the back button while loading, then on the content once it's up so "down"/"up" pages through
        // it (see tvPageScroll). No effect on touch.
        val backFocusRequester = remember { FocusRequester() }
        val contentFocusRequester = remember { FocusRequester() }
        if (isTelevision) {
            LaunchedEffect(html) {
                val primary = if (html != null) contentFocusRequester else backFocusRequester
                repeat(20) {
                    if (runCatching { primary.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(50)
                }
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        colors = accentTopAppBarColors(),
                        expandedHeight = AccentBarHeight,
                        modifier = if (isTelevision) {
                            Modifier.tvDpadDownTo(contentFocusRequester, debugLabel = "changelog-topappbar")
                        } else {
                            Modifier
                        },
                        title = { Text(stringResource(R.string.changelog)) },
                        navigationIcon = {
                            BackButton(
                                onDismiss,
                                modifier = if (isTelevision) {
                                    Modifier.focusRequester(backFocusRequester)
                                } else {
                                    Modifier
                                },
                            )
                        },
                    )
                },
            ) { contentPadding ->
                when {
                    html != null -> {
                        val scrollState = rememberScrollState()
                        val density = LocalDensity.current
                        var heightPx by remember { mutableStateOf(0) }
                        var viewportPx by remember { mutableStateOf(0) }
                        Box(
                            modifier = Modifier
                                .padding(contentPadding)
                                .fillMaxWidth()
                                .onSizeChanged { viewportPx = it.height }
                                .then(
                                    if (isTelevision) {
                                        Modifier
                                            .focusRequester(contentFocusRequester)
                                            .tvPageScroll(
                                                scrollState,
                                                (viewportPx * 0.85f).toInt(),
                                                debugLabel = "changelog-content",
                                            )
                                    } else {
                                        Modifier
                                    },
                                )
                                .verticalScroll(scrollState),
                        ) {
                            ReadmeWebView(
                                html = html,
                                baseUrl = baseUrl,
                                javaScriptEnabled = javaScriptEnabled,
                                onContentHeight = { heightPx = it },
                                scrollState = scrollState,
                                // See ReadmeWebView's forceSoftwareLayer doc comment: its software layer
                                // can only draw roughly one screen's worth of pixels — a changelog with
                                // several releases' worth of notes can easily run taller than that, and
                                // without this the WebView would silently render nothing at all past a
                                // short one.
                                forceSoftwareLayer = viewportPx <= 0 || heightPx <= viewportPx,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(
                                        if (heightPx > 0) {
                                            with(density) { heightPx.toDp() }
                                        } else {
                                            600.dp
                                        },
                                    ),
                            )
                        }
                    }

                    unavailable -> Box(
                        modifier = Modifier
                            .padding(contentPadding)
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.external_no_changelog),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Box(
                        modifier = Modifier.padding(contentPadding).fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }
            }
        }
    }
}
