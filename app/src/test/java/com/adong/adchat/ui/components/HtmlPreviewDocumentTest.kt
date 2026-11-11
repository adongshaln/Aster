package com.adong.adchat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlPreviewDocumentTest {

    @Test
    fun `wrap without panel bridge has no bridge markers`() {
        val out = HtmlPreviewDocument.wrap("<p>hi</p>", allowScripts = true)
        assertFalse(out.contains(HtmlPreviewDocument.PANEL_CAPTURE_MARKER))
        assertFalse(out.contains(HtmlPreviewDocument.PANEL_BRIDGE_MARKER))
    }

    @Test
    fun `wrap with panel bridge contains capture and listener scripts`() {
        val out = HtmlPreviewDocument.wrap("<p>hi</p>", allowScripts = true, panelBridge = true)
        assertTrue(out.contains(HtmlPreviewDocument.PANEL_CAPTURE_MARKER))
        assertTrue(out.contains(HtmlPreviewDocument.PANEL_BRIDGE_MARKER))
        assertTrue(out.contains(HtmlPreviewDocument.PANEL_TOKEN))
        assertTrue(out.contains("window.parent.postMessage"))
        assertTrue(out.contains("window.Aster"))
    }

    @Test
    fun `disabled scripts without height never inject an action runtime`() {
        val out = HtmlPreviewDocument.wrap("<p>hi</p>", allowScripts = false, panelBridge = true)
        assertFalse(out.contains(HtmlPreviewDocument.PANEL_CAPTURE_MARKER))
        assertFalse(out.contains(HtmlPreviewDocument.PANEL_BRIDGE_MARKER))
        assertEquals("script-src 'none';", out.substringAfter("default-src 'none'; ").substringBefore(" style").trim())
    }

    @Test
    fun `bridged variant keeps connect-src none and sandbox flags`() {
        val out = HtmlPreviewDocument.wrap("<p>hi</p>", allowScripts = true, panelBridge = true)
        assertTrue(out.contains("connect-src 'none'"))
        assertTrue(out.contains("sandbox=\"allow-scripts\""))
    }

    @Test
    fun `source round-trips escaped inside srcdoc`() {
        val source = """<div class="x" data-a="1">& <b>bold</b><script>if(a<b){}</script></div>"""
        val out = HtmlPreviewDocument.wrap(source, allowScripts = true)
        val escaped = source.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;")
        assertTrue(out.contains("srcdoc=\"$escaped\""))
    }

    @Test
    fun `measurement only authorizes trusted tags and ignores helper capabilities`() {
        val out = HtmlPreviewDocument.wrap(
            "<details><summary>状态</summary>正文</details><script id='card'>bad()</script>",
            allowScripts = false, reportHeight = true, panelBridge = true,
            helperBundle = "<script id='helper'>helper()</script>", nonce = "test-measurement-nonce"
        )
        val outer = org.jsoup.Jsoup.parse(out)
        val policy = outer.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        val scriptRule = policy.split(';').first { it.trim().startsWith("script-src ") }
        assertTrue(scriptRule.contains("'nonce-test-measurement-nonce'"))
        assertFalse(scriptRule.contains("unsafe-inline"))
        assertFalse(scriptRule.contains("unsafe-eval"))
        assertTrue(policy.contains("style-src 'unsafe-inline'"))
        assertTrue(policy.contains("connect-src 'none'"))
        val frame = outer.selectFirst("iframe")!!
        assertEquals("allow-scripts", frame.attr("sandbox"))
        val inner = org.jsoup.Jsoup.parse(frame.attr("srcdoc"))
        assertEquals("", inner.getElementById("card")!!.attr("nonce"))
        assertEquals(null, inner.getElementById("helper"))
        assertEquals(1, inner.select("script[nonce=test-measurement-nonce]").size)
        assertEquals(1, outer.select("script[nonce=test-measurement-nonce]").size)
        assertFalse(inner.toString().contains(HtmlPreviewDocument.PANEL_CAPTURE_MARKER))
        assertTrue(outer.toString().contains("e.source!==frame.contentWindow"))
    }

    @Test
    fun `theme defaults have zero specificity and source styles retain precedence`() {
        val out = HtmlPreviewDocument.wrap("<style>html{color:red}</style><p>正文</p>",
            allowScripts = false, reportHeight = true, dark = true, nonce = "test-nonce")
        val inner = org.jsoup.Jsoup.parse(out).selectFirst("iframe")!!.attr("srcdoc")
        assertTrue(inner.startsWith("<!doctype html>"))
        assertTrue(inner.contains(":where(html){color:#F0EFEA;color-scheme:dark}"))
        assertTrue(inner.indexOf(":where(html)") < inner.indexOf("html{color:red}"))
    }

    @Test
    fun `a card with its own white background keeps dark browser text in dark app mode`() {
        val out = HtmlPreviewDocument.wrap("<body style='background:white'><p>正文</p></body>",
            allowScripts = false, reportHeight = true, dark = true, nonce = "test-nonce")
        val inner = org.jsoup.Jsoup.parse(out).selectFirst("iframe")!!.attr("srcdoc")
        assertTrue(inner.contains(":where(html){color:#141413;color-scheme:light}"))
        assertTrue(inner.contains("background:white"))
    }
}
