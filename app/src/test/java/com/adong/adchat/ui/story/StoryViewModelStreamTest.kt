package com.adong.adchat.ui.story

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.AppConfig
import com.adong.adchat.data.ConfigStore
import com.adong.adchat.data.story.Story
import com.adong.adchat.data.story.StoryDatabase
import com.adong.adchat.data.story.StoryRepository
import com.adong.adchat.data.story.StoryRevisionState
import com.adong.adchat.data.story.StoryWorkspace
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
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
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class StoryViewModelStreamTest {
    private lateinit var application: Application
    private lateinit var server: MockWebServer
    private lateinit var repository: StoryRepository
    private lateinit var story: Story
    private lateinit var profile: ApiProfile
    private lateinit var vm: StoryViewModel
    private val viewModels = ViewModelStore()

    @Before fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repository = StoryRepository(application)
        story = repository.createStory("测试故事", "test", "gemini-test")
        repository.setAutomaticMemoryEnabled(story.id, false)
        server = MockWebServer().apply { start() }
        profile = ApiProfile(id = "test", baseUrl = server.url("/").newBuilder().host("127.0.0.1").build().toString(),
            chatModel = "gemini-test", autoResumeStream = false)
        ConfigStore(application).save(AppConfig(profiles = listOf(profile), activeChatProfileId = "test", activeImageProfileId = "test"))
        vm = StoryViewModel(application)
        viewModels.put("story", vm)
        await { vm.activeStoryId == story.id && vm.workspaceState().timelineId == story.currentTimelineId }
    }

    @After fun tearDown() {
        viewModels.clear()
        await { vm.viewModelScope.coroutineContext[Job]!!.isCompleted }
        repository.close()
        server.shutdown()
    }

    @Test fun uiCanAdvanceBetweenDiskCheckpointsAndStopFlushesTheTailBeforeNextGeneration() {
        startSlowStream()
        await { (vm.messages().lastOrNull()?.revision?.content?.length ?: 0) >= 2 }
        val visibleBeforeStop = vm.messages().last().revision.content
        val checkpoint = storedAssistant().revision.content
        assertTrue("UI should not require a database write for each refresh", checkpoint.length < visibleBeforeStop.length)

        vm.stop()
        await { !vm.isLoading() }

        val stopped = storedAssistant()
        assertEquals(StoryRevisionState.Stopped, stopped.revision.state)
        assertTrue(stopped.revision.content.startsWith(visibleBeforeStop))
        assertEquals(stopped.revision, vm.messages().last().revision)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"新回复"},"finish_reason":"stop"}]}""")
        }
        vm.updateDraft("新的问题")
        vm.send(profile)
        await { !vm.isLoading() && vm.messages().lastOrNull()?.revision?.content == "新回复" }
        assertEquals(StoryRevisionState.Complete, storedAssistant().revision.state)
        assertEquals(4, vm.messages().size)
        assertEquals(StoryRevisionState.Stopped, vm.messages()[1].revision.state)
    }

    @Test fun viewModelCancellationPersistsUncheckpointedOutputAsInterrupted() {
        startSlowStream()
        await { (vm.messages().lastOrNull()?.revision?.content?.length ?: 0) >= 2 }
        val visible = vm.messages().last().revision.content

        viewModels.clear()
        await { vm.viewModelScope.coroutineContext[Job]!!.isCompleted }

        val interrupted = storedAssistant().revision
        assertEquals(StoryRevisionState.Interrupted, interrupted.state)
        assertTrue(interrupted.content.startsWith(visible))
    }

    @Test fun stoppingRegenerationBeforeFirstTextRestoresTheOriginalReply() {
        repository.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Discussion, "user", "原来的问题")
        val original = repository.appendMessage(story.id, story.currentTimelineId, StoryWorkspace.Discussion,
            "assistant", "应该保留的原回复", state = StoryRevisionState.Complete)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        vm.regenerateReply(profile, original)
        await { server.requestCount == 1 && vm.messages().lastOrNull()?.revision?.state == StoryRevisionState.Streaming }
        vm.stop()
        await { !vm.isLoading() }

        assertEquals(original.revision.id, storedAssistant().revision.id)
        assertEquals("应该保留的原回复", vm.messages().last().revision.content)
        assertEquals(StoryRevisionState.Complete, vm.messages().last().revision.state)
        assertEquals(2, vm.messages().size)
        assertEquals(2, repository.listRevisions(original.message.id).size)
        assertEquals(StoryRevisionState.Stopped, repository.listRevisions(original.message.id).last().state)
    }

    private fun startSlowStream() {
        val event = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"
        val response = MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(event.repeat(40) + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            .throttleBody(event.toByteArray().size.toLong(), 200, TimeUnit.MILLISECONDS)
        // QueueDispatcher.peek() also applies throttleBody while receiving the request.
        // Return the throttled response only after the (large story-context) POST is read.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = response
        }
        vm.updateDraft("讨论这个故事")
        vm.send(profile, StoryWorkspace.Discussion)
    }

    private fun storedAssistant() = repository.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Discussion).last()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for StoryViewModel state: loading=${vm.isLoading()}, error=${vm.error()}, " +
            "requests=${server.requestCount}, rows=${vm.messages().map { it.revision.state to it.revision.content }}")
    }
}
