package com.looker.droidify.compose.tv

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.looker.droidify.BuildConfig
import com.looker.droidify.R
import com.looker.droidify.compose.appDetail.ScreenshotsRow
import com.looker.droidify.compose.components.LoadingSpinner
import com.looker.droidify.compose.components.TvOverscan
import com.looker.droidify.compose.components.premiumCardBorder
import com.looker.droidify.compose.components.tvFocusFill
import com.looker.droidify.compose.components.tvPageScroll
import com.looker.droidify.compose.externalApps.ReadmeWebView
import com.looker.droidify.data.model.FilePath
import com.looker.droidify.utility.diagnostics.DiagnosticLog
import kotlinx.coroutines.delay

/**
 * The two panes of an Android TV app detail page, shared by the catalogue and the external-source
 * screens so both read alike, like the tablet split view: what the app is and what can be done with it
 * on the left ([left], scrolling on its own), what it looks like and says on the right ([right], which
 * does not scroll as a whole: its description scrolls by itself, see [TvDescriptionPane]).
 *
 * [onKeyDown] is told of every key press, so a screen can stop landing focus on its own once the person
 * has taken over. [leftBringIntoViewSpec] only applies to the left pane.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvAppDetailPanes(
    leftScroll: ScrollState,
    leftBringIntoViewSpec: BringIntoViewSpec,
    onKeyDown: () -> Unit,
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) onKeyDown()
                false
            },
    ) {
        // The left pane sits in a card like the phone's hero card, the same plain surface and accent
        // border, so the app and its actions read as one block apart from the description beside it.
        val cardShape = MaterialTheme.shapes.large
        Box(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight()
                .padding(start = TvOverscan + 16.dp, end = 16.dp, top = TvOverscan, bottom = TvOverscan)
                .then(premiumCardBorder(cardShape)),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = cardShape,
            ) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides leftBringIntoViewSpec) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .stayWithinPaneVertically()
                            // overscrollEffect = null: Android 12+'s stretch overscroll crashes RenderThread
                            // when it redraws a hardware-accelerated WebView at a scroll boundary, and the
                            // screens host one (same reason as the phone detail screen).
                            .verticalScroll(leftScroll, overscrollEffect = null)
                            .padding(20.dp),
                        verticalArrangement = spacedBy(24.dp),
                        content = left,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .stayWithinPaneVertically()
                .padding(start = 16.dp, end = TvOverscan + 16.dp, top = TvOverscan, bottom = TvOverscan),
            verticalArrangement = spacedBy(16.dp),
            content = right,
        )
    }
}

/**
 * Keeps up and down within the pane: from its top or bottom edge they used to carry on into the other
 * pane (up from the left pane's top landed on the screenshots), when only right and left are meant to
 * cross between the two.
 */
private fun Modifier.stayWithinPaneVertically(): Modifier = focusProperties {
    onExit = {
        if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) {
            cancelFocusChange()
        }
    }
}.focusGroup()

/**
 * Diagnostics builds only (see [DiagnosticLog]): how long a TV detail [page] takes to open, from its
 * first composition to [ready] (its content is known) and to the first frame drawn after that.
 */
@Composable
internal fun TvDetailOpenTiming(page: String, ready: Boolean) {
    if (!BuildConfig.DIAGNOSTICS) return
    val openedAt = remember { SystemClock.elapsedRealtime() }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        DiagnosticLog.log("TV $page: content composed ${SystemClock.elapsedRealtime() - openedAt} ms after opening")
        withFrameNanos { }
        DiagnosticLog.log("TV $page: content on screen ${SystemClock.elapsedRealtime() - openedAt} ms after opening")
    }
}

/**
 * Android TV: until the person presses a key, keeps the focus on the main action ([primary]), taking it
 * back whenever it is anywhere else ([actionHasFocus] false). Landing once is not enough: the page keeps
 * filling in after it opens (screenshots appear, the install state flips Install to Launch and replaces
 * the button), and any of that can move the focus. Once a key is pressed the focus is the person's.
 * [onLanded] runs after each landing.
 */
