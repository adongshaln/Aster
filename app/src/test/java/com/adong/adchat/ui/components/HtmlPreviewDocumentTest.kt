package com.adong.adchat.ui.components

import org.junit.Assert.*
import org.junit.Test

class HtmlPreviewDocumentTest {
    @Test fun htmlCannotEscapeSrcdocAndAcquireTheParentOrigin() {
        val wrapped = HtmlPreviewDocument.wrap("\" ></iframe><script>parent.hacked=true</script>&中文")
        assertTrue(wrapped.contains("sandbox=\"allow-scripts\""))
        assertFalse(wrapped.contains("allow-same-origin"))
        assertFalse(wrapped.contains("<script>parent.hacked"))
        assertTrue(wrapped.contains("&quot; &gt;&lt;/iframe&gt;"))
        assertTrue(wrapped.contains("&amp;中文"))
        assertTrue(wrapped.contains("connect-src 'none'"))
        assertTrue(wrapped.contains("form-action 'none'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedDocumentDoesNotInstantiateAPreview() {
        HtmlPreviewDocument.wrap("x".repeat(HtmlPreviewDocument.MAX_PREVIEW_CHARS + 1))
    }
}
