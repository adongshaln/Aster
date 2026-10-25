package com.adong.adchat.island

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarIslandContentParserTest {
    @Test fun recognizesTextAndPreservesOriginalWebParameters() {
        assertNull(StarIslandContentParser.parse(" \n "))
        assertEquals(StarIslandKind.Text, StarIslandContentParser.parse("需要解释的一段话")!!.kind)
        val url = "https://example.com/read?signature=a%2Bb&x=1&x=2&utm_source=mail#part"
        val content = StarIslandContentParser.parse("请读 ($url) 然后看 https://other.example/")!!
        assertEquals(url, content.url)
        assertEquals(StarIslandKind.Web, content.kind)
        assertTrue(content.text.startsWith("请读"))
    }

    @Test fun platformLookalikesAreWebPagesAndRealMediaStillWorks() {
        assertFalse(IslandLinkParser.isSupported("https://x.com.evil.example/status/123"))
        assertFalse(IslandLinkParser.isSupported("https://bilibili.com.evil.example/video/BV1xx411c7mD"))
        assertEquals(StarIslandKind.Web, StarIslandContentParser.parse("https://x.com.evil.example/status/123")!!.kind)
        assertEquals(StarIslandKind.Media, StarIslandContentParser.parse("https://x.com/name/status/123")!!.kind)
        assertEquals(StarIslandKind.Media, StarIslandContentParser.parse("https://cdn.example/video.mp4?signature=abc")!!.kind)
        assertFalse(IslandLinkParser.clean("https://www.douyin.com/video/123?msToken=secret&id=1")!!.contains("msToken"))
    }

    @Test fun rejectsCredentialsAndAmbiguousUrlAuthority() {
        for (url in listOf("https://user:secret@example.com/", "https://@example.com/", "https://example.com\\@private/")) {
            assertTrue(runCatching { StarIslandContentParser.parse(url) }.exceptionOrNull() is IllegalArgumentException)
        }
        // Non-web schemes remain text and never become an openable URL.
        assertNull(StarIslandContentParser.parse("javascript:alert(1)")!!.url)
    }

    @Test fun overlongContentFailsWithoutSilentlyChangingTheInput() {
        val text = "字".repeat(StarIslandContentParser.MAX_CONTENT_CHARS)
        assertEquals(text, StarIslandContentParser.parse(text)!!.text)
        val error = runCatching { StarIslandContentParser.parse(text + "字") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("内容过长"))
    }
}
