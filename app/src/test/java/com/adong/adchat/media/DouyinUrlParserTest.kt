package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinUrlParserTest {
    @Test
    fun parsesDirectVideoUrlAndBuildsCanonicalResolver() {
        val parsed = DouyinUrlParser.parse("https://www.douyin.com/video/6961737553342991651")

        requireNotNull(parsed)
        assertEquals("6961737553342991651", parsed.videoId)
        assertEquals(
            "https://www.douyin.com/jingxuan?modal_id=6961737553342991651",
            parsed.resolverUrl
        )
    }

    @Test
    fun extractsShortLinkFromShareTextAndTrimsPunctuation() {
        val parsed = DouyinUrlParser.parse(
            "2.34 abc https://v.douyin.com/lpYsAUlL3OU/ 复制此链接，打开Dou音搜索。"
        )

        requireNotNull(parsed)
        assertEquals("https://v.douyin.com/lpYsAUlL3OU/", parsed.sourceUrl)
        assertNull(parsed.videoId)
        assertEquals(parsed.sourceUrl, parsed.resolverUrl)
    }

    @Test
    fun parsesModalAndAwemeIds() {
        assertEquals(
            "7234567890123456789",
            DouyinUrlParser.extractVideoId("https://www.douyin.com/jingxuan?modal_id=7234567890123456789")
        )
        assertEquals(
            "7234567890123456789",
            DouyinUrlParser.extractVideoId("https://www.douyin.com/?aweme_id=7234567890123456789")
        )
    }

    @Test
    fun acceptsOfficialSubdomainsOnly() {
        assertTrue(DouyinUrlParser.parse("https://m.douyin.com/share/video/6961737553342991651") != null)
        assertTrue(DouyinUrlParser.parse("https://www.iesdouyin.com/share/video/6961737553342991651") != null)
        assertNull(DouyinUrlParser.parse("https://douyin.com.example.org/video/6961737553342991651"))
        assertNull(DouyinUrlParser.parse("https://example.org/video/6961737553342991651"))
    }

    @Test
    fun rejectsMalformedAndNonHttpInput() {
        assertNull(DouyinUrlParser.parse("not a link"))
        assertNull(DouyinUrlParser.parse("javascript:alert(1)"))
        assertNull(DouyinUrlParser.parse("ftp://v.douyin.com/lpYsAUlL3OU/"))
    }
}
