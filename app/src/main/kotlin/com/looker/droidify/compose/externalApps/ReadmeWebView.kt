package com.looker.droidify.compose.externalApps

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import com.looker.droidify.compose.theme.LocalIsTelevision

/**
 * Renders a project README (GitHub-rendered HTML) in a WebView, so it looks like it does on the web:
 * images sized responsively (CSS `max-width`), badges, code blocks and tables handled by the engine
 * rather than approximated. Themed to the app colours; links open in the external browser.
 *
 * JavaScript and file/content access are disabled — the content is static markup, so the attack
 * surface stays minimal. Repo-relative image paths resolve against [baseUrl].
 *
 * The WebView does NOT scroll itself: it reports its full content height via [onContentHeight] so the
 * caller can size it exactly and let the whole screen scroll as one (only the top bar stays fixed).
 */
@Composable
fun ReadmeWebView(
    html: String,
    baseUrl: String,
    onContentHeight: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    val isTelevision = LocalIsTelevision.current
    val document = remember(html, colorScheme) {
        wrapReadmeHtml(
            body = html,
            background = colorScheme.surface,
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
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                }
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // The parent scroll owns scrolling; the WebView is sized to its content.
                isVerticalScrollBarEnabled = false
                isNestedScrollingEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                // On Android TV, keep the D-pad out of the README: a focusable WebView would step
                // through every link and image inside it. Non-focusable, the remote skips it and the
                // surrounding page-scroll (see tvPageScroll) just scrolls the README into view to read.
                if (isTelevision) {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
                fun reportHeight() {
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

                    // Open tapped links in the browser instead of navigating inside the README.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = openExternally(request.url.toString())

                    // API < 24 calls this String overload instead of the request one above.
                    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                        openExternally(url)

                    private fun openExternally(url: String): Boolean {
                        runCatching { uriHandler.openUri(humanizeGitHubMarkdownUrl(url)) }
                        return true
                    }
                }
            }
        },
        update = { web ->
            // Reload only when the rendered document actually changes (not on every recomposition).
            if (web.tag != document) {
                web.tag = document
                web.loadDataWithBaseURL(baseUrl, document, "text/html", "UTF-8", null)
            }
        },
        onRelease = { it.destroy() },
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
        background: ${background.css}; color: ${text.css};
        font-family: sans-serif; font-size: 15px; line-height: 1.6;
        overflow-wrap: break-word; word-wrap: break-word;
      }
      img { max-width: 100%; height: auto; }
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

/** `#RRGGBB` form for use in the WebView's inline CSS. */
private val Color.css: String
    get() = "#%06X".format(0xFFFFFF and toArgb())
