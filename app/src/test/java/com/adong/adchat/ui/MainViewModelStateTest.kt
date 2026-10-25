package com.adong.adchat.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.AppConfig
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.ChatSessionStore
import com.adong.adchat.data.ConfigStore
import com.adong.adchat.data.Conversation
import com.adong.adchat.data.ConversationStore
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MainViewModelStateTest {
    private lateinit var application: Application
    private lateinit var server: MockWebServer
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() {
        application = RuntimeEnvironment.getApplication()
        listOf("adchat_api_config", "adchat_conversations", "adchat_stream_recovery", "adchat_chat_session", "adchat_artworks")
            .forEach { application.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        server = MockWebServer().apply { start() }
        configure(suggestions = false)
    }

    @After fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        shadowOf(Looper.getMainLooper()).idle()
        server.shutdown()
    }

    @Test fun regenerateImageOnlyTurnPreservesTheImageAndSendsANewRequest() {
        server.enqueue(completion("第二次回答"))
        val vm = createViewModel()
        val image = ChatImageAttachment(uri = "file:///original.jpg", name = "original.jpg", bytes = byteArrayOf(1, 2, 3))
        vm.messages += ChatMessage(id = 10, role = "user", content = "", attachments = listOf(image))
        vm.messages += ChatMessage(id = 11, role = "assistant", content = "第一次回答")
        vm.updateChatInput("下一轮的草稿")
        val draftImage = ChatImageAttachment(uri = "file:///draft.jpg", name = "draft.jpg")
        vm.chatAttachments += draftImage

        vm.regenerateMessage(11)

        await { !vm.isChatLoading && server.requestCount == 1 }
        val user = vm.messages.first()
        assertEquals("", user.content)
        assertEquals(image, user.attachments.single())
        assertEquals("第一次回答", vm.messages.last().previousContent)
        assertEquals("第二次回答", vm.messages.last().content)
        assertEquals("下一轮的草稿", vm.chatInput)
        assertEquals(listOf(draftImage), vm.chatAttachments)
        assertEquals("data:image/jpeg;base64,AQID", requestedImageUrl())
    }

    @Test fun retryFailedImageOnlyTurnPreservesTheImage() {
        server.enqueue(completion("图片已收到"))
        val vm = createViewModel()
        val image = ChatImageAttachment(uri = "file:///retry.jpg", name = "retry.jpg", bytes = byteArrayOf(4, 5, 6))
        vm.messages += ChatMessage(id = 20, role = "user", content = "", attachments = listOf(image))
        vm.messages += ChatMessage(id = 21, role = "assistant", content = "连接失败", isError = true)

        vm.retryMessage(21)

        await { !vm.isChatLoading && server.requestCount == 1 }
        assertEquals(image, vm.messages.first().attachments.single())
        assertEquals("图片已收到", vm.messages.last().content)
        assertEquals("data:image/jpeg;base64,BAUG", requestedImageUrl())
    }

    @Test fun editingFirstTurnPersistsEmptyHistoryAndRestoresDraftWithoutMixingImages() {
        val image = ChatImageAttachment(id = "original-image", uri = "file:///original.jpg", name = "original.jpg")
        val old = Conversation(id = "conversation", title = "原对话", profileId = "test", model = "gemini-test", messages = listOf(
            ChatMessage(id = 30, role = "user", content = "准备编辑的问题", attachments = listOf(image)),
            ChatMessage(id = 31, role = "assistant", content = "旧回答")
        ))
        ConversationStore(application).save(listOf(old))
        val vm = createViewModel()
        vm.updateChatInput("未发送的其他草稿")
        vm.chatAttachments += ChatImageAttachment(uri = "file:///other.jpg", name = "other.jpg")

        vm.editUserMessage(30)

        assertTrue(vm.messages.isEmpty())
        assertEquals(listOf(image), vm.chatAttachments)
        await {
            ConversationStore(application).load().singleOrNull()?.messages?.isEmpty() == true &&
                ChatSessionStore(application).load().drafts[old.id] == "准备编辑的问题"
        }
        val restored = createViewModel()
        assertEquals(old.id, restored.activeConversationId)
        assertTrue(restored.messages.isEmpty())
        assertEquals("准备编辑的问题", restored.chatInput)
        assertEquals("gemini-test", restored.chatProfile.chatModel)
    }

    @Test fun doesNotRequestSuggestionWhenUserHasAlreadyStartedTheirNextDraft() {
        configure(suggestions = true)
        server.enqueue(completion("回答").setBodyDelay(150, TimeUnit.MILLISECONDS))
        server.enqueue(completion("旧预测"))
        val vm = createViewModel()
        vm.updateChatInput("问题")
        vm.sendMessage()
        vm.updateChatInput("我正在输入下一个问题")

        await { !vm.isChatLoading && idle(vm) }

        assertEquals(1, server.requestCount)
        assertNull(vm.inputSuggestion)
        assertEquals("我正在输入下一个问题", vm.chatInput)
    }

    @Test fun typingThenClearingDraftInvalidatesAnInFlightSuggestion() {
        configure(suggestions = true)
        server.enqueue(completion("回答"))
        server.enqueue(completion("旧预测").setBodyDelay(300, TimeUnit.MILLISECONDS))
        val vm = createViewModel()
        vm.updateChatInput("问题")
        vm.sendMessage()
        await { server.requestCount == 2 }

        vm.updateChatInput("新输入")
        vm.updateChatInput("")
        await { idle(vm) }

        assertNull(vm.inputSuggestion)
    }

    @Test fun anotherReplyDoesNotDuplicateAnInFlightTitleSummary() {
        val titleRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.body.readUtf8().contains("根据对话写一个不超过16个字")) {
                    titleRequests.incrementAndGet()
                    completion("自动标题").setBodyDelay(500, TimeUnit.MILLISECONDS)
                } else completion("回复")
            }
        }
        val vm = createViewModel()
        repeat(2) { turn ->
            vm.messages += ChatMessage(role = "user", content = "问题$turn")
            vm.messages += ChatMessage(role = "assistant", content = "回答$turn")
        }
        vm.sendMessage("第三轮")
        await { !vm.isChatLoading && titleRequests.get() == 1 }
        vm.sendMessage("第四轮")
        await { !vm.isChatLoading && idle(vm) }

        assertEquals(1, titleRequests.get())
        assertEquals(3, server.requestCount)
        assertEquals("自动标题", vm.conversations.single().title)
    }

    @Test fun changingDefaultProfilePreservesHistoricalRouteAndDraftForItsNextVisit() {
        val config = ConfigStore(application).load()
        val nextProfile = config.chatProfile().copy(id = "next-profile", name = "另一个接口", chatModel = "next-model")
        ConfigStore(application).save(config.copy(profiles = config.profiles + nextProfile))
        val old = savedConversationWithDraft()
        val vm = createViewModel()
        val draftImage = ChatImageAttachment(uri = "file:///draft.jpg", name = "draft.jpg")
        vm.chatAttachments += draftImage

        vm.selectDefaultChatProfile(nextProfile.id)

        assertEquals(nextProfile.id, ConfigStore(application).load().chatProfile().id)
        assertEquals(old.id, vm.activeConversationId)
        assertEquals(old.profileId, vm.chatProfile.id)
        assertEquals(old.model, vm.chatProfile.chatModel)
        assertEquals(old.messages, vm.messages.toList())
        assertEquals("历史会话的未发送草稿", vm.chatInput)
        assertEquals(listOf(draftImage), vm.chatAttachments)
        assertEquals(old, ConversationStore(application).load().single())

        vm.newConversation()
        assertNull(vm.activeConversationId)
        assertEquals(nextProfile.id, vm.chatProfile.id)
        assertEquals(nextProfile.chatModel, vm.chatProfile.chatModel)
        assertEquals("新会话的未发送草稿", vm.chatInput)

        vm.selectConversation(old.id)
        assertEquals(old.model, vm.chatProfile.chatModel)
        assertEquals("历史会话的未发送草稿", vm.chatInput)
        assertEquals(0, server.requestCount)
    }

    @Test fun changingDefaultModelOnTheHistoricalProfileOnlyAffectsNewConversations() {
        val old = savedConversationWithDraft()
        val vm = createViewModel()
        vm.updateChatInput("继续编辑中的历史草稿")

        vm.selectDefaultChatModel(old.profileId, "  replacement-model  ")

        val persistedDefault = ConfigStore(application).load().chatProfile()
        assertEquals(old.profileId, persistedDefault.id)
        assertEquals("replacement-model", persistedDefault.chatModel)
        assertEquals(old.id, vm.activeConversationId)
        assertEquals(old.model, vm.chatProfile.chatModel)
        assertEquals(old.messages, vm.messages.toList())
        assertEquals("继续编辑中的历史草稿", vm.chatInput)
        assertEquals(old, ConversationStore(application).load().single())

        vm.newConversation()
        assertEquals("replacement-model", vm.chatProfile.chatModel)
        assertEquals("新会话的未发送草稿", vm.chatInput)
        vm.selectConversation(old.id)
        assertEquals(old.model, vm.chatProfile.chatModel)
        assertEquals("继续编辑中的历史草稿", vm.chatInput)
        await { ChatSessionStore(application).load().drafts[old.id] == "继续编辑中的历史草稿" }
        assertEquals(0, server.requestCount)
    }

    @Test fun savingProfileInvalidatesAnInFlightConnectionTest() {
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                releaseResponse.await(8, TimeUnit.SECONDS)
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"data":[{"id":"stale-model"}]}""")
            }
        }
        val vm = createViewModel()
        val original = vm.profiles.single()
        try {
            vm.testProfile(original)
            await { server.requestCount == 1 }
            val edited = original.copy(
                name = "修改后的服务", baseUrl = server.url("/updated").toString(),
                modelsPath = "/new-models", extraHeaders = "X-New: value", chatModel = "chosen-model"
            )
            vm.saveProfile(edited)
            val savedState = vm.connectionFor(edited.id)
            val savedNotice = vm.notice

            releaseResponse.countDown()
            await { idle(vm) }

            assertEquals(edited, vm.profiles.single())
            assertEquals(edited, ConfigStore(application).load().profiles.single())
            assertTrue(vm.modelsFor(edited.id).isEmpty())
            assertEquals(ConnectionPhase.Idle, savedState.phase)
            assertEquals(savedState, vm.connectionFor(edited.id))
            assertEquals(savedNotice, vm.notice)
        } finally {
            releaseResponse.countDown()
        }
    }

    private fun savedConversationWithDraft(): Conversation {
        val conversation = Conversation(
            id = "historical-route", title = "历史会话", profileId = "test", model = "historical-model",
            messages = listOf(ChatMessage(id = 100, role = "user", content = "原问题"),
                ChatMessage(id = 101, role = "assistant", content = "原回答"))
        )
        ConversationStore(application).save(listOf(conversation))
        ChatSessionStore(application).save(mapOf(
            conversation.id to "历史会话的未发送草稿",
            ChatSessionStore.NEW_CONVERSATION_KEY to "新会话的未发送草稿"
        ), conversation.id)
        return conversation
    }

    private fun configure(suggestions: Boolean) {
        val profile = ApiProfile(id = "test", baseUrl = server.url("/").toString(), chatModel = "gemini-test",
            suggestionModel = if (suggestions) "gemini-test" else "", autoResumeStream = false)
        ConfigStore(application).save(AppConfig(profiles = listOf(profile), activeChatProfileId = "test", activeImageProfileId = "test"))
    }

    private fun createViewModel(): MainViewModel = MainViewModel(application).also { vm ->
        stores += ViewModelStore().apply { put("main", vm) }
    }

    private fun completion(text: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"content":"$text"},"finish_reason":"stop"}]}""")

    private fun requestedImageUrl(): String {
        val messages = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
        val user = (0 until messages.length()).map(messages::getJSONObject).first { it.getString("role") == "user" }
        val parts = user.getJSONArray("content")
        val image = (0 until parts.length()).map(parts::getJSONObject).first { it.getString("type") == "image_url" }
        return image.getJSONObject("image_url").getString("url")
    }

    private fun idle(vm: MainViewModel): Boolean = vm.viewModelScope.coroutineContext[Job]!!.children.none { it.isActive }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for ViewModel state")
    }
}
