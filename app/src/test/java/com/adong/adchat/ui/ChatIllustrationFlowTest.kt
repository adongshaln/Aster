package com.adong.adchat.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.adong.adchat.data.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ChatIllustrationFlowTest {
    private lateinit var app: Application
    private lateinit var server: MockWebServer
    private val stores = mutableListOf<ViewModelStore>()
    private val requests = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()
    private val plans = AtomicInteger()
    private val drawings = AtomicInteger()
    private val replies = AtomicInteger()
    private var releasePlan = CountDownLatch(0)
    private var failFirstDrawing = false
    private var finishReason = "stop"

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        listOf("adchat_api_config", "adchat_conversations", "adchat_stream_recovery", "adchat_chat_session",
            "adchat_artworks", "adchat_prompt_injection").forEach {
            app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        server = MockWebServer().apply { start() }
        val profiles = listOf("original", "other", "drawing").map { id ->
            ApiProfile(id = id, name = id, baseUrl = server.url("/").toString(), apiKey = "fixture-key",
                chatModel = "$id-model", imageModel = "another-image-model", autoResumeStream = false)
        }
        ConfigStore(app).save(AppConfig(profiles = profiles, activeChatProfileId = "original", activeImageProfileId = "drawing",
            systemPrompt = "The knight has silver hair and wears a blue cloak."))
        // An ordinary creative preset proves the original chat route is retained when injection is enabled.
        PromptInjectionStore(app).apply {
            savePreset(PromptInjectionPreset(id = "creative", name = "Style", description = "Fixture",
                pureMode = false, zeroWidthObfuscation = false,
                entries = listOf(PromptInjectionEntry(name = "Style", content = "Use vivid descriptions."))))
            setEnabled(true)
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                requests += body
                if (request.path == "/v1/images/generations") {
                    val count = drawings.incrementAndGet()
                    if (failFirstDrawing && count == 1) return MockResponse().setResponseCode(400)
                        .setBody("""{"error":{"message":"fixture image failure"}}""")
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", PNG))).toString())
                }
                if (body.optBoolean("stream")) {
                    replies.incrementAndGet()
                    return completion("The silver-haired knight raises her lantern beneath the rain.", finishReason)
                }
                plans.incrementAndGet()
                releasePlan.await(6, TimeUnit.SECONDS)
                return completion(JSONObject().put("title", "雨中的灯火").put("focus", "骑士在雨中举起灯笼")
                    .put("prompt", "A silver-haired knight in a blue cloak raises a lantern in the rain, intimate portrait, warm light.").toString())
            }
        }
    }

    @After fun tearDown() {
        releasePlan.countDown()
        stores.forEach(ViewModelStore::clear)
        shadowOf(Looper.getMainLooper()).idle()
        server.shutdown()
    }

    @Test fun originalModelAndConversationAreRetainedAfterSwitchingWhilePlannerRuns() {
        releasePlan = CountDownLatch(1)
        val vm = createVm()
        vm.setChatIllustrationsEnabled(true)
        send(vm)
        await { !vm.isChatLoading && plans.get() == 1 }
        val originalId = vm.activeConversationId!!
        val sourceMessageId = vm.messages.last().id
        vm.selectChatModel("other", "different-model")
        vm.newConversation()
        assertFalse(vm.chatIllustrationsEnabled)
        releasePlan.countDown()
        await { vm.conversations.first { it.id == originalId }.messages.last().illustration?.status == IllustrationStatus.Complete }

        assertTrue(vm.messages.isEmpty())
        val illustration = vm.conversations.first { it.id == originalId }.messages.last().illustration!!
        assertEquals("original-model", illustration.textModel)
        assertEquals("original", illustration.textProfileId)
        assertTrue(File(illustration.source.removePrefix("file://")).isFile)
        assertEquals(1, plans.get())
        assertEquals(1, drawings.get())
        val planRequest = requests.single { !it.has("stream") && it.has("messages") }
        assertEquals("original-model", planRequest.getString("model"))
        assertFalse(planRequest.has("tools"))
        assertFalse(planRequest.toString().contains("Use vivid descriptions."))
        val drawRequest = requests.single { it.has("prompt") }
        assertEquals(CHAT_ILLUSTRATION_MODEL, drawRequest.getString("model"))
        vm.selectConversation(originalId)
        assertEquals(sourceMessageId, vm.messages.last().id)
        assertTrue(vm.chatIllustrationsEnabled)
        await { ConversationStore(app).load().first { it.id == originalId }.messages.last().illustration?.status == IllustrationStatus.Complete }
        await { ChatSessionStore(app).load().lastActiveKey == originalId }
        assertEquals(illustration, createVm().messages.last().illustration)
    }

    @Test fun failedImageRetriesOnlyDrawingAndDoesNotPolluteTheNextChatRequest() {
        failFirstDrawing = true
        val vm = createVm()
        vm.setChatIllustrationsEnabled(true)
        send(vm)
        await { vm.messages.lastOrNull()?.illustration?.status == IllustrationStatus.Failed }
        val message = vm.messages.last()
        assertFalse(message.isError)
        assertFalse(vm.isChatLoading)
        vm.retryChatIllustration(message.id)
        vm.retryChatIllustration(message.id) // Repeated taps must not duplicate a paid request.
        await { vm.messages.last().illustration?.status == IllustrationStatus.Complete }
        assertEquals(1, plans.get())
        assertEquals(2, drawings.get())
        vm.setChatIllustrationsEnabled(false)
        send(vm)
        await { !vm.isChatLoading && replies.get() == 2 }
        val next = requests.last { it.optBoolean("stream") }.toString()
        assertFalse(next.contains("file://"))
        assertFalse(next.contains("intimate portrait"))
        assertFalse(next.contains("illustration"))
        assertEquals(1, plans.get())
    }

    @Test fun editingTheSourceWhilePlanningPreventsStaleDrawingAndRegenerationCanIllustrate() {
        releasePlan = CountDownLatch(1)
        val vm = createVm()
        vm.setChatIllustrationsEnabled(true)
        send(vm)
        await { plans.get() == 1 && !vm.isChatLoading }
        vm.editUserMessage(vm.messages.first().id)
        releasePlan.countDown()
        await { vm.activeImageCount == 0 }
        assertTrue(vm.messages.isEmpty())
        assertEquals(0, drawings.get())
        send(vm)
        await { vm.messages.lastOrNull()?.illustration?.status == IllustrationStatus.Complete }
        val oldSource = vm.messages.last().illustration!!.source
        vm.regenerateMessage(vm.messages.last().id)
        await { vm.messages.lastOrNull()?.illustration?.status == IllustrationStatus.Complete && replies.get() == 3 }
        assertEquals(2, drawings.get())
        assertNotEquals(oldSource, vm.messages.last().illustration!!.source)
        assertTrue(vm.chatIllustrationsEnabled)
    }

    @Test fun incompleteReplyNeverTriggersPlannerOrDrawing() {
        finishReason = "length"
        val vm = createVm()
        vm.setChatIllustrationsEnabled(true)
        send(vm)
        await { !vm.isChatLoading && replies.get() == 1 }
        assertTrue(vm.messages.last().isInterrupted)
        assertNull(vm.messages.last().illustration)
        assertEquals(0, plans.get())
        assertEquals(0, drawings.get())
    }

    @Test fun restartKeepsTheScenePromptWithoutAutomaticallyReplayingAnInterruptedTask() {
        val message = ChatMessage(id = 81, role = "assistant", content = "A knight raises a lantern.")
        val image = ChatIllustration(sourceRevision = ChatIllustrationPlanner.revision(message), textProfileId = "original",
            textModel = "old-model", imageProfileId = "drawing", status = IllustrationStatus.Generating,
            prompt = "A silver-haired knight raises a lantern in the rain.")
        val conversation = Conversation(id = "saved", title = "Saved", profileId = "original", model = "original-model",
            illustrationsEnabled = true, messages = listOf(message.copy(illustration = image)))
        ConversationStore(app).save(listOf(conversation))
        val vm = createVm()
        assertTrue(vm.chatIllustrationsEnabled)
        assertEquals(IllustrationStatus.Failed, vm.messages.single().illustration!!.status)
        assertEquals(image.prompt, vm.messages.single().illustration!!.prompt)
        assertEquals(0, server.requestCount)
    }

    private fun createVm(): MainViewModel = MainViewModel(app).also { vm -> stores += ViewModelStore().apply { put("main", vm) } }
    private fun send(vm: MainViewModel) { vm.updateChatInput("Continue the scene."); vm.sendMessage() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for illustration state")
    }
    private fun completion(text: String, finish: String = "stop") = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", text)))).toString())

    private companion object {
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aVVkAAAAASUVORK5CYII="
    }
}
