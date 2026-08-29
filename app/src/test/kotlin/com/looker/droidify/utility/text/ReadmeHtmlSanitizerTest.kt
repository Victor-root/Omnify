package com.looker.droidify.utility.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two halves, and the second one matters as much as the first: what a README must never be able to do
 * (the first block), and what a README must still look exactly like afterwards (the second). A
 * sanitizer that quietly drops `<picture>`, an image's width, or a task list's checkboxes has broken
 * the feature it was added to protect.
 */
class ReadmeHtmlSanitizerTest {

    private fun sanitize(html: String) = ReadmeHtmlSanitizer.sanitize(html)

    @Test
    fun `script elements go, with their content`() {
        val output = sanitize("<p>before</p><script>alert(1)</script><p>after</p>")
        assertEquals("<p>before</p><p>after</p>", output)
    }

    @Test
    fun `an unclosed script takes the rest of the document with it`() {
        // A browser would run everything after it as script, so leaving the tail behind would be
        // showing text that was never meant to render.
        assertEquals("<p>before</p>", sanitize("<p>before</p><script>alert(1)"))
    }

    @Test
    fun `a script nested inside inline svg goes too`() {
        val output = sanitize("""<svg viewBox="0 0 1 1"><script>alert(1)</script></svg>""")
        assertEquals("""<svg viewBox="0 0 1 1"></svg>""", output)
    }

    @Test
    fun `a tag broken up to reform after the inner one is removed does not reform`() {
        // The classic bypass against a remove-the-substring sanitizer: dropping the inner <script>
        // would splice "<scr" and "ipt>" back into a working tag. Rebuilding kept tags from their
        // parsed name and attributes is what stops it.
        val output = sanitize("<scr<script>ipt>alert(1)</script>")
        assertFalse("<script" in output.lowercase(), "A script tag reformed: $output")
    }

    @Test
    fun `event handler attributes go, the element stays`() {
        val output = sanitize("""<img src="a.png" onerror="alert(1)" width="220">""")
        assertEquals("""<img src="a.png" width="220">""", output)
    }

    @Test
    fun `javascript links lose their href and keep their text`() {
        val output = sanitize("""<a href="javascript:alert(1)">click</a>""")
        assertEquals("<a>click</a>", output)
    }

    @Test
    fun `a javascript link hidden behind a character reference is still caught`() {
        assertFalse("href" in sanitize("""<a href="java&#115;cript:alert(1)">x</a>"""))
        assertFalse("href" in sanitize("""<a href="&#106;avascript:alert(1)">x</a>"""))
        assertFalse("href" in sanitize("""<a href="&#x6A;avascript:alert(1)">x</a>"""))
    }

    @Test
    fun `a javascript link hidden behind whitespace is still caught`() {
        assertFalse("href" in sanitize("<a href=\"java\tscript:alert(1)\">x</a>"))
        assertFalse("href" in sanitize("""<a href="  javascript:alert(1)">x</a>"""))
    }

    @Test
    fun `a real link survives the same cleaning that unmasks a disguised one`() {
        // Stripping whitespace and control characters before reading the scheme has two jobs, and the
        // tests above only covered one. It unmasks "java\tscript:", and it must also leave an ordinary
        // link alone: markdown tooling wraps long lines, so a leading space or a newline inside an href
        // is ordinary, and reading " https" as the scheme would refuse the project's own links.
        assertTrue("href" in sanitize("""<a href="  https://example.org">x</a>"""))
        assertTrue("href" in sanitize("<a href=\"\nhttps://example.org\">x</a>"))
        assertTrue("href" in sanitize("<a href=\"https://example.org/a\tb\">x</a>"))
        assertTrue(ReadmeHtmlSanitizer.isFollowableUrl(" https://example.org"))
    }

    @Test
    fun `a scheme shouted in capitals is the same scheme`() {
        // HTTPS://EXAMPLE.ORG is a link like any other, and refusing it would quietly drop real ones.
        assertTrue("href" in sanitize("""<a href="HTTPS://example.org">x</a>"""))
        assertTrue(ReadmeHtmlSanitizer.isFollowableUrl("HTTPS://example.org"))
        assertTrue(ReadmeHtmlSanitizer.isFollowableUrl("MailTo:someone@example.org"))
        // And the refusals are not case-sensitive either way round.
        assertFalse(ReadmeHtmlSanitizer.isFollowableUrl("JavaScript:alert(1)"))
    }

