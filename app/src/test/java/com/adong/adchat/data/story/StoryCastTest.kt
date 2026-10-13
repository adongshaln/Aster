package com.adong.adchat.data.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryCastTest {
    @Test
    fun characterCardIgnoresSurroundingProse() {
        val card = StoryCharacterCard.parse("好的。\n```json\n{\"name\":\"林夏\",\"description\":\"住在旧书店\",\"personality\":\"寡言\",\"scenario\":\"雨夜\"}\n```")
        assertEquals("林夏", card.name)
        assertTrue(card.description.contains("旧书店"))
    }

    @Test
    fun personaIsJustTheUser() {
        assertEquals("旁白，偶尔下场", parseUserPersona("{\"persona\":\"旁白，偶尔下场\"}"))
    }

    @Test
    fun confirmedCastIsSentWithTheStoryPrompt() {
        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "写正文",
            memoryRecords = emptyList(),
            proposals = emptyList(),
            proseMessages = emptyList(),
            discussionMessages = emptyList(),
            cast = StoryCastContext(
                card = StoryCharacterCard("梅尔文", "一个人", "冷淡", "Fate/strange Fake 同人"),
                userPersona = "旁白"
            )
        )
        assertTrue(result.systemPrompt.contains("梅尔文"))
        assertTrue(result.systemPrompt.contains("Fate/strange Fake 同人"))
        assertTrue(result.systemPrompt.contains("旁白"))
        assertFalse(result.systemPrompt.contains("{{"))
    }

    @Test
    fun worldBookEntriesAreInjectedAroundTheCardHeader() {
        val card = StoryCharacterCard(
            name = "梅尔文", description = "一个人", personality = "冷淡", scenario = "Fate/strange Fake 同人",
            worldBook = StoryWorldBook(
                entries = listOf(
                    StoryWorldBookEntry(keys = emptyList(), content = "雨夜横滨的常量背景", constant = true, beforeChar = true),
                    StoryWorldBookEntry(keys = listOf("圣杯"), content = "圣杯战争的隐秘规则"),
                    StoryWorldBookEntry(keys = listOf("完全不相关的关键词"), content = "不应出现的词条")
                )
            )
        )
        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "写正文",
            memoryRecords = emptyList(),
            proposals = emptyList(),
            proseMessages = listOf(message("u1", "user", "今晚圣杯有动静", 1), message("a1", "assistant", "梅尔文抬头。", 2)),
            discussionMessages = emptyList(),
            cast = StoryCastContext(card = card, userPersona = "旁白")
        )
        val header = result.systemPrompt.indexOf("[已确认的对方角色卡")
        assertTrue(header > 0)
        assertTrue(result.systemPrompt.indexOf("雨夜横滨的常量背景") < header)
        assertTrue(result.systemPrompt.indexOf("圣杯战争的隐秘规则") > header)
        assertFalse(result.systemPrompt.contains("不应出现的词条"))
        assertEquals("雨夜横滨的常量背景", result.worldBefore)
        assertEquals("圣杯战争的隐秘规则", result.worldAfter)
    }

    @Test
    fun originalMacroInCardIsReplacedWithWorkspaceInstruction() {
        val card = StoryCharacterCard(
            name = "梅尔文", description = "一个人", personality = "冷淡", scenario = "同人",
            systemPrompt = "保持文风。{{original}}", imported = true
        )
        val result = StoryContextComposer.compose(
            workspace = StoryWorkspace.Prose,
            baseInstruction = "写正文",
            memoryRecords = emptyList(),
            proposals = emptyList(),
            proseMessages = emptyList(),
            discussionMessages = emptyList(),
            cast = StoryCastContext(card = card, userPersona = "")
        )
        assertFalse(result.systemPrompt.contains("{{original}}"))
        assertFalse(result.systemPrompt.contains("{{Original}}"))
        assertTrue(result.systemPrompt.contains("保持文风。写正文"))
    }

    @Test
    fun cardTextWithBracesDoesNotBreakExtraction() {
        val withBrace = """{"name":"林夏","description":"规则 { 不挂牌 } 写在纸上"}"""
        val card = StoryCharacterCard.parse(withBrace)
        assertEquals("林夏", card.name)
        assertTrue(card.description.contains("不挂牌"))

        val prose = "说明文字带 { 括号 }\n{\"name\":\"乔\",\"description\":\"掌事\"}"
        val fromProse = StoryCharacterCard.parse(prose)
        assertEquals("乔", fromProse.name)
    }

    private fun message(id: String, role: String, content: String, sequence: Long): StoryMessageWithRevision {
        val revisionId = "revision-$id"
        return StoryMessageWithRevision(
            message = StoryMessage(
                id = id, storyId = "story", timelineId = "timeline", workspace = StoryWorkspace.Prose,
                role = role, sequence = sequence, activeRevisionId = revisionId, createdAt = sequence
            ),
            revision = StoryMessageRevision(
                id = revisionId, messageId = id, storyId = "story", timelineId = "timeline",
                workspace = StoryWorkspace.Prose, content = content, state = StoryRevisionState.Complete,
                createdAt = sequence, completedAt = sequence
            )
        )
    }
}
