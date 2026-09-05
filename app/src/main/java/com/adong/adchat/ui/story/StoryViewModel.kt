package com.adong.adchat.ui.story

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.ApiRepository
import com.adong.adchat.data.story.Story
import com.adong.adchat.data.story.StoryArchiveStore
import com.adong.adchat.data.story.StoryContextComposer
import com.adong.adchat.data.story.StoryMemoryKind
import com.adong.adchat.data.story.StoryMemoryRecord
import com.adong.adchat.data.story.StoryMessageWithRevision
import com.adong.adchat.data.story.StoryRepository
import com.adong.adchat.data.story.StoryRevisionState
import com.adong.adchat.data.story.StoryStopCleanup
import com.adong.adchat.data.story.StoryWorkspace
import com.adong.adchat.data.story.StoryWorkspaceState
import com.adong.adchat.data.story.nextStoryWorkspaceUpdatedAt
import com.adong.adchat.data.story.storyStopCleanupFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class StoryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = StoryRepository(application)
    private val archiveStore = StoryArchiveStore(application)
    private val api = ApiRepository()
    private val jobs = linkedMapOf<String, Job>()
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()

    val stories = mutableStateListOf<Story>()
    val archiveRecords = mutableStateListOf<StoryMemoryRecord>()
    private val workspaceMessages = mutableStateMapOf<StoryWorkspace, List<StoryMessageWithRevision>>()
    private val workspaceStates = mutableStateMapOf<StoryWorkspace, StoryWorkspaceState>()
    private val loadingKeys = mutableStateMapOf<String, Boolean>()
    private val errors = mutableStateMapOf<StoryWorkspace, String>()

    var activeStoryId by mutableStateOf<String?>(null)
        private set
    var activeWorkspace by mutableStateOf(StoryWorkspace.Discussion)
        private set
    var archiveOpen by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = store.listStories()
            withContext(Dispatchers.Main) {
                stories.clear()
                stories.addAll(loaded)
                loaded.firstOrNull()?.let { selectStory(it.id) }
            }
        }
    }

    val activeStory: Story?
        get() = activeStoryId?.let { id -> stories.firstOrNull { it.id == id } }

    fun messages(workspace: StoryWorkspace = activeWorkspace): List<StoryMessageWithRevision> =
        workspaceMessages[workspace].orEmpty()

    fun workspaceState(workspace: StoryWorkspace = activeWorkspace): StoryWorkspaceState =
        workspaceStates[workspace] ?: StoryWorkspaceState(activeStoryId.orEmpty(), workspace)

    fun draft(workspace: StoryWorkspace = activeWorkspace): String = workspaceState(workspace).draft

    fun isLoading(workspace: StoryWorkspace = activeWorkspace): Boolean {
        val storyId = activeStoryId ?: return false
        return loadingKeys[jobKey(storyId, workspace)] == true
    }

    fun error(workspace: StoryWorkspace = activeWorkspace): String? = errors[workspace]
    fun clearError(workspace: StoryWorkspace = activeWorkspace) { errors.remove(workspace) }

    fun createStory(title: String, profile: ApiProfile, onCreated: (Story) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val story = store.createStory(title, profile.id, profile.chatModel)
            withContext(Dispatchers.Main) {
                stories.add(0, story)
                activeStoryId = story.id
                activeWorkspace = StoryWorkspace.Discussion
                workspaceMessages.clear()
                workspaceStates.clear()
                archiveRecords.clear()
                errors.clear()
                loadActiveStoryState(story)
                onCreated(story)
            }
        }
    }

    fun selectStory(storyId: String) {
        if (activeStoryId == storyId && workspaceMessages.isNotEmpty()) return
        val story = stories.firstOrNull { it.id == storyId } ?: return
        activeStoryId = storyId
        activeWorkspace = StoryWorkspace.Discussion
        workspaceMessages.clear()
        workspaceStates.clear()
        archiveRecords.clear()
        errors.clear()
        loadActiveStoryState(story)
    }

    fun switchWorkspace(workspace: StoryWorkspace) {
        activeWorkspace = workspace
        errors.remove(workspace)
    }

    fun openArchive() { archiveOpen = true }
    fun closeArchive() { archiveOpen = false }

    fun renameActiveStory(title: String) {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!store.renameStory(story.id, title)) return@launch
            val updated = store.getStory(story.id) ?: return@launch
            withContext(Dispatchers.Main) { replaceStory(updated) }
        }
    }

    fun replaceActiveRoute(profile: ApiProfile) {
        val story = activeStory ?: return
        val model = profile.chatModel
        if (model.isBlank()) {
            errors[activeWorkspace] = "请选择一个可用的对话模型。"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!store.updateStoryRoute(story.id, profile.id, model)) return@launch
            val updated = store.getStory(story.id) ?: return@launch
            withContext(Dispatchers.Main) {
                replaceStory(updated)
                errors.clear()
            }
        }
    }

    fun setAutomaticMemoryEnabled(enabled: Boolean) {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!store.setAutomaticMemoryEnabled(story.id, enabled)) return@launch
            val updated = store.getStory(story.id) ?: return@launch
            withContext(Dispatchers.Main) { replaceStory(updated) }
        }
    }

    fun deleteStory(storyId: String) {
        jobs.keys.filter { it.startsWith("$storyId|") }.forEach { key ->
            stopRequested += key
            jobs.remove(key)?.cancel(CancellationException("Story deleted"))
        }
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteStory(storyId)
            val remaining = store.listStories()
            withContext(Dispatchers.Main) {
                stories.clear()
                stories.addAll(remaining)
                if (activeStoryId == storyId) {
                    activeStoryId = remaining.firstOrNull()?.id
                    activeWorkspace = StoryWorkspace.Discussion
                    workspaceMessages.clear()
                    workspaceStates.clear()
                    archiveRecords.clear()
                    activeStory?.let(::loadActiveStoryState)
                }
            }
        }
    }

    fun updateDraft(value: String, workspace: StoryWorkspace = activeWorkspace) {
        val storyId = activeStoryId ?: return
        val current = workspaceState(workspace)
        val next = current.copy(
            storyId = storyId,
            draft = value,
            updatedAt = nextStoryWorkspaceUpdatedAt(current.updatedAt, System.currentTimeMillis())
        )
        workspaceStates[workspace] = next
        viewModelScope.launch(Dispatchers.IO) { store.saveWorkspaceState(next) }
    }

    fun saveScroll(workspace: StoryWorkspace, firstVisibleIndex: Int, firstVisibleOffset: Int) {
        val storyId = activeStoryId ?: return
        val current = workspaceState(workspace)
        val next = current.copy(
            storyId = storyId,
            firstVisibleIndex = firstVisibleIndex.coerceAtLeast(0),
            firstVisibleOffset = firstVisibleOffset,
            updatedAt = nextStoryWorkspaceUpdatedAt(current.updatedAt, System.currentTimeMillis())
        )
        workspaceStates[workspace] = next
        viewModelScope.launch(Dispatchers.IO) { store.saveWorkspaceState(next) }
    }

    fun addArchiveRecord(kind: StoryMemoryKind, content: String, pinned: Boolean) {
        val story = activeStory ?: return
        if (content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            archiveStore.addConfirmedRecord(
                storyId = story.id,
                timelineId = story.currentTimelineId,
                kind = kind,
                content = content,
                pinned = pinned
            )
            refreshArchive(story.id, story.currentTimelineId)
            refreshStory(story.id)
        }
    }

    fun updateArchiveRecord(recordId: String, content: String, pinned: Boolean) {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            archiveStore.updateConfirmedRecord(recordId, content, pinned)
            refreshArchive(story.id, story.currentTimelineId)
            refreshStory(story.id)
        }
    }

    fun setArchivePinned(recordId: String, pinned: Boolean) {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            archiveStore.setPinned(recordId, pinned)
            refreshArchive(story.id, story.currentTimelineId)
        }
    }

    fun removeArchiveRecord(recordId: String) {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            archiveStore.deactivateRecord(recordId)
            refreshArchive(story.id, story.currentTimelineId)
            refreshStory(story.id)
        }
    }

    fun send(profile: ApiProfile, workspace: StoryWorkspace = activeWorkspace) {
        val story = activeStory ?: return
        val input = draft(workspace).trim()
        if (input.isBlank()) return
        val key = jobKey(story.id, workspace)
        if (loadingKeys[key] == true) return
        if (profile.id != story.profileId) {
            errors[workspace] = "这个故事绑定的服务已不可用或已变化，请先在故事档案中选择新的模型。"
            return
        }
        val routeModel = story.model.ifBlank { profile.chatModel }
        if (routeModel.isBlank()) {
            errors[workspace] = "这个故事还没有可用的模型。"
            return
        }

        updateDraft("", workspace)
        errors.remove(workspace)
        loadingKeys[key] = true
        stopRequested.remove(key)

        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var assistant: StoryMessageWithRevision? = null
            val streamed = StringBuilder()
            var lastPersistAt = 0L
            try {
                store.appendMessage(
                    storyId = story.id,
                    timelineId = story.currentTimelineId,
                    workspace = workspace,
                    role = "user",
                    content = input,
                    state = StoryRevisionState.Complete
                )
                assistant = store.appendMessage(
                    storyId = story.id,
                    timelineId = story.currentTimelineId,
                    workspace = workspace,
                    role = "assistant",
                    content = "",
                    state = StoryRevisionState.Streaming,
                    profileName = profile.name,
                    model = routeModel
                )
                refreshWorkspaceIfVisible(story.id, workspace)

                val context = StoryContextComposer.compose(
                    workspace = workspace,
                    baseInstruction = workspaceSystemPrompt(workspace),
                    memoryRecords = archiveStore.listMemoryRecords(story.id, story.currentTimelineId),
                    proposals = archiveStore.listPendingProposals(story.id, story.currentTimelineId),
                    proseMessages = store.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose),
                    discussionMessages = store.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Discussion)
                )
                context.truncationNotice?.let { notice ->
                    withContext(Dispatchers.Main) {
                        if (activeStoryId == story.id) errors[workspace] = notice
                    }
                }

                val result = api.streamChat(
                    profile = profile,
                    model = routeModel,
                    systemPrompt = context.systemPrompt,
                    history = context.history,
                    cacheKey = "aster-story-${story.id}-${workspace.dbValue}"
                ) { delta ->
                    streamed.append(delta)
                    val now = SystemClock.elapsedRealtime()
                    if (lastPersistAt == 0L || now - lastPersistAt >= 110L) {
                        lastPersistAt = now
                        assistant?.revision?.id?.let { revisionId ->
                            store.updateActiveRevision(
                                revisionId = revisionId,
                                content = streamed.toString(),
                                state = StoryRevisionState.Streaming,
                                profileName = profile.name,
                                model = routeModel
                            )
                        }
                        refreshWorkspaceIfVisible(story.id, workspace)
                    }
                }
                val finalText = result.text.ifBlank { streamed.toString() }
                assistant?.revision?.id?.let { revisionId ->
                    store.updateActiveRevision(
                        revisionId = revisionId,
                        content = finalText,
                        state = StoryRevisionState.Complete,
                        profileName = profile.name,
                        model = routeModel
                    )
                }
            } catch (error: Throwable) {
                val partial = streamed.toString().trimEnd()
                val revisionId = assistant?.revision?.id
                when {
                    key in stopRequested -> {
                        when (storyStopCleanupFor(partial)) {
                            StoryStopCleanup.RemoveAssistant -> assistant?.message?.id?.let(store::deleteMessage)
                            StoryStopCleanup.KeepStoppedPartial -> revisionId?.let {
                                store.updateActiveRevision(
                                    revisionId = it,
                                    content = partial,
                                    state = StoryRevisionState.Stopped,
                                    profileName = profile.name,
                                    model = routeModel
                                )
                            }
                        }
                    }
                    error is CancellationException -> throw error
                    revisionId != null -> {
                        store.updateActiveRevision(
                            revisionId = revisionId,
                            content = partial.ifBlank { "生成失败：${friendlyStoryError(error)}" },
                            state = StoryRevisionState.Interrupted,
                            profileName = profile.name,
                            model = routeModel
                        )
                        withContext(Dispatchers.Main) {
                            if (activeStoryId == story.id) errors[workspace] = "回复中断，已保留当前内容。"
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    stopRequested.remove(key)
                    jobs.remove(key)
                    loadingKeys.remove(key)
                }
                refreshWorkspaceIfVisible(story.id, workspace)
                refreshStory(story.id)
            }
        }
        jobs[key] = job
        job.start()
    }

    fun stop(workspace: StoryWorkspace = activeWorkspace) {
        val storyId = activeStoryId ?: return
        val key = jobKey(storyId, workspace)
        val job = jobs[key] ?: return
        stopRequested += key
        job.cancel(CancellationException("User stopped story generation"))
    }

    private fun loadActiveStoryState(story: Story) {
        viewModelScope.launch(Dispatchers.IO) {
            val loadedMessages = StoryWorkspace.entries.associateWith { workspace ->
                store.loadMessages(story.id, story.currentTimelineId, workspace)
            }
            val loadedStates = StoryWorkspace.entries.associateWith { workspace ->
                store.loadWorkspaceState(story.id, workspace)
            }
            val records = archiveStore.listMemoryRecords(story.id, story.currentTimelineId)
            withContext(Dispatchers.Main) {
                if (activeStoryId != story.id) return@withContext
                workspaceMessages.putAll(loadedMessages)
                workspaceStates.putAll(loadedStates)
                archiveRecords.clear()
                archiveRecords.addAll(records)
            }
        }
    }

    private fun refreshWorkspaceIfVisible(storyId: String, workspace: StoryWorkspace) {
        if (activeStoryId != storyId) return
        viewModelScope.launch(Dispatchers.IO) {
            val story = store.getStory(storyId) ?: return@launch
            val rows = store.loadMessages(storyId, story.currentTimelineId, workspace)
            withContext(Dispatchers.Main) {
                if (activeStoryId == storyId) workspaceMessages[workspace] = rows
            }
        }
    }

    private suspend fun refreshArchive(storyId: String, timelineId: String) {
        val records = archiveStore.listMemoryRecords(storyId, timelineId)
        withContext(Dispatchers.Main) {
            if (activeStoryId == storyId) {
                archiveRecords.clear()
                archiveRecords.addAll(records)
            }
        }
    }

    private suspend fun refreshStory(storyId: String) {
        val updated = store.getStory(storyId) ?: return
        withContext(Dispatchers.Main) { replaceStory(updated) }
    }

    private fun replaceStory(story: Story) {
        val index = stories.indexOfFirst { it.id == story.id }
        if (index >= 0) stories[index] = story else stories.add(0, story)
        val sorted = stories.sortedByDescending { it.updatedAt }
        stories.clear()
        stories.addAll(sorted)
    }

    private fun jobKey(storyId: String, workspace: StoryWorkspace): String = "$storyId|${workspace.dbValue}"

    override fun onCleared() {
        jobs.values.forEach { it.cancel() }
        archiveStore.close()
        store.close()
        super.onCleared()
    }

    private fun workspaceSystemPrompt(workspace: StoryWorkspace): String = when (workspace) {
        StoryWorkspace.Discussion -> """
            你正在 Aster 的故事讨论工作区。与用户讨论设定、人物、文风和后续计划。
            讨论中的建议、假设、备选方案和示例片段都不是已经发生的正式剧情。
            不要因为自己提出了某个方案，就把它当作用户已经确认的事实。
            标记为“未确认候选”的内容仅供讨论，除非之后被用户确认并写入正式资料，否则不得提升为故事事实。
        """.trimIndent()
        StoryWorkspace.Prose -> """
            你正在 Aster 的故事正文工作区。根据用户给出的剧情方向、对白、人物行动或世界观约束继续创作正文。
            你只能把注入的“固定且已确认的故事资料”和“已确认的故事资料”视作正式资料；讨论候选不会提供给你。
            作者计划用于约束创作方向，不等于剧情已经发生。不要输出记忆 JSON、资料整理过程或管理说明，也不要把尚未发生的计划提前写成既成事实。
            尊重用户对角色控制权和推进节奏的要求，保持连续、自然的小说叙事。
        """.trimIndent()
    }

    private fun friendlyStoryError(error: Throwable): String =
        generateSequence(error) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.take(240)
            ?: "未知错误"
}
