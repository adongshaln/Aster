package com.adong.adchat.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidPreviewDocumentTest {
    @Test
    fun modelSourceCannotCloseScriptOrCreateNewHtml() {
        val source = "graph TD; A['</script><img src=x>']\nB['quote\\ slash\u2028line\u2029']"
        val document = MermaidPreviewDocument.wrap(source, "/* bundled runtime */")
        assertFalse(document.contains("</script><img"))
        assertTrue(document.contains("\\u003C/script>"))
        assertTrue(document.contains("\\u2028"))
        assertTrue(document.contains("\\u2029"))
        assertTrue(document.contains("connect-src 'none'"))
        assertTrue(document.contains("securityLevel:'strict'"))
    }

    @Test
    fun asyncRenderingErrorsHaveAVisibleFallback() {
        val document = MermaidPreviewDocument.wrap("invalid", "/* bundled runtime */")
        assertTrue(document.contains(".catch(renderFailed)"))
        assertTrue(document.contains("setAttribute('role','alert')"))
        assertTrue(document.contains("图表渲染失败，请检查图表代码"))
    }

    @Test
    fun libraryScriptClosingTagsAreSanitizedCaseInsensitively() {
        val safe = MermaidPreviewDocument.sanitizeScript("var a='</ScRiPt>'; var b='</script>';")
        assertFalse(safe.contains("</script", ignoreCase = true))
        assertTrue(safe.contains("<\\/script>"))
    }
}