    @Test
    fun `a colon belonging to a query or an anchor is not a scheme`() {
        // "?q=a:b" and "#a:b" are relative links with no scheme at all. The existing case carried a
        // slash before the colon, which is a second, independent reason to read it as relative, so it
        // held even with the query and anchor rules removed.
        assertTrue(ReadmeHtmlSanitizer.isFollowableUrl("?q=a:b"))
        assertTrue(ReadmeHtmlSanitizer.isFollowableUrl("#section:one"))
        val html = """<a href="?q=a:b">a</a><a href="#section:one">b</a>"""
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `schemes that reach off the page or into the device are refused`() {
        listOf("intent://x#Intent;end", "file:///data/data/x", "content://x/y", "vbscript:x")
            .forEach { url ->
                assertFalse("href" in sanitize("""<a href="$url">x</a>"""), "Allowed $url")
            }
    }

    @Test
    fun `a form is removed but its fields still show`() {
        val output = sanitize("""<form action="https://evil.example"><input name="token"></form>""")
        assertEquals("""<input name="token">""", output)
    }

    @Test
    fun `base meta and link are removed`() {
        val output = sanitize(
            """<base href="https://evil.example/"><meta http-equiv="refresh" content="0;url=x">""" +
                """<link rel="stylesheet" href="https://evil.example/a.css"><p>text</p>""",
        )
        assertEquals("<p>text</p>", output)
    }

    @Test
    fun `iframes and embedded objects are removed with their content`() {
        val output = sanitize("""<iframe src="https://evil.example">fallback</iframe><p>text</p>""")
        assertEquals("<p>text</p>", output)
    }

    @Test
    fun `comments are removed`() {
        assertEquals("<p>a</p><p>b</p>", sanitize("<p>a</p><!-- <script>alert(1)</script> --><p>b</p>"))
    }

    @Test
    fun `case is no way around any of it`() {
        assertEquals("<p>x</p>", sanitize("<SCRIPT>alert(1)</SCRIPT><p>x</p>"))
        assertFalse("href" in sanitize("""<a href="JaVaScRiPt:alert(1)">x</a>"""))
        assertFalse("onclick" in sanitize("""<div OnClick="alert(1)">x</div>"""))
    }

    @Test
    fun `quoting is no way around it either`() {
        assertFalse("href" in sanitize("<a href='javascript:alert(1)'>x</a>"))
        assertFalse("href" in sanitize("<a href=javascript:alert(1)>x</a>"))
        assertFalse("href" in sanitize("""<a href = "javascript:alert(1)">x</a>"""))
    }

    @Test
    fun `an svg reference to a document is refused`() {
        val output = sanitize("""<svg><use xlink:href="data:text/html;base64,PHN2Zz4=" /></svg>""")
        assertFalse("xlink:href" in output, output)
    }

    @Test
    fun `an unterminated tag stays text and cannot act`() {
        val output = sanitize("""<p>x</p><a href="javascript:alert(1)"""")
        assertFalse("<a href" in output, output)
    }

    @Test
    fun `srcdoc and ping are removed`() {
        assertEquals("<a>x</a>", sanitize("""<a ping="https://evil.example" srcdoc="<b>">x</a>"""))
    }

    // What must survive untouched, because a README's whole point is to look the way it was written.

    @Test
    fun `a dark and light screenshot pair survives`() {
        val html = """<picture><source media="(prefers-color-scheme: dark)" srcset="dark.webp">""" +
            """<img width="220" alt="screenshot" src="light.webp" /></picture>"""
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `centred headers, details blocks and badges survive`() {
        val html = """<div align="center"><h1>Title</h1>""" +
            """<a href="https://example.org/release"><img src="https://img.shields.io/x.svg"></a>""" +
            """</div><details><summary>More</summary><p>body</p></details>"""
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `task list checkboxes survive`() {
        val html = """<ul><li><input type="checkbox" disabled checked> done</li></ul>"""
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `inlined data image sources survive`() {
        // ExternalApi.inlineRelativeImages turns a README's relative images into data URIs before
        // this ever runs, so refusing them would blank out every repo-hosted screenshot.
        val html = """<img src="data:image/png;base64,iVBORw0KGgo=">"""
        assertEquals(html, sanitize(html))
        val svg = """<img src="data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=">"""
        assertEquals(svg, sanitize(svg))
    }

    @Test
    fun `a data document that is not an image is refused`() {
        assertFalse("src" in sanitize("""<img src="data:text/html;base64,PHNjcmlwdD4=">"""))
    }

    @Test
    fun `the chat-room links a project README actually uses survive`() {
        // Matching what github.com's own sanitizer keeps: a README that links its Matrix or XMPP room
        // reads the same here as it does on the forge.
        val html = """<a href="xmpp:room@example.org">a</a><a href="matrix:r/room:example.org">b</a>""" +
            """<a href="tel:+33100000000">c</a><a href="mailto:x@example.org">d</a>"""
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `isFollowableUrl agrees with what the document keeps`() {
        // The WebView asks this of every navigation, so the two must not drift apart.
        assertTrue(ReadmeHtmlSanitizer.isFollowableUrl("https://example.org"))
        assertTrue(ReadmeHtmlSanitizer.isFollowableUrl("matrix:r/room:example.org"))
        assertFalse(ReadmeHtmlSanitizer.isFollowableUrl("javascript:alert(1)"))
        assertFalse(ReadmeHtmlSanitizer.isFollowableUrl("intent://x#Intent;end"))
        assertFalse(ReadmeHtmlSanitizer.isFollowableUrl("file:///data/data/x"))
        // Kept as an image inside the document, but never somewhere a tap should navigate to.
        assertFalse(ReadmeHtmlSanitizer.isFollowableUrl("data:image/png;base64,iVBORw0KGgo="))
    }

    @Test
    fun `relative links and anchors survive`() {
        val html = """<a href="docs/install.md">a</a><a href="#usage">b</a><a href="./x?q=1:2">c</a>"""
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `tables code blocks and text are untouched`() {
        val html = "<table><thead><tr><th>a</th></tr></thead><tbody><tr><td>b</td></tr></tbody>" +
            "</table><pre><code>fun main() {}</code></pre><p>5 &lt; 6 &amp; 7 &gt; 6</p>"
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `the app's own alert callouts survive`() {
        val html = """<div class="markdown-alert markdown-alert-note">""" +
            """<p class="markdown-alert-title">Note</p><p>body</p></div>"""
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `an ampersand already written as a reference is not escaped twice`() {
        val html = """<a href="https://example.org/?a=1&amp;b=2">x</a>"""
        assertEquals(html, sanitize(html))
    }

    @Test
    fun `a bare less-than in prose stays text`() {
        // Escaped rather than left bare, which renders as the same character it already did.
        assertEquals("<p>a &lt; b</p>", sanitize("<p>a < b</p>"))
    }

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("no markup at all", sanitize("no markup at all"))
    }

    @Test
    fun `an empty document stays empty`() {
        assertEquals("", sanitize(""))
    }
}
