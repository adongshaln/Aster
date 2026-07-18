package com.adong.adchat.media

import org.junit.Assert.assertEquals
import org.junit.Test

class BilibiliMediaPolicyTest {
    @Test
    fun normalizesCoverToSecureSizedWebp() {
        assertEquals(
            "https://i2.hdslb.com/bfs/archive/demo.jpg@672w_378h_1c.webp",
            BilibiliMediaPolicy.normalizeThumbnail("http://i2.hdslb.com/bfs/archive/demo.jpg")
        )
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/demo.jpg@672w_378h_1c.webp",
            BilibiliMediaPolicy.normalizeThumbnail("//i0.hdslb.com/bfs/archive/demo.jpg")
        )
    }

    @Test
    fun keepsAlreadyProcessedOrEmptyCover() {
        val processed = "https://i0.hdslb.com/bfs/archive/demo.jpg@320w_180h.webp"
        assertEquals(processed, BilibiliMediaPolicy.normalizeThumbnail(processed))
        assertEquals("", BilibiliMediaPolicy.normalizeThumbnail(""))
    }
}
