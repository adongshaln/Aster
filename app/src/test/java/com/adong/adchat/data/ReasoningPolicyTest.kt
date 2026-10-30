package com.adong.adchat.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 各厂商的思考强度字段互不通用，混淆会让请求直接 400，这里逐个钉死。 */
class ReasoningPolicyTest {
    @Test fun familiesAreDetectedFromModelId() {
        assertEquals(ReasoningFamily.Gpt, ReasoningPolicy.family("gpt-5.6-sol"))
        assertEquals(ReasoningFamily.Gpt, ReasoningPolicy.family("openai/gpt-4.1"))
        assertEquals(ReasoningFamily.Grok, ReasoningPolicy.family("grok-4.6"))
        assertEquals(ReasoningFamily.Grok, ReasoningPolicy.family("xai/grok-4-fast"))
        assertEquals(ReasoningFamily.Claude, ReasoningPolicy.family("claude-sonnet-4.5"))
        assertEquals(ReasoningFamily.Glm, ReasoningPolicy.family("glm-4.6"))
        assertEquals(ReasoningFamily.KimiEffort, ReasoningPolicy.family("kimi-k3"))
        assertEquals(ReasoningFamily.KimiToggle, ReasoningPolicy.family("kimi-k2.6"))
        assertEquals(ReasoningFamily.DeepSeek, ReasoningPolicy.family("deepseek-chat"))
        assertEquals(ReasoningFamily.DeepSeek, ReasoningPolicy.family("deepseek-reasoner"))
        assertEquals(ReasoningFamily.Unsupported, ReasoningPolicy.family("gemini-2.5-pro"))
        assertEquals(ReasoningFamily.Unsupported, ReasoningPolicy.family(""))
    }

    @Test fun onlySupportedModelsOfferChoices() {
        assertTrue(ReasoningPolicy.choices("gpt-5.6-sol").isNotEmpty())
        assertTrue(ReasoningPolicy.choices("grok-4.6").isNotEmpty())
        assertTrue(ReasoningPolicy.choices("claude-opus-4.5").isNotEmpty())
        assertTrue(ReasoningPolicy.choices("glm-4.6").isNotEmpty())
        assertTrue(ReasoningPolicy.choices("kimi-k2.6").isNotEmpty())
        assertTrue(ReasoningPolicy.choices("deepseek-chat").isNotEmpty())
        assertTrue(ReasoningPolicy.choices("gemini-2.5-pro").isEmpty())
    }

    @Test fun grokCannotDisableReasoningAndOffersNoOffSwitch() {
        val ids = ReasoningPolicy.choices("grok-4.6").map { it.id }
        assertEquals(listOf("default", "low", "medium", "high", "xhigh"), ids)
        assertFalse(ReasoningPolicy.choices("grok-4.6").any { it.id == "none" })
    }

    @Test fun toggleFamiliesExposeOnOffInsteadOfEffortLevels() {
        assertEquals(listOf("default", "off", "on"), ReasoningPolicy.choices("kimi-k2.6").map { it.id })
        // 未登记过的档位回落到默认，不会把 on/off 之外的词当真值发出去。
        assertEquals("default", ReasoningPolicy.choiceOf("kimi-k2.6", "medium")?.id)
        assertEquals("on", ReasoningPolicy.choiceOf("kimi-k2.6", "on")?.id)
        assertEquals("default", ReasoningPolicy.choiceOf("gpt-5.6-sol", "sideways")?.id)
    }

    @Test fun gptWritesReasoningObjectOnResponsesAndStringOnChat() {
        val responses = JSONObject()
        ReasoningPolicy.write(responses, "gpt-5.6-sol", "high", responsesApi = true, outputTokenLimit = 8192)
        assertEquals(JSONObject().put("effort", "high").toString(), responses.getJSONObject("reasoning").toString())
        assertFalse(responses.has("reasoning_effort"))

        val chat = JSONObject()
        ReasoningPolicy.write(chat, "gpt-5.6-sol", "high", responsesApi = false, outputTokenLimit = 8192)
        assertEquals("high", chat.getString("reasoning_effort"))
        assertFalse(chat.has("reasoning"))
    }

    @Test fun grokAlwaysUsesTopLevelEffortField() {
        val responses = JSONObject()
        ReasoningPolicy.write(responses, "grok-4.6", "xhigh", responsesApi = true, outputTokenLimit = 8192)
        assertEquals("xhigh", responses.getString("reasoning_effort"))
        assertFalse(responses.has("reasoning"))
        assertTrue(ReasoningPolicy.forbidsSamplingControls("grok-4.6"))
        assertFalse(ReasoningPolicy.forbidsSamplingControls("deepseek-chat"))
    }

