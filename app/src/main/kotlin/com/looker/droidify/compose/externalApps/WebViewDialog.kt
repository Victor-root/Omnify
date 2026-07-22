package com.looker.droidify.compose.externalApps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
 * A full-screen in-app page that renders [html] in a [ReadmeWebView] — the shared scaffolding behind the
 * changelog page and the app-description page, so any long-form HTML (README, changelog, description)
 * reads identically and never sends the user out to a browser. [html] null with [unavailable] false
 * means still loading; null with [unavailable] true means there's genuinely nothing to show (then
 * [unavailableMessage] is displayed). On Android TV the D-pad pages through the content and system back
 * closes the page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebViewDialog(
    title: String,
    html: String?,
    unavailable: Boolean,
    unavailableMessage: String,
    baseUrl: String,
    javaScriptEnabled: Boolean,
    // The content's own web page, opened if the WebView's renderer process dies mid-render (see
    // ReadmeWebView's onRenderProcessGone) so there's still a way to read it.
    webUrl: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A Dialog opens its own separate Android Window, which doesn't inherit the Activity's
        // edge-to-edge status/navigation bar styling — left alone, it shows the app theme's default
        // (accent red) instead of the current screen's own accent, and mismatches the Activity behind it
        // once dismissed. Match this dialog's own window to the same accent bar colour the top bar uses.
        val barColor = LocalAccentBarColor.current
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            applyDialogBarColor(window, barColor.toArgb())
            val lightIcons = barColor.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightIcons
                isAppearanceLightNavigationBars = lightIcons
            }
        }
        val isTelevision = LocalIsTelevision.current
        // Android TV: this dialog opens its own window with nothing else focused. The WebView content is
        // deliberately non-focusable (see ReadmeWebView), so without this the D-pad would have nowhere to
        // land — a remote press then times out input dispatch and kills the app. Lands on the back button
        // while loading, then on the content once it's up so "down"/"up" pages through it. No-op on touch.
        val backFocusRequester = remember { FocusRequester() }
        val contentFocusRequester = remember { FocusRequester() }
        val uriHandler = LocalUriHandler.current
        var rendererGone by remember { mutableStateOf(false) }
        if (isTelevision) {
            LaunchedEffect(html) {
                val primary = if (html != null) contentFocusRequester else backFocusRequester
                repeat(20) {
                    if (runCatching { primary.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(50)
                }
            }
        }
        // On Android TV a full-screen sheet is too much for a description — centre it as a card filling
        // three-quarters of the screen, with the dimmed activity showing through around it. On touch it
        // stays a full-screen page (unchanged). The Dialog window itself fills the screen either way; the
        // Box centres the card within it.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = if (isTelevision) {
                    Modifier.fillMaxWidth(0.75f).fillMaxHeight(0.8f)
                } else {
                    Modifier.fillMaxSize()
                },
                shape = if (isTelevision) RoundedCornerShape(24.dp) else RectangleShape,
            ) {
                Scaffold(
                topBar = {
                    TopAppBar(
                        colors = accentTopAppBarColors(),
                        expandedHeight = AccentBarHeight,
                        modifier = if (isTelevision) {
                            Modifier.tvDpadDownTo(contentFocusRequester, debugLabel = "webview-dialog-topappbar")
                        } else {
                            Modifier
                        },
                        title = { Text(title) },
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
                    html != null && !rendererGone -> {
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
                                                debugLabel = "webview-dialog-content",
                                            )
                                    } else {
                                        Modifier
                                    },
                                )
                                // overscrollEffect = null: this container hosts a hardware-accelerated
                                // WebView, and Android 12+'s stretch overscroll deterministically crashes
                                // RenderThread when it redraws that WebView at the scroll boundary — see
                                // the same parameter in ExternalAppDetailScreen for the full story.
                                .verticalScroll(scrollState, overscrollEffect = null),
                        ) {
                            ReadmeWebView(
                                html = html,
                                baseUrl = baseUrl,
                                javaScriptEnabled = javaScriptEnabled,
                                onContentHeight = { heightPx = it },
                                scrollState = scrollState,
                                // See ReadmeWebView's forceSoftwareLayer doc comment: its software layer
                                // draws only ~one screen of pixels, so anything taller must switch to a
                                // hardware layer or render fully blank.
                                forceSoftwareLayer = viewportPx <= 0 || heightPx <= viewportPx,
                                onRendererGone = { rendererGone = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(
                                        if (heightPx > 0) with(density) { heightPx.toDp() } else 600.dp,
                                    ),
                            )
                        }
                    }

                    rendererGone -> Column(
                        modifier = Modifier
                            .padding(contentPadding)
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.readme_render_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { uriHandler.openUri(webUrl) }) {
                            Text(stringResource(R.string.source_code))
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
                            text = unavailableMessage,
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
}

/**
 * Sets this dialog window's status/navigation bar background to [argb]. No modern replacement covers
 * this: WindowCompat/WindowInsetsControllerCompat only manage bar icon appearance and visibility, never
 * an explicit background colour, so matching the dialog's own separate window to the current screen's
 * accent still has to go through these deprecated [android.view.Window] properties directly.
 */
@Suppress("DEPRECATION")
private fun applyDialogBarColor(window: android.view.Window, argb: Int) {
    window.statusBarColor = argb
    window.navigationBarColor = argb
}
