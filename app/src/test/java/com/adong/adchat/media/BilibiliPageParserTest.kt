package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BilibiliPageParserTest {
    @Test
    fun extractsInitialStateVideoData() {
        val html = """
            <script>window.__INITIAL_STATE__={"videoData":{"bvid":"BV1234567890","cid":42,"title":"demo"}};(function(){})();</script>
        """.trimIndent()
        val data = BilibiliPageParser.extractVideoData(html)
        assertEquals("BV1234567890", data?.optString("bvid"))
        assertEquals(42L, data?.optLong("cid"))
    }

    @Test
    fun rejectsPagesWithoutVideoState() {
        assertNull(BilibiliPageParser.extractVideoData("<html><body>challenge</body></html>"))
    }
}
