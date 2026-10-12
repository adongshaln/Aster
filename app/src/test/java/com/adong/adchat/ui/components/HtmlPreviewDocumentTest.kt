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
        assertTrue(out.contains("window.parent.postMessage"))
        assertTrue(out.contains("window.Aster"))
    }

    @Test
    fun `panel bridge without scripts still injects listener markup`() {
        val out = HtmlPreviewDocument.wrap("<p>hi</p>", allowScripts = false, panelBridge = true)
        assertTrue(out.contains(HtmlPreviewDocument.PANEL_CAPTURE_MARKER))
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
}
