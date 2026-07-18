package com.adong.adchat.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRouteTest {
    private val first = ApiProfile(id = "first", name = "供应商 A", chatModel = "model-a")
    private val second = ApiProfile(id = "second", name = "供应商 B", chatModel = "model-b")
    private val profiles = listOf(first, second)

    @Test
    fun explicitConversationRouteOverridesGlobalDefault() {
        val conversation = Conversation(
            title = "独立路由",
            messages = emptyList(),
            profileId = second.id,
            model = "model-b-pro"
        )

        assertEquals(
            ConversationRoute(second.id, "model-b-pro"),
            resolveConversationRoute(conversation, profiles, first.id)
        )
    }

    @Test
    fun legacyConversationInfersRouteFromLatestAssistantMessage() {
        val conversation = Conversation(
            title = "旧对话",
            messages = listOf(
                ChatMessage(role = "assistant", content = "旧回复", profileName = first.name, model = "old-model"),
                ChatMessage(role = "assistant", content = "新回复", profileName = second.name, model = "model-b-latest")
            )
        )

        assertEquals(
            ConversationRoute(second.id, "model-b-latest"),
            resolveConversationRoute(conversation, profiles, first.id)
        )
    }

    @Test
    fun newConversationUsesConfiguredDefaultRoute() {
        assertEquals(
            ConversationRoute(second.id, second.chatModel),
            resolveConversationRoute(null, profiles, second.id)
        )
    }

    @Test
    fun missingBoundProfileFallsBackWithoutLeakingOldModel() {
        val conversation = Conversation(
            title = "已删除供应商",
            messages = emptyList(),
            profileId = "deleted",
            model = "deleted-model"
        )

        assertEquals(
            ConversationRoute(first.id, first.chatModel),
            resolveConversationRoute(conversation, profiles, first.id)
        )
    }
}
