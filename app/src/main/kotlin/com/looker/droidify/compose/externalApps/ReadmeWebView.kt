package com.looker.droidify.compose.externalApps

import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView

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
    val document = remember(html, colorScheme) {
        wrapReadmeHtml(
            body = html,
            background = colorScheme.surface,
            text = colorScheme.onSurface,
            link = colorScheme.primary,
            codeBackground = colorScheme.surfaceVariant,
            border = colorScheme.outlineVariant,
            muted = colorScheme.onSurfaceVariant,
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
                        runCatching { uriHandler.openUri(url) }
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

private fun wrapReadmeHtml(
    body: String,
    background: Color,
    text: Color,
    link: Color,
    codeBackground: Color,
    border: Color,
    muted: Color,
): String = """
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
      blockquote { margin: 0; padding-left: 12px; border-left: 3px solid ${border.css};
                   color: ${muted.css}; }
      table { border-collapse: collapse; display: block; overflow-x: auto; }
      th, td { border: 1px solid ${border.css}; padding: 6px 10px; }
      hr { border: none; border-top: 1px solid ${border.css}; }
    </style>
    </head>
    <body>$body</body>
    </html>
""".trimIndent()

/** `#RRGGBB` form for use in the WebView's inline CSS. */
private val Color.css: String
    get() = "#%06X".format(0xFFFFFF and toArgb())
