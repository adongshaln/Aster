package com.adong.adchat.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineImagesTest {
    @Test
    fun markdownImageAndBareLinkBecomeImages() {
        val pieces = splitInlineImages("封面如下\n![](https://example.com/a.jpg)\n直链 https://cdn.example.com/b.png")
        assertEquals(InlinePiece.Prose("封面如下\n"), pieces[0])
        assertEquals(InlinePiece.RemoteImage("https://example.com/a.jpg", ""), pieces[1])
        assertTrue(pieces[2] is InlinePiece.Prose)
        assertEquals(InlinePiece.RemoteImage("https://cdn.example.com/b.png", ""), pieces[3])
    }

    @Test
    fun markdownAndBareAddressesAreLinksWithoutSwallowingPunctuation() {
        val linked = linkAt("见 [维基](https://example.com/a) 这里", 2)
        assertEquals("维基", linked?.label)
        assertEquals("https://example.com/a", linked?.url)
        val bare = linkAt("打开 https://example.com/path。", 3)
        assertEquals("https://example.com/path", bare?.url)
        assertTrue(bare!!.end < "打开 https://example.com/path。".length)
    }

    @Test
    fun wrappedPixivOriginalBecomesOneImage() {
        val pieces = splitInlineImages(
            "图\nhttps://i.pximg.net/img-original/img/2025/09/16/01\n/05/33/135153978_p0.png"
        )
        assertEquals(
            InlinePiece.RemoteImage("https://i.pximg.net/img-original/img/2025/09/16/01/05/33/135153978_p0.png", ""),
            pieces.last()
        )
    }

    @Test
    fun markdownLinkIsNotSplitAtItsUrl() {
        val text = "作品 [97224444](https://www.pixiv.net/artworks/97224444) 完"
        val end = nextPlainEnd(text, 0, listOf("**", "*", "_"))
        assertEquals(text.indexOf('['), end)
        val link = linkAt(text, end)
        assertEquals("97224444", link?.label)
        assertEquals("https://www.pixiv.net/artworks/97224444", link?.url)
    }

    @Test
    fun brokenPixivPageLinkBecomesAnArtwork() {
        val pieces = splitInlineImages("[97224444](https://www.pixiv.net/artworks\n/97224444)")
        val art = pieces.filterIsInstance<InlinePiece.PixivArtwork>().single()
        assertEquals("97224444", art.id)
        assertEquals("https://www.pixiv.net/artworks/97224444", art.pageUrl)
    }
}