@Composable
internal fun TvKeepFocusOnMainAction(
    primary: FocusRequester,
    actionHasFocus: () -> Boolean,
    userInteracted: () -> Boolean,
    onLanded: suspend () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        while (!userInteracted()) {
            if (!actionHasFocus() && runCatching { primary.requestFocus() }.getOrDefault(false)) {
                DiagnosticLog.log("TV app detail: focus put on the main action")
                delay(50)
                if (!userInteracted()) onLanded()
            }
            delay(100)
        }
    }
}

/**
 * An app's description, all of it, filling the room it is given: the page does not scroll for it, the
 * description does, one D-pad focus stop that pages up and down (see [tvPageScroll]) with a faint
 * highlight while it holds focus. Left goes back to [leftTarget] (the page's main action) rather than to
 * whatever sits nearest in the left pane, which on a tall description was the versions list; right is
 * left to the default search; [upTarget] is where "up" goes once at the top. A WebView renders it, so
 * emojis, images, tables and links all show properly.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TvDescriptionPane(
    html: String,
    baseUrl: String,
    javaScriptEnabled: Boolean,
    // The app's own web page, opened if the WebView's renderer process dies mid-render.
    webUrl: String,
    modifier: Modifier = Modifier,
    upTarget: FocusRequester? = null,
    leftTarget: FocusRequester? = null,
) {
    var rendererGone by remember(html) { mutableStateOf(false) }
    if (rendererGone) {
        val uriHandler = LocalUriHandler.current
        Column(
            modifier = modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.readme_render_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { runCatching { uriHandler.openUri(webUrl) } }) {
                Text(stringResource(R.string.source_code))
            }
        }
        return
    }

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var heightPx by remember(html) { mutableStateOf(0) }
    var viewportPx by remember { mutableStateOf(0) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .onSizeChanged { viewportPx = it.height }
            .tvFocusFill(shape)
            .onPreviewKeyEvent { event ->
                // Falls back to the default search if the main action can't take the focus right now.
                leftTarget != null && event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft &&
                    runCatching { leftTarget.requestFocus() }.getOrDefault(false)
            }
            .tvPageScroll(scrollState, (viewportPx * 0.85f).toInt(), upTarget = upTarget)
            // overscrollEffect = null: see TvAppDetailPanes.
            .verticalScroll(scrollState, overscrollEffect = null),
    ) {
        ReadmeWebView(
            html = html,
            baseUrl = baseUrl,
            javaScriptEnabled = javaScriptEnabled,
            onContentHeight = { heightPx = it },
            scrollState = scrollState,
            // Translucent so the screen's accent wash shows through instead of a hard white block.
            translucentBackground = true,
            // A software layer only holds about one screen of pixels; taller content needs a hardware
            // layer or it renders blank (see ReadmeWebView).
            forceSoftwareLayer = viewportPx <= 0 || heightPx <= viewportPx,
            onRendererGone = { rendererGone = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(if (heightPx > 0) with(density) { heightPx.toDp() } else 600.dp),
        )
        // A big description takes a moment to lay out in the WebView (network images, heavy markdown):
        // the spinner stays up until it reports its first real content height.
        if (heightPx <= 0) {
            LoadingSpinner(modifier = Modifier.size(36.dp))
        }
    }
}

/** What the description area says when there is nothing to show, so an empty page is never mistaken for
 *  one that has not loaded. */
@Composable
internal fun TvDescriptionMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** The screenshots row, as one target [requester] can send the focus to (see [TvDescriptionPane]'s upTarget). */
@Composable
internal fun TvScreenshots(
    screenshots: List<FilePath>,
    requester: FocusRequester,
) {
    Box(modifier = Modifier.fillMaxWidth().focusRequester(requester).focusGroup()) {
        ScreenshotsRow(screenshots)
    }
}
