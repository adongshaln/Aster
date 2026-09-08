package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelContextTest {
    private fun user(text: String) = ChatMessage(role="user", content=text)
    private fun reply(text: String) = ChatMessage(role="assistant", content=text)
    private val limits = ModelContextLimits(4096, 512)

    @Test fun settingsRoundTripAndModelIsolation() {
        val settings = mapOf("gemini-custom" to ModelContextLimits(1048576,8192), "gpt-custom" to ModelContextLimits(131072,16384))
        val restored = ModelContextSettings.decode(JSONObject(ModelContextSettings.encode(settings).toString()))
        assertEquals(settings, restored)
        assertEquals(1048576, ApiProfile(modelContexts=restored).contextLimits("gemini-custom")?.windowTokens)
        assertNull(ApiProfile(modelContexts=restored).contextLimits("other-model"))
        assertNull(ApiProfile().contextLimits("gemini-custom"))
        assertTrue(ModelContextSettings.decode(null).isEmpty())
    }
    @Test fun invalidLimitsCannotSilentlyWrapOrExhaustInput() {
        for (value in listOf(ModelContextLimits(-1,100), ModelContextLimits(Int.MAX_VALUE,256), ModelContextLimits(4096,4000))) {
            assertTrue(runCatching { value.validate() }.isFailure)
        }
        assertTrue(runCatching { ModelContextSettings.decode(JSONObject("{\"x\":{\"windowTokens\":4096,\"outputTokens\":4000}}")) }.isFailure)
    }
    @Test fun keepsLatestInputAndContinuousWholeTurnsWithoutChangingStoredHistory() {
        val history = listOf(user("older"),reply("small"),user("middle"),reply("中".repeat(2000)),user("current"))
        val result = ModelContextPolicy.prepare("system",history,limits,true)
        assertEquals(listOf(history.last()), result.history)
        assertEquals(2,result.omittedTurns)
        assertEquals(5,history.size)
    }
    @Test fun oversizedCurrentInputAndImagesAreBlocked() {
        assertTrue(runCatching { ModelContextPolicy.prepare("",listOf(user("中".repeat(2000))),limits,true) }.isFailure)
        val image = ChatImageAttachment(uri="local",name="image")
        assertTrue(runCatching { ModelContextPolicy.prepare("",listOf(user("see").copy(attachments=listOf(image))),limits,true) }.isFailure)
    }
    @Test fun storyContextIsNeverTrimmedAgainAndLegacyRemainsUnchanged() {
        val history = listOf(user("old"),reply("中".repeat(2000)),user("current"))
        assertTrue(runCatching { ModelContextPolicy.prepare("",history,limits,false) }.isFailure)
        assertEquals(history,ModelContextPolicy.prepare("",history,null,false).history)
    }
    @Test fun toolResultsAndPreviousResponseContextCountWithoutBase64Inflation() {
        val image=JSONObject().put("type","input_image").put("image_url","data:image/jpeg;base64,"+"a".repeat(100000))
        assertEquals(4096L,ModelContextPolicy.wireCost(image))
        val body=JSONObject().put("input",JSONArray().put(JSONObject().put("type","function_call_output").put("output","中".repeat(2000))))
        assertTrue(runCatching { ModelContextPolicy.applyToRequest(body,limits,true) }.isFailure)
        assertTrue(runCatching { ModelContextPolicy.applyToRequest(JSONObject().put("input",JSONArray()),limits,true,4000) }.isFailure)
    }
    @Test fun requestUsesActualModelLimitAndCorrectProtocolField() = runBlocking {
        for (model in listOf("gemini-custom","gpt-custom")) {
            val server=MockWebServer();server.start()
            try {
                val responses=model.startsWith("gpt")
                server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(if(responses)
                    "{\"id\":\"r\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}"
                    else "{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}"))
                val profile=ApiProfile(baseUrl=server.url("/").toString(),chatModel="other",modelContexts=mapOf(model to limits))
                ApiRepository().streamChat(profile,model,"",listOf(user("hi")),"test") {}
                val request=server.takeRequest(2,TimeUnit.SECONDS)!!
                val body=JSONObject(request.body.readUtf8())
                assertEquals(512,body.getInt(if(responses) "max_output_tokens" else "max_tokens"))
                assertFalse(body.has(if(responses) "max_tokens" else "max_output_tokens"))
            } finally {server.shutdown()}
        }
    }
}
