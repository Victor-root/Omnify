package com.looker.droidify.compose.externalApps

import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import com.looker.droidify.compose.theme.LocalIsTelevision
import kotlinx.coroutines.launch

/**
 * A WebView sized exactly to its content (see [ReadmeWebView]'s doc): it never needs to scroll
 * itself, so a mouse wheel over it should scroll the surrounding page, not the WebView. Chromium
 * handles that fling internally regardless of touch nested-scroll settings, so on a device with a
 * mouse it grabbed the wheel and scrolled its own (non-existent) overflow, visually detaching from
 * the rest of the page. Intercepting the raw scroll axis and forwarding it to [onWheelScroll]
 * keeps the whole screen scrolling as one, matching touch behaviour.
 */
private class NonScrollingWebView(context: Context) : WebView(context) {
    var onWheelScroll: ((Float) -> Unit)? = null

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vscroll != 0f) {
                onWheelScroll?.invoke(-vscroll * WheelScrollFactorPx)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}

/** Roughly one text line per wheel notch — mirrors the platform's own View.scrollBy sizing. */
private const val WheelScrollFactorPx = 60f


/**
 * Renders a project README (GitHub-rendered HTML) in a WebView, so it looks like it does on the web:
 * images sized responsively (CSS `max-width`), badges, code blocks and tables handled by the engine
 * rather than approximated, and (when [javaScriptEnabled]) any script a README embeds — e.g. a live
 * star-history chart or dynamic badge — actually runs. Themed to the app colours; links open in the
 * external browser.
 *
 * File/content access stay disabled regardless of [javaScriptEnabled]: a README is untrusted
 * third-party content, and there's no legitimate reason for it to reach local files or content URIs.
 * Repo-relative image paths resolve against [baseUrl].
 *
 * The WebView does NOT scroll itself: it reports its full content height via [onContentHeight] so the
 * caller can size it exactly and let the whole screen scroll as one (only the top bar stays fixed).
 */
