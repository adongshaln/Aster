package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardFenceTest {
    @Test fun extractsPayloadFromClosedFence() {
        val json = """{"spec":"chara_card_v2","data":{"name":"梅尔文"}}"""
        val text = "好的，这是角色卡：\n```charactercard\n$json\n```\n如有需要可以再改。"
        assertEquals(json, CharacterCardFence.extract(text))
    }

    @Test fun bracesAndNewlinesInsidePayloadAreKept() {
        val json = "{\n  \"description\": \"调色盘 {底色} 结尾\",\n  \"tags\": [\"a\",\"b\"]\n}"
        val text = "```charactercard\n$json\n```"
        assertEquals(json, CharacterCardFence.extract(text))
    }

    @Test fun noFenceReturnsNull() {
        assertNull(CharacterCardFence.extract("这只是普通回复，没有代码块。"))
        assertNull(CharacterCardFence.extract(""))
    }

    @Test fun unclosedFenceFallsBackToTailLikeStoryProsePresentation() {
        val json = """{"spec":"chara_card_v2","data":{"name":"未闭合"}}"""
        val text = "生成中：\n```charactercard\n$json"
        assertEquals(json, CharacterCardFence.extract(text))
    }

    @Test fun plainJsonFenceIsNotMistakenForCharacterCard() {
        val json = """{"spec":"chara_card_v2","data":{"name":"X"}}"""
        assertNull(CharacterCardFence.extract("```json\n$json\n```"))
        assertNull(CharacterCardFence.extract("```\n$json\n```"))
    }

    @Test fun surroundingTextDoesNotLeakIntoPayload() {
        val json = """{"spec":"chara_card_v2","data":{"name":"夹在中间"}}"""
        val text = "前文说明\n```charactercard\n$json\n```\n后文说明"
        val extracted = CharacterCardFence.extract(text)!!
        assertTrue(extracted.startsWith("{"))
        assertTrue(extracted.endsWith("}"))
        assertFalse(extracted.contains("前文"))
        assertFalse(extracted.contains("后文"))
        assertEquals(json, extracted)
    }

    @Test fun closingFenceMustOwnItsLine() {
        // 闭合围栏后同行还有内容时，不构成闭合，走 \z 兜底提取到末尾。
        val text = "```charactercard\n{\"a\":1}\n```尾随文字"
        val extracted = CharacterCardFence.extract(text)
        assertTrue(extracted!!.contains("尾随文字"))
    }
}