    @Test fun claudeUsesThinkingBudgetThatStaysBelowMaxTokens() {
        val body = JSONObject()
        ReasoningPolicy.write(body, "claude-sonnet-4.5", "high", responsesApi = false, outputTokenLimit = 8192)
        val thinking = body.getJSONObject("thinking")
        assertEquals("enabled", thinking.getString("type"))
        assertEquals(8192 - 1024, thinking.getInt("budget_tokens"))

        // 输出上限收紧到 3072 时，思考预算跟着收紧，始终给正文留出至少 1024。
        val tight = JSONObject()
        ReasoningPolicy.write(tight, "claude-sonnet-4.5", "high", responsesApi = false, outputTokenLimit = 3072)
        assertEquals(3072 - 1024, tight.getJSONObject("thinking").getInt("budget_tokens"))

        // 只剩 1536 时，任何 ≥1024 的思考预算都会挤掉正文；显式关闭也好过发出去撞 400。
        val impossible = JSONObject()
        ReasoningPolicy.write(impossible, "claude-sonnet-4.5", "high", responsesApi = false, outputTokenLimit = 1536)
        assertEquals("disabled", impossible.getJSONObject("thinking").getString("type"))

        // 未设置上下文长度时不知道 max_tokens，既不发 budget 也不擅自关掉服务端默认。
        val unknown = JSONObject()
        ReasoningPolicy.write(unknown, "claude-sonnet-4.5", "high", responsesApi = false, outputTokenLimit = 0)
        assertTrue(unknown.length() == 0)
    }

    @Test fun glmAndDeepSeekPairThinkingSwitchWithEffort() {
        val glm = JSONObject()
        ReasoningPolicy.write(glm, "glm-4.6", "low", responsesApi = false, outputTokenLimit = 8192)
        assertEquals("enabled", glm.getJSONObject("thinking").getString("type"))
        assertEquals("low", glm.getString("reasoning_effort"))

        val glmOff = JSONObject()
        ReasoningPolicy.write(glmOff, "glm-4.6", "none", responsesApi = false, outputTokenLimit = 8192)
        assertEquals("disabled", glmOff.getJSONObject("thinking").getString("type"))
        assertFalse(glmOff.has("reasoning_effort"))

        val deepseek = JSONObject()
        ReasoningPolicy.write(deepseek, "deepseek-chat", "max", responsesApi = true, outputTokenLimit = 8192)
        assertEquals("enabled", deepseek.getJSONObject("thinking").getString("type"))
        assertEquals("max", deepseek.getJSONObject("reasoning").getString("effort"))
        assertFalse(deepseek.has("reasoning_effort"))

        val deepseekChat = JSONObject()
        ReasoningPolicy.write(deepseekChat, "deepseek-chat", "low", responsesApi = false, outputTokenLimit = 8192)
        assertEquals("enabled", deepseekChat.getJSONObject("thinking").getString("type"))
        assertEquals("low", deepseekChat.getString("reasoning_effort"))

        val deepseekNoneResponses = JSONObject()
        ReasoningPolicy.write(deepseekNoneResponses, "deepseek-reasoner", "none", responsesApi = true, outputTokenLimit = 8192)
        assertEquals("disabled", deepseekNoneResponses.getJSONObject("thinking").getString("type"))
        assertEquals("none", deepseekNoneResponses.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun defaultAndUnsupportedModelsAddNothing() {
        listOf("gpt-5.6-sol", "grok-4.6", "claude-opus-4.5", "glm-4.6", "kimi-k3", "kimi-k2.6", "deepseek-chat")
            .forEach { model ->
                val body = JSONObject()
                ReasoningPolicy.write(body, model, ReasoningPolicy.DEFAULT, responsesApi = false, outputTokenLimit = 8192)
                assertTrue("$model 默认档不应写字段", body.length() == 0)
            }
        val unsupported = JSONObject()
        ReasoningPolicy.write(unsupported, "gemini-2.5-pro", "high", responsesApi = false, outputTokenLimit = 8192)
        assertTrue(unsupported.length() == 0)
    }

    @Test fun foreignEffortLevelsAreDroppedInsteadOfSent() {
        // 换模型后旧档位可能不在新家族的取值里，宁可回落到默认，也不要发出去等 400。
        val deepseek = JSONObject()
        ReasoningPolicy.write(deepseek, "deepseek-chat", "xhigh", responsesApi = false, outputTokenLimit = 8192)
        assertTrue(deepseek.length() == 0)

        val kimi = JSONObject()
        ReasoningPolicy.write(kimi, "kimi-k2.6", "medium", responsesApi = false, outputTokenLimit = 8192)
        assertTrue(kimi.length() == 0)

        val claude = JSONObject()
        ReasoningPolicy.write(claude, "claude-sonnet-4.5", "maximal", responsesApi = false, outputTokenLimit = 8192)
        assertTrue(claude.length() == 0)
    }
}