@Composable
fun ReadmeWebView(
    html: String,
    baseUrl: String,
    javaScriptEnabled: Boolean,
    onContentHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    // True (the default, and always true before the content height is first known) forces a software
    // draw layer — see the comment further down for why. A software layer's backing bitmap is capped to
    // roughly one screen's worth of pixels (confirmed via logcat: "not displayed because it is too large
    // to fit into a software layer" — an unrecoverable, silent failure with no visible content at all,
    // not a partial render), so a caller whose README is taller than that must pass false here once it
    // knows, or the whole WebView renders nothing.
    forceSoftwareLayer: Boolean = true,
    // Called if the WebView's renderer process dies (system-triggered, typically under memory pressure —
    // see onRenderProcessGone below) so the caller can show a fallback instead of a permanently blank gap.
    // Never called under normal operation.
    onRendererGone: () -> Unit = {},
    // True when this WebView sits directly on the app's own background + accent wash (the TV detail/reader
    // screens), so the README page must be fully TRANSPARENT and let the wash behind it show through — a
    // flat opaque colour instead reads as a hard rectangular patch that doesn't line up with the aurora
    // glow around it. Both the WebView view background and the CSS body background go fully transparent;
    // only code blocks/tables/quotes keep their own solid surface fill for legibility. False (the raised
    // surface colour, opaque) by default for ChangelogDialog, which sits on its own opaque dialog surface.
    translucentBackground: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    // The README page's own background: fully transparent when it sits on the app (so the accent wash
    // shows straight through), otherwise the opaque raised surface colour.
    val pageBackground = if (translucentBackground) Color.Transparent else colorScheme.surface
    val uriHandler = LocalUriHandler.current
    val isTelevision = LocalIsTelevision.current
    val coroutineScope = rememberCoroutineScope()
    // Captured under a distinct name: this Composable parameter shares its name with WebSettings' own
    // `javaScriptEnabled` property, and a bare reference inside `settings.apply { }` below binds to
    // THIS outer val, not the receiver's settable property — assigning to it would be a compile error
    // ("val cannot be reassigned"), so the receiver-side assignment needs `this.javaScriptEnabled =`
    // explicitly, and reading the parameter's value here under its own name keeps both sides unambiguous.
    val allowJavaScript = javaScriptEnabled
    val document = remember(html, colorScheme, pageBackground) {
        wrapReadmeHtml(
            body = html,
            // The app background (not the raised surface) when translucentBackground is set, so the page
            // reads as part of the screen rather than a card floating on it. Code blocks/tables/quotes
            // below keep their own solid surface colour for contrast; only the page itself blends in.
            background = pageBackground,
            text = colorScheme.onSurface,
            link = colorScheme.primary,
            codeBackground = colorScheme.surfaceVariant,
            border = colorScheme.outlineVariant,
            muted = colorScheme.onSurfaceVariant,
            isDark = colorScheme.surface.luminance() < 0.5f,
        )
    }
    AndroidView(
        modifier = modifier,
        // The WebView sits inside a plain FrameLayout rather than being the AndroidView root itself:
        // defense-in-depth against the Android 12+ libhwui crash this screen used to hit (RenderThread
        // SIGSEGV in GLFunctorDrawable::onDraw when a stretch/saveLayer path redraws the WebView's GL
        // functor with degenerate clip bounds). The primary fix is disabling the stretch overscroll on
        // the scroll containers hosting this composable (overscrollEffect = null at every call site),
        // but the interposed FrameLayout also changes the clip geometry so the fatal unclipped-saveLayer
        // branch isn't taken even if some other effect ever promotes this subtree to a layer — the exact
        // mitigation react-native-webview shipped at scale for the same crash (their PR #2874).
        factory = { context ->
            val web = NonScrollingWebView(context).apply {
                settings.apply {
                    // Explicit `this.`: a bare `javaScriptEnabled` here still binds to the outer
                    // Composable parameter of the same name (a val, hence "val cannot be reassigned")
                    // rather than this WebSettings receiver's own settable property.
                    this.javaScriptEnabled = allowJavaScript
                    allowFileAccess = false
                    allowContentAccess = false
                }
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // Software layer, not hardware, while forceSoftwareLayer holds — see its own doc comment
                // for why this can't just always be on for every caller (a size cap far too small for
                // anything but a short README). The hardware path's real risk (RenderThread crashing in
                // GLFunctorDrawable::onDraw when the WebView's renderer process has died — confirmed via a
                // real Android TV tombstone) is handled below via onRenderProcessGone instead of avoided
                // by force here, since disabling hardware rendering outright would silently blank every
                // README taller than one screen, which is most of them.
                setLayerType(
                    if (forceSoftwareLayer) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE,
                    null,
                )
                // The parent scroll owns scrolling; the WebView is sized to its content.
                isVerticalScrollBarEnabled = false
                isNestedScrollingEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                onWheelScroll = { delta ->
                    scrollState?.let { state ->
                        coroutineScope.launch { state.scrollBy(delta) }
                    }
                }
                // On Android TV, keep the D-pad out of the README: a focusable WebView would step
                // through every link and image inside it. Non-focusable, the remote skips it and the
                // surrounding page-scroll (see tvPageScroll) just scrolls the README into view to read.
                if (isTelevision) {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
                fun reportHeight() {
                    // The last two of these run up to 1.5s later (postDelayed), after the screen may
                    // already have moved on (navigated away, dialog dismissed) and this view detached —
                    // touching a torn-down WebView's content/resources then is a real native-crash
                    // vector, not just a wasted call.
                    if (!isAttachedToWindow) return
                    val pixels = (contentHeight * resources.displayMetrics.density).toInt()
                    if (pixels > 0) onContentHeight(pixels)
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // Report once the text is laid out, then a few more times as images load and
                        // grow the document (the height can only be read after a layout pass).
                        reportHeight()
                        view.postDelayed({ reportHeight() }, 250)
                        view.postDelayed({ reportHeight() }, 750)
                        view.postDelayed({ reportHeight() }, 1500)
                    }

                    // The renderer process died — usually the system reclaiming memory under pressure,
                    // confirmed as the real-world trigger for a native SIGSEGV crash on a real Android TV
                    // device (a real tombstone: RenderThread null-derefing in GLFunctorDrawable::onDraw,
                    // Skia's hook into a WebView's hardware-accelerated draw). Its native compositor state
                    // — including whatever hardware-layer draw hook HWUI still holds for this view — dies
                    // with it, so any further draw of this WebView crashes the same way. Detaching and
                    // destroying it here, the moment Android tells us it's gone, stops that before it can
                    // happen again; returning true tells the system this is handled (no default recovery
                    // UI). onRendererGone lets the caller show a fallback instead of a permanent blank gap.
                    // API 26+ only (minSdk 23) — harmless no-op override on older platforms, which never
                    // call it themselves.
                    @RequiresApi(Build.VERSION_CODES.O)
                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                        onRendererGone()
                        return true
                    }

                    // Open tapped links in the browser instead of navigating inside the README.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = openExternally(request.url.toString())

                    // API < 24 calls this String overload instead of the request one above.
                    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
                    @Deprecated("Deprecated in Java", ReplaceWith("shouldOverrideUrlLoading(view, request)"))
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        openExternally(url)

                    private fun openExternally(url: String): Boolean {
                        runCatching { uriHandler.openUri(humanizeGitHubMarkdownUrl(url)) }
                        return true
                    }
                }
            }
            FrameLayout(context).apply {
                addView(
                    web,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { frame ->
            // The child can be gone: onRenderProcessGone below detaches and destroys it when its
            // renderer process dies, and this update block can still run once more before the caller's
            // onRendererGone state swap removes this composable from composition entirely.
            val web = frame.getChildAt(0) as? WebView ?: return@AndroidView
            // Keep the live setting in sync even if the user flips it in Settings and returns to an
            // already-composed screen (factory only runs once, at first creation).
            web.settings.javaScriptEnabled = allowJavaScript
            // Re-applied on every recomposition where it changed (factory only sets the *initial* value,
            // taken when the content height wasn't known yet — see forceSoftwareLayer's doc comment).
            // setLayerType with the same type it's already on is a cheap no-op.
            val wantedLayerType = if (forceSoftwareLayer) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE
            if (web.layerType != wantedLayerType) {
                web.setLayerType(wantedLayerType, null)
            }
            // Reload only when the rendered document actually changes (not on every recomposition).
            if (web.tag != document) {
                web.tag = document
                web.loadDataWithBaseURL(baseUrl, document, "text/html", "UTF-8", null)
            }
        },
        // Detach from the view hierarchy before destroying, not just destroy() alone: a WebView still
        // attached when destroy() runs can have RenderThread try to draw its (now torn-down) native
        // hardware layer on the very next frame, a null-deref SIGSEGV crash confirmed on a real Android TV
        // device navigating away from this screen. stopLoading() first: an in-flight page load can also
        // touch native state mid-teardown otherwise. (Null child: already torn down by
        // onRenderProcessGone — nothing left to release.)
        onRelease = { frame ->
            (frame.getChildAt(0) as? WebView)?.let { web ->
                web.stopLoading()
                frame.removeView(web)
                web.destroy()
            }
        },
    )
}

/**
 * A README often links to a sibling Markdown file as a repo-relative path; the WebView resolves it
 * against the raw-content [baseUrl], so tapping it would open unstyled raw text in the browser.
 * Rewrite such raw `.md`/`.markdown` links to their human github.com "blob" page so they open
 * rendered. Anything that isn't a raw Markdown link is returned unchanged.
 */
private fun humanizeGitHubMarkdownUrl(url: String): String {
    val rawPrefix = "https://raw.githubusercontent.com/"
    if (!url.startsWith(rawPrefix)) return url
    val path = url.substringBefore('?').substringBefore('#')
    if (!path.endsWith(".md", ignoreCase = true) &&
        !path.endsWith(".markdown", ignoreCase = true)
    ) {
        return url
    }
    // raw.githubusercontent.com/{owner}/{repo}/{ref}/{file…} -> github.com/{owner}/{repo}/blob/{ref}/{file…}
    val parts = path.removePrefix(rawPrefix).split("/", limit = 4)
    if (parts.size < 4) return url
    val (owner, repo, ref, filePath) = parts
    return "https://github.com/$owner/$repo/blob/$ref/$filePath"
}

private fun wrapReadmeHtml(
    body: String,
    background: Color,
    text: Color,
    link: Color,
    codeBackground: Color,
    border: Color,
    muted: Color,
    isDark: Boolean,
): String {
    // GitHub alert (admonition) accent colours, matching github.com light/dark so the coloured left
    // bar and title read the same as on the web.
    val note = if (isDark) "#4493f8" else "#0969da"
    val tip = if (isDark) "#3fb950" else "#1a7f37"
    val important = if (isDark) "#ab7df8" else "#8250df"
    val warning = if (isDark) "#d29922" else "#9a6700"
    val caution = if (isDark) "#f85149" else "#cf222e"
    return """
    <!DOCTYPE html>
    <html>
    <head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      body {
        margin: 0; padding: 16px;
        background: ${background.cssRgba}; color: ${text.css};
        font-family: sans-serif; font-size: 15px; line-height: 1.6;
        overflow-wrap: break-word; word-wrap: break-word;
      }
      /* A heading/paragraph's own top margin doesn't collapse into body's padding (padding is
         non-zero), so the first element's default browser margin stacked ON TOP of our 16px
         padding — a heading-first README (very common: a big centred logo/title) showed a much
         bigger gap above it than any other README with a plain paragraph first. Zeroing just the
         very first child's top margin removes that double gap without touching normal spacing
         between elements further down. */
      body > *:first-child { margin-top: 0; }
      img { max-width: 100%; }
      /* An <img width="…" height="…"> whose width gets scaled down by max-width above keeps its
         literal height attribute unless height is also freed to auto — without this, a screenshot
         wider than the WebView renders squashed (width shrinks, height doesn't). Scoped to images
         that declare a width attribute so small badges/icons sized only via height="…" (no width)
         are untouched and keep the author's exact sizing. */
      img[width] { height: auto; }
      /* Only images with NO declared width get the height cap — combining max-height with the
         height:auto rule above (needed for sized screenshots) hit an aspect-ratio bug on some
         WebView builds: max-width shrank the width but max-height didn't shrink it further to
         match, so the image rendered stretched full-width and squashed short instead of scaled
         down proportionally. Unsized images have no such attribute-derived width to conflict with,
         so the cap alone is safe there — it's only meant to stop a giant unsized logo/banner from
         filling the screen. */
      img:not([width]) { max-height: 280px; }
      a { color: ${link.css}; }
      h1, h2 { border-bottom: 1px solid ${border.css}; padding-bottom: .3em; }
      code { background: ${codeBackground.css}; padding: 2px 5px; border-radius: 4px;
             font-family: monospace; }
      pre { background: ${codeBackground.css}; padding: 12px; border-radius: 8px; overflow-x: auto; }
      pre code { background: transparent; padding: 0; }
      blockquote { margin: 0 0 16px; padding: 0 1em; border-left: .25em solid ${border.css};
                   color: ${muted.css}; }
      table { border-collapse: collapse; display: block; overflow-x: auto; }
      th, td { border: 1px solid ${border.css}; padding: 6px 10px; }
      hr { border: none; border-top: 1px solid ${border.css}; }
      /* GitHub alerts (> [!NOTE] / [!WARNING] …): a coloured left bar and a coloured, icon-led title. */
      .markdown-alert { padding: 8px 16px; margin-bottom: 16px; border-left: .25em solid ${border.css}; }
      .markdown-alert > :first-child { margin-top: 0; }
      .markdown-alert > :last-child { margin-bottom: 0; }
      .markdown-alert .markdown-alert-title { display: flex; align-items: center;
             font-weight: 600; line-height: 1; }
      .markdown-alert .markdown-alert-title svg { fill: currentColor; margin-right: 8px; }
      .markdown-alert-note { border-left-color: $note; }
      .markdown-alert-note .markdown-alert-title { color: $note; }
      .markdown-alert-tip { border-left-color: $tip; }
      .markdown-alert-tip .markdown-alert-title { color: $tip; }
      .markdown-alert-important { border-left-color: $important; }
      .markdown-alert-important .markdown-alert-title { color: $important; }
      .markdown-alert-warning { border-left-color: $warning; }
      .markdown-alert-warning .markdown-alert-title { color: $warning; }
      .markdown-alert-caution { border-left-color: $caution; }
      .markdown-alert-caution .markdown-alert-title { color: $caution; }
    </style>
    </head>
    <body>$body</body>
    </html>
    """.trimIndent()
}

/** `#RRGGBB` form for use in the WebView's inline CSS. Drops alpha — see [cssRgba] for a colour
 *  that needs to stay translucent (e.g. over the aurora wash behind the page). */
private val Color.css: String
    get() = "#%06X".format(0xFFFFFF and toArgb())

/** `rgba(r, g, b, a)` form, alpha included — for a CSS colour that must stay translucent, unlike
 *  [css] (which always renders fully opaque). */
private val Color.cssRgba: String
    get() = "rgba(${(red * 255).toInt()}, ${(green * 255).toInt()}, ${(blue * 255).toInt()}, $alpha)"
