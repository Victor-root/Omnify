package com.looker.droidify.compose.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.looker.droidify.R
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.tvDpadDownTo
import com.looker.droidify.compose.components.tvPageScroll
import com.looker.droidify.compose.externalApps.ReadmeWebView
import kotlinx.coroutines.delay

/**
 * Android TV full-page reader for an app's long-form HTML (description / README), replacing the phone's
 * modal dialog with a real page in the TV language: the accent wash background, a back affordance + large
 * title, and the [ReadmeWebView] paged by the D-pad. Rendered in place over the detail screen while open;
 * system back closes it. [html] null with [unavailable] false = still loading; null with true = nothing
 * to show ([unavailableMessage]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TvReadmeScreen(
    title: String,
    html: String?,
    unavailable: Boolean,
    unavailableMessage: String,
    baseUrl: String,
    javaScriptEnabled: Boolean,
    // The content's own web page, opened if the WebView's renderer process dies mid-render.
    webUrl: String,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val uriHandler = LocalUriHandler.current
    var rendererGone by remember { mutableStateOf(false) }

    val backFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    // Android TV must always hold focus somewhere. The WebView is non-focusable, so focus lands on the
    // paged content once it's up, otherwise on the back affordance.
    LaunchedEffect(html, rendererGone) {
        val primary = if (html != null && !rendererGone) contentFocus else backFocus
        repeat(20) {
            if (runCatching { primary.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TvAccentBackground()
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvDpadDownTo(contentFocus)
                    .padding(start = TvOverscan + 8.dp, end = TvOverscan, top = TvOverscan, bottom = 8.dp),
            ) {
                TvBackButton(onBackClick = onBack, modifier = Modifier.focusRequester(backFocus))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            when {
                html != null && !rendererGone -> {
                    val scrollState = rememberScrollState()
                    val density = LocalDensity.current
                    var heightPx by remember { mutableStateOf(0) }
                    var viewportPx by remember { mutableStateOf(0) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = TvOverscan + 8.dp)
                            .onSizeChanged { viewportPx = it.height }
                            .focusRequester(contentFocus)
                            .tvPageScroll(scrollState, (viewportPx * 0.85f).toInt(), debugLabel = "tv-readme")
                            // overscrollEffect = null: this container hosts a hardware-accelerated WebView,
                            // and Android 12+'s stretch overscroll crashes RenderThread when it redraws the
                            // WebView at the scroll boundary (same as every other ReadmeWebView host).
                            .verticalScroll(scrollState, overscrollEffect = null),
                    ) {
                        ReadmeWebView(
                            html = html,
                            baseUrl = baseUrl,
                            javaScriptEnabled = javaScriptEnabled,
                            onContentHeight = { heightPx = it },
                            scrollState = scrollState,
                            // Translucent so the accent wash shows through instead of a hard white block.
                            translucentBackground = true,
                            // Software layer only caps at ~one screen of pixels; taller content needs a
                            // hardware layer or it renders blank (see ReadmeWebView).
                            forceSoftwareLayer = viewportPx <= 0 || heightPx <= viewportPx,
                            onRendererGone = { rendererGone = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (heightPx > 0) with(density) { heightPx.toDp() } else 600.dp),
                        )
                    }
                }

                rendererGone -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.readme_render_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { runCatching { uriHandler.openUri(webUrl) } }) {
                        Text(stringResource(R.string.source_code))
                    }
                }

                unavailable -> Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = unavailableMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}
