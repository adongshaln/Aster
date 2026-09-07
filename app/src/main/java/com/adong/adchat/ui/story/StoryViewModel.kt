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
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.ConfigStore
import com.adong.adchat.data.story.Story
import com.adong.adchat.data.story.StoryConflictEntry
import com.adong.adchat.data.story.StoryChangeEntry
import com.adong.adchat.data.story.StoryArchiveStore
import com.adong.adchat.data.story.StoryContextComposer
import com.adong.adchat.data.story.StoryMemoryApplyResult
import com.adong.adchat.data.story.StoryMemoryKind
import com.adong.adchat.data.story.StoryMemoryOrganizer
import com.adong.adchat.data.story.StoryMemoryRecord
import com.adong.adchat.data.story.StoryProposal
import com.adong.adchat.data.story.StoryMemoryStore
import com.adong.adchat.data.story.StoryMessageRevision
import com.adong.adchat.data.story.StoryMessageWithRevision
import com.adong.adchat.data.story.StoryRepository
import com.adong.adchat.data.story.StoryRevisionState
import com.adong.adchat.data.story.StoryStopCleanup
import com.adong.adchat.data.story.StoryWorkspace
import com.adong.adchat.data.story.StoryWorkspaceState
import com.adong.adchat.data.story.StoryTimeline
import com.adong.adchat.data.story.nextStoryWorkspaceUpdatedAt
import com.adong.adchat.data.story.storyStopCleanupFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class StoryViewModel(application: Application) : AndroidViewModel(application) {
    private val store = StoryRepository(application)
    private val archiveStore = StoryArchiveStore(application)
    private val usageStore = com.adong.adchat.data.story.StoryUsageStore(application)
    var usageText by mutableStateOf("正在读取用量…")
        private set
    private val memoryStore = StoryMemoryStore(application)
    private val configStore = ConfigStore(application)
    private val api = ApiRepository()
    private val jobs = linkedMapOf<String, Job>()
    private val organizerJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var stateEpoch = 0L
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()

    val stories = mutableStateListOf<Story>()
    val archiveProposals = mutableStateListOf<StoryProposal>()
    var memoryStatus by mutableStateOf("暂无整理任务")
        private set
    val archiveRecords = mutableStateListOf<StoryMemoryRecord>()
    val archiveConflicts = mutableStateListOf<StoryConflictEntry>()
    val archiveChanges = mutableStateListOf<StoryChangeEntry>()
    var archiveChangeError by mutableStateOf<String?>(null)
        private set
    var undoBusy by mutableStateOf(false)
        private set
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
            store.recoverInterruptedGenerations()
            memoryStore.recoverRunningJobs()
            usageStore.recoverInterrupted()
            val loaded = store.listStories()
            withContext(Dispatchers.Main) {
                stories.clear()
                stories.addAll(loaded)
                loaded.firstOrNull()?.let { selectStory(it.id) }
            }
            loaded.filter(Story::automaticMemoryEnabled).forEach { story ->
                scheduleMemoryMaintenance(story.id, story.currentTimelineId)
            }
        }
    }

    val activeStory: Story?
        get() = activeStoryId?.let { id -> stories.firstOrNull { it.id == id } }

    fun messages(workspace: StoryWorkspace = activeWorkspace): List<StoryMessageWithRevision> =
        workspaceMessages[workspace].orEmpty()

    fun workspaceState(workspace: StoryWorkspace = activeWorkspace): StoryWorkspaceState =
        workspaceStates[workspace] ?: StoryWorkspaceState(activeStoryId.orEmpty(), workspace, timelineId = activeStory?.currentTimelineId)

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
                stateEpoch++
                activeStoryId = story.id
                activeWorkspace = StoryWorkspace.Discussion
                workspaceMessages.clear()
                workspaceStates.clear()
                archiveRecords.clear(); archiveConflicts.clear()
                archiveProposals.clear()
                errors.clear()
                loadActiveStoryState(story)
                onCreated(story)
            }
        }
    }

    fun selectStory(storyId: String) {
        if (activeStoryId == storyId && workspaceMessages.isNotEmpty()) return
        val story = stories.firstOrNull { it.id == storyId } ?: return
        stateEpoch++
        activeStoryId = storyId
        activeWorkspace = StoryWorkspace.Discussion
        workspaceMessages.clear()
        workspaceStates.clear()
        archiveRecords.clear(); archiveConflicts.clear()
                archiveProposals.clear()
        errors.clear()
        loadActiveStoryState(story)
    }

    fun switchWorkspace(workspace: StoryWorkspace) {
        activeWorkspace = workspace
        errors.remove(workspace)
    }

    fun openArchive() {
        archiveOpen = true
        archiveChanges.clear()
        usageText = "正在读取用量…"
        archiveChangeError = null
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) { refreshArchive(story.id, story.currentTimelineId) }
    }
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
            if (updated.automaticMemoryEnabled) scheduleMemoryMaintenance(updated.id, updated.currentTimelineId, profile)
        }
    }

    fun setAutomaticMemoryEnabled(enabled: Boolean) {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!store.setAutomaticMemoryEnabled(story.id, enabled)) return@launch
            val updated = store.getStory(story.id) ?: return@launch
            withContext(Dispatchers.Main) { replaceStory(updated) }
            if (enabled) {
                scheduleMemoryMaintenance(updated.id, updated.currentTimelineId)
            } else {
                organizerJobs.remove(memoryJobKey(updated.id, updated.currentTimelineId))?.cancel()
            }
        }
    }

    fun deleteStory(storyId: String) {
        jobs.keys.filter { it.startsWith("$storyId|") }.forEach { key ->
            stopRequested += key
            jobs.remove(key)?.cancel(CancellationException("Story deleted"))
        }
        organizerJobs.keys.filter { it.startsWith("$storyId|") }.forEach { key ->
            organizerJobs.remove(key)?.cancel(CancellationException("Story deleted"))
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
                    archiveRecords.clear(); archiveConflicts.clear()
                archiveProposals.clear()
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

    fun saveScroll(workspace: StoryWorkspace, firstVisibleIndex: Int, firstVisibleOffset: Int, expectedTimelineId: String? = activeStory?.currentTimelineId) {
        if (expectedTimelineId != activeStory?.currentTimelineId) return
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
            refreshStory(story.id)
        }
    }

    fun resolveConflict(entry: StoryConflictEntry, acceptNew: Boolean) {
        val story = activeStory ?: return
        if (undoBusy) return
        undoBusy = true
        archiveChangeError = null
        val epoch = stateEpoch
        viewModelScope.launch(Dispatchers.IO) {
            try {
                archiveStore.resolveStateConflict(story.id, story.currentTimelineId, entry.id, entry.memoryVersion, acceptNew)
                refreshStory(story.id)
                refreshArchive(story.id, story.currentTimelineId)
            } catch (error: Exception) {
                refreshArchive(story.id, story.currentTimelineId)
                withContext(Dispatchers.Main) {
                    if (epoch == stateEpoch) archiveChangeError = error.message ?: "处理冲突失败"
                }
            } finally { withContext(NonCancellable + Dispatchers.Main) { undoBusy = false } }
        }
    }

    fun undoArchiveChange(changeId: String, batch: Boolean) {
        val story = activeStory ?: return
        if (undoBusy) return
        undoBusy = true
        archiveChangeError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (batch) archiveStore.undoChangeSet(story.id, story.currentTimelineId, changeId)
                else archiveStore.undoManualChange(story.id, story.currentTimelineId, changeId)
                refreshArchive(story.id, story.currentTimelineId)
                refreshStory(story.id)
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    if (activeStoryId == story.id && activeStory?.currentTimelineId == story.currentTimelineId)
                        archiveChangeError = error.message ?: "撤销未保存，请重试"
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { undoBusy = false }
            }
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

    var revisionTarget by mutableStateOf<StoryMessageWithRevision?>(null)
        private set
    val revisionHistory = mutableStateListOf<StoryMessageRevision>()
    var revisionBusy by mutableStateOf(false)
        private set
    var revisionError by mutableStateOf<String?>(null)
        private set

    fun openRevisionEditor(row: StoryMessageWithRevision) {
        if (revisionBusy || StoryWorkspace.entries.any { isLoading(it) }) return
        revisionTarget = row
        revisionError = null
        revisionHistory.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val history = store.listRevisions(row.message.id)
            withContext(Dispatchers.Main) {
                if (revisionTarget?.revision?.id == row.revision.id) {
                    revisionHistory.clear()
                    revisionHistory.addAll(history)
                }
            }
        }
    }

    fun closeRevisionEditor() {
        if (!revisionBusy) { revisionTarget = null; revisionHistory.clear(); revisionError = null }
    }

    fun discussProseSelection(start: Int, end: Int) {
        val target = revisionTarget ?: return
        val story = activeStory ?: return
        val current = workspaceStates[StoryWorkspace.Discussion]
        if(revisionBusy || current == null) { revisionError = "讨论草稿尚在加载，请稍后重试。"; return }
        if(target.message.storyId != story.id || target.message.timelineId != story.currentTimelineId) {
            revisionError = "故事或路线已变化，请重新打开正文。"; return
        }
        val epoch = stateEpoch
        val expected = current.copy(timelineId=story.currentTimelineId)
        revisionBusy = true;revisionError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved = store.appendDiscussionQuote(target.message.id,target.revision.id,start,end,expected)
                withContext(Dispatchers.Main) {
                    if(epoch == stateEpoch && activeStoryId == story.id &&
                        workspaceStates[StoryWorkspace.Discussion]?.updatedAt == current.updatedAt) {
                        workspaceStates[StoryWorkspace.Discussion] = saved
                        activeWorkspace = StoryWorkspace.Discussion
                        revisionTarget = null;revisionHistory.clear()
                    } else if(activeStoryId == story.id) revisionError = "引用已保存到讨论草稿，请重新打开讨论查看。"
                }
            } catch(error: Exception) {
                withContext(Dispatchers.Main) { revisionError = error.message ?: "引用未保存，请重试。" }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { revisionBusy = false }
            }
        }
    }

    fun saveProseRevision(content: String, restoreRevisionId: String? = null, fork: Boolean = false) {
        val target = revisionTarget ?: return
        if (revisionBusy || StoryWorkspace.entries.any { isLoading(it) }) return
        revisionBusy = true
        stateEpoch++
        revisionError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (fork) {
                    store.forkProseRevision(target.message.id, target.revision.id, content)
                } else if (restoreRevisionId != null) {
                    check(store.restoreMessageRevision(target.message.id, restoreRevisionId, target.revision.id))
                } else {
                    check(store.replaceMessageRevision(target.message.id, content.trim(),
                        profileName = target.revision.profileName, model = target.revision.model,
                        expectedRevisionId = target.revision.id) != null)
                }
                val updated = store.getStory(target.message.storyId) ?: error("故事已删除")
                withContext(Dispatchers.Main) {
                    replaceStory(updated)
                    if (activeStoryId == updated.id) {
                        workspaceMessages.clear(); workspaceStates.clear(); archiveRecords.clear(); archiveConflicts.clear(); archiveProposals.clear()
                        loadActiveStoryState(updated)
                    }
                }
                scheduleMemoryMaintenance(updated.id, updated.currentTimelineId)
                withContext(Dispatchers.Main) { revisionTarget = null; revisionHistory.clear() }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { revisionError = error.message ?: "修订未保存，请重试" }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { revisionBusy = false }
            }
        }
    }

    val timelineHistory = mutableStateListOf<StoryTimeline>()
    var timelineHistoryOpen by mutableStateOf(false)
        private set

    fun openTimelineHistory() {
        val story = activeStory ?: return
        if (revisionBusy) return
        timelineHistoryOpen = true
        revisionError = null
        viewModelScope.launch(Dispatchers.IO) {
            val routes = store.listTimelines(story.id)
            withContext(Dispatchers.Main) {
                if (activeStoryId == story.id) { timelineHistory.clear(); timelineHistory.addAll(routes) }
            }
        }
    }
    fun closeTimelineHistory() { if (!revisionBusy) timelineHistoryOpen = false }

    fun restoreTimeline(timelineId: String) {
        val story = activeStory ?: return
        if (revisionBusy || StoryWorkspace.entries.any { isLoading(it) }) return
        revisionBusy = true
        stateEpoch++
        revisionError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.switchTimeline(story.id, timelineId, story.currentTimelineId)
                val updated = store.getStory(story.id) ?: error("故事已删除")
                withContext(Dispatchers.Main) {
                    replaceStory(updated)
                    if (activeStoryId == updated.id) {
                        workspaceMessages.clear(); workspaceStates.clear(); archiveRecords.clear(); archiveConflicts.clear(); archiveProposals.clear()
                        loadActiveStoryState(updated)
                    }
                    timelineHistoryOpen = false
                }
                scheduleMemoryMaintenance(updated.id, updated.currentTimelineId)
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { revisionError = error.message ?: "恢复失败" }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) { revisionBusy = false }
            }
        }
    }

    fun send(profile: ApiProfile, workspace: StoryWorkspace = activeWorkspace) {
        val story = activeStory ?: return
        val input = draft(workspace).trim()
        if (input.isBlank() || revisionBusy) return
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

                val memorySnapshot = archiveStore.contextMemorySnapshot(story.id, story.currentTimelineId)
                val context = StoryContextComposer.compose(
                    workspace = workspace,
                    baseInstruction = workspaceSystemPrompt(workspace),
                    memoryRecords = memorySnapshot.records,
                    proposals = memorySnapshot.proposals,
                    organizedProseRevisionIds = memorySnapshot.organizedProseRevisionIds,
                    summarySources = memorySnapshot.summarySources,
                    proseMessages = store.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Prose),
                    discussionMessages = store.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Discussion)
                )
                context.truncationNotice?.let { notice ->
                    withContext(Dispatchers.Main) {
                        if (activeStoryId == story.id) errors[workspace] = notice
                    }
                }

                val result = trackedChat(
                    storyId = story.id, timelineId = story.currentTimelineId, category = workspace.dbValue, sourceId = assistant?.revision?.id,
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
                    val completed = store.updateActiveRevision(
                        revisionId = revisionId,
                        content = finalText,
                        state = if (result.outputComplete) StoryRevisionState.Complete else StoryRevisionState.Interrupted,
                        profileName = profile.name,
                        model = routeModel
                    )
                    if (completed && result.outputComplete) {
                        memoryStore.enqueueForRevision(story.id, story.currentTimelineId, revisionId)
                        scheduleMemoryMaintenance(story.id, story.currentTimelineId, profile)
                    }
                }
                if (!result.outputComplete) withContext(Dispatchers.Main) {
                    if (activeStoryId == story.id) errors[workspace] = "回复未确认完整结束，已保留内容，不会写入正式记忆。"
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
                withContext(NonCancellable + Dispatchers.Main) {
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

    fun retryMemory() {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            memoryStore.retryFailed(story.id, story.currentTimelineId)
            scheduleMemoryMaintenance(story.id, story.currentTimelineId)
        }
    }

    fun decideProposal(proposalId: String, accept: Boolean) {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                archiveStore.decideProposal(story.id, story.currentTimelineId, proposalId, accept)
                refreshArchive(story.id, story.currentTimelineId)
                refreshStory(story.id)
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { memoryStatus = "候选操作未保存，请重新打开档案后重试" }
            }
        }
    }

    private fun organizerProfile(story: Story, preferred: ApiProfile? = null): ApiProfile? {
        if (story.model.isBlank()) return null
        return preferred?.takeIf { it.id == story.profileId }
            ?: configStore.load().profiles.firstOrNull { it.id == story.profileId }
    }

    @Synchronized
    private fun scheduleMemoryMaintenance(
        storyId: String,
        timelineId: String,
        preferredProfile: ApiProfile? = null
    ) {
        val key = memoryJobKey(storyId, timelineId)
        if (organizerJobs[key]?.isActive == true) return
        val task = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                memoryStore.enqueueMissingSources(storyId, timelineId)
                while (isActive) {
                    val story = store.getStory(storyId) ?: break
                    if (!story.automaticMemoryEnabled || story.currentTimelineId != timelineId) break
                    memoryStore.enqueueSummary(storyId, timelineId)
                    val pending = memoryStore.nextPendingJob(storyId, timelineId) ?: break
                    val resolvedProfile = organizerProfile(story, preferredProfile)
                    val running = memoryStore.markRunning(pending, configurationAvailable = resolvedProfile != null)
                    if (resolvedProfile == null) break // Wait for configuration; do not spend a request attempt.
                    if (running == null) continue
                    try {
                        withContext(Dispatchers.Main) { if (activeStoryId == storyId && activeStory?.currentTimelineId == timelineId) memoryStatus = "正在整理记忆" }
                        if (running.kind == com.adong.adchat.data.story.StorySummaries.KIND) {
                            val summaryInput = memoryStore.summaryRequest(running) ?: continue
                            withContext(Dispatchers.Main) { if(activeStoryId == storyId) memoryStatus = "正在生成剧情摘要" }
                            val response = trackedChat(
                                storyId = storyId, timelineId = timelineId, category = "summary", sourceId = running.id,
                                profile = resolvedProfile.copy(webSearchEnabled=false,fileCreationEnabled=false), model=story.model,
                                systemPrompt=com.adong.adchat.data.story.StorySummaries.prompt,
                                history=listOf(ChatMessage(role="user",content=summaryInput)), cacheKey="aster-summary-${running.id}"
                            ) { }
                            check(response.outputComplete) { "摘要未完整结束，未提交" }
                            if(memoryStore.applySummary(running,response.text)) {
                                refreshArchive(storyId,timelineId); refreshStory(storyId)
                            }
                            continue
                        }
                        val source = store.getActiveRevision(running.sourceRevisionId)
                        val currentVersion = memoryStore.currentMemoryVersion(storyId)
                        if (source == null || source.state != StoryRevisionState.Complete || source.content.isBlank()) {
                            memoryStore.markStale(running.id, "Source revision is no longer active complete prose")
                            continue
                        }
                        if (currentVersion == null) {
                            memoryStore.markStale(running.id, "Story no longer exists")
                            break
                        }
                        if (currentVersion != running.baseMemoryVersion) {
                            memoryStore.requeueStale(running, currentVersion)
                            continue
                        }

                        val organizerProfile = resolvedProfile.copy(
                            webSearchEnabled = false,
                            fileCreationEnabled = false
                        )
                        val userInput = store.loadMessages(storyId, timelineId, source.workspace)
                            .takeWhile { it.revision.id != source.id }
                            .lastOrNull { it.message.role == "user" && it.revision.state == StoryRevisionState.Complete }
                            ?.revision?.content.orEmpty()
                        val chunks = com.adong.adchat.data.story.StoryOrganizerChunks.plan(source.content, userInput)
                        val existingMemory = archiveStore.listMemoryRecords(storyId, timelineId)
                        val outputs = mutableListOf<com.adong.adchat.data.story.StoryOrganizerOutput>()
                        for (chunk in chunks) {
                            val currentStory = store.getStory(storyId)
                            if (currentStory?.automaticMemoryEnabled != true || currentStory.currentTimelineId != timelineId)
                                throw CancellationException("自动整理已暂停或路线已切换")
                            check(memoryStore.currentMemoryVersion(storyId) == running.baseMemoryVersion &&
                                store.getActiveRevision(source.id) != null) { "资料或正文已变化，需要重新整理" }
                            withContext(Dispatchers.Main) {
                                if (activeStoryId == storyId && activeStory?.currentTimelineId == timelineId)
                                    memoryStatus = "正在整理记忆 ${chunk.index + 1}/${chunks.size}"
                            }
                            val fingerprint = chunk.fingerprint(userInput)
                            val cached = memoryStore.loadOrganizerChunk(running, chunk.index, fingerprint)
                            val raw = cached ?: if (chunk.text.isBlank()) "{\"memories\":[],\"proposals\":[]}" else {
                                val organizerInput = StoryMemoryOrganizer.buildInput(source.copy(content = chunk.text), existingMemory,
                                    userInput, chunk.precedingContext)
                                val response = trackedChat(
                                    storyId = storyId, timelineId = timelineId, category = "organizer", sourceId = "${running.id}:${chunk.index}",
                                    profile = organizerProfile, model = story.model,
                                    systemPrompt = if (source.workspace == StoryWorkspace.Prose) StoryMemoryOrganizer.systemPrompt else StoryMemoryOrganizer.discussionPrompt,
                                    history = listOf(ChatMessage(role = "user", content = organizerInput)),
                                    cacheKey = "aster-story-memory-$storyId-${running.sourceRevisionId}-${running.baseMemoryVersion}-${chunk.index}"
                                ) { }
                                check(response.outputComplete) { "整理回复未完整结束，未提交资料" }
                                response.text
                            }
                            val parsed = StoryMemoryOrganizer.parse(raw, source.workspace)
                            if (cached == null) check(memoryStore.saveOrganizerChunk(running, chunk, fingerprint, raw)) { "资料版本已变化，分段结果未提交" }
                            outputs += parsed
                        }
                        val output = com.adong.adchat.data.story.StoryOrganizerChunks.combine(chunks, outputs)
                        when (memoryStore.applyOrganizerOutput(running, output)) {
                            is StoryMemoryApplyResult.Committed -> {
                                refreshArchive(storyId, timelineId)
                                refreshStory(storyId)
                            }
                            is StoryMemoryApplyResult.Requeued -> Unit
                            StoryMemoryApplyResult.StaleSource -> Unit
                        }
                    } catch (cancelled: CancellationException) {
                        memoryStore.resetPending(running.id, "Organizer cancelled")
                        throw cancelled
                    } catch (error: Throwable) {
                        if (running.attempts < 2) {
                            memoryStore.resetPending(running.id, friendlyStoryError(error))
                        } else {
                            memoryStore.markFailed(running.id, friendlyStoryError(error))
                        }
                    }
                }
            } finally {
                synchronized(this@StoryViewModel) { organizerJobs.remove(key) }
                val latestStory = store.getStory(storyId)
                val waitingForConfiguration = latestStory != null && organizerProfile(latestStory, preferredProfile) == null
                if (isActive && !waitingForConfiguration && latestStory?.automaticMemoryEnabled == true && latestStory.currentTimelineId == timelineId &&
                    memoryStore.nextPendingJob(storyId, timelineId) != null) {
                    scheduleMemoryMaintenance(storyId, timelineId)
                }
                withContext(NonCancellable) {
                    val status = if (waitingForConfiguration && memoryStore.nextPendingJob(storyId, timelineId) != null)
                        "记忆整理已暂停，请选择可用的 API 和模型后重试"
                    else memoryStore.jobStatus(storyId, timelineId)
                    withContext(Dispatchers.Main) { if (activeStoryId == storyId && activeStory?.currentTimelineId == timelineId) memoryStatus = status }
                }
            }
        }
        organizerJobs[key] = task
        task.start()
    }

    private fun loadActiveStoryState(story: Story) {
        val epoch = stateEpoch
        viewModelScope.launch(Dispatchers.IO) {
            val loadedMessages = StoryWorkspace.entries.associateWith { workspace ->
                store.loadMessages(story.id, story.currentTimelineId, workspace)
            }
            val loadedStates = StoryWorkspace.entries.associateWith { workspace ->
                store.loadWorkspaceState(story.id, workspace)
            }
            val records = archiveStore.listMemoryRecords(story.id, story.currentTimelineId)
            val proposals = archiveStore.listPendingProposals(story.id, story.currentTimelineId)
            val conflicts = archiveStore.listStateConflicts(story.id, story.currentTimelineId)
            val status = memoryStore.jobStatus(story.id, story.currentTimelineId)
            withContext(Dispatchers.Main) {
                if (epoch != stateEpoch || activeStoryId != story.id || activeStory?.currentTimelineId != story.currentTimelineId) return@withContext
                workspaceMessages.putAll(loadedMessages)
                workspaceStates.putAll(loadedStates)
                archiveRecords.clear(); archiveConflicts.clear()
                archiveProposals.clear()
                archiveRecords.addAll(records)
                archiveConflicts.addAll(conflicts)
                archiveProposals.addAll(proposals)
                memoryStatus = status
            }
        }
    }

    private fun refreshWorkspaceIfVisible(storyId: String, workspace: StoryWorkspace) {
        val epoch = stateEpoch
        if (activeStoryId != storyId) return
        viewModelScope.launch(Dispatchers.IO) {
            val story = store.getStory(storyId) ?: return@launch
            val rows = store.loadMessages(storyId, story.currentTimelineId, workspace)
            withContext(Dispatchers.Main) {
                if (epoch == stateEpoch && activeStoryId == storyId && activeStory?.currentTimelineId == story.currentTimelineId) workspaceMessages[workspace] = rows
            }
        }
    }

    private suspend fun refreshArchive(storyId: String, timelineId: String) {
        val epoch = stateEpoch
        val records = archiveStore.listMemoryRecords(storyId, timelineId)
        val proposals = archiveStore.listPendingProposals(storyId, timelineId)
        val conflicts = archiveStore.listStateConflicts(storyId, timelineId)
        val changes = archiveStore.listChanges(storyId, timelineId)
        val usage = com.adong.adchat.data.story.renderStoryUsage(usageStore.totals(storyId))
        withContext(Dispatchers.Main) {
            if (epoch == stateEpoch && activeStoryId == storyId && activeStory?.currentTimelineId == timelineId) {
                archiveRecords.clear(); archiveConflicts.clear()
                archiveProposals.clear()
                archiveRecords.addAll(records)
                archiveConflicts.addAll(conflicts)
                archiveProposals.addAll(proposals)
                archiveChanges.clear()
                archiveChanges.addAll(changes)
                usageText = usage
            }
        }
    }

    private suspend fun refreshStory(storyId: String) {
        val epoch = stateEpoch
        val updated = store.getStory(storyId) ?: return
        withContext(Dispatchers.Main) { if (epoch == stateEpoch) replaceStory(updated) }
    }

    private fun replaceStory(story: Story) {
        val index = stories.indexOfFirst { it.id == story.id }
        if (index >= 0) stories[index] = story else stories.add(0, story)
        val sorted = stories.sortedByDescending { it.updatedAt }
        stories.clear()
        stories.addAll(sorted)
    }

    private suspend fun trackedChat(
        storyId: String, timelineId: String, category: String, sourceId: String?,
        profile: ApiProfile, model: String, systemPrompt: String, history: List<ChatMessage>, cacheKey: String,
        onDelta: suspend (String) -> Unit
    ): com.adong.adchat.data.ChatCompletionResult {
        val id = usageStore.begin(storyId,timelineId,category,profile.id,model,sourceId)
        var result: com.adong.adchat.data.ChatCompletionResult? = null
        var state = "failed"
        try {
            val response = api.streamChat(profile,model,systemPrompt,history,cacheKey,onDelta=onDelta)
            result = response
            state = if(response.outputComplete) "completed" else "incomplete"
            return response
        } catch(cancelled: CancellationException) {
            state = "cancelled"
            throw cancelled
        } finally {
            // Usage is operational data: persist even when a request is stopped or memory commit later fails.
            withContext(NonCancellable + Dispatchers.IO) {
                val saved = runCatching { usageStore.finish(id,state,result) }
                val text = if(saved.isSuccess) runCatching {
                    com.adong.adchat.data.story.renderStoryUsage(usageStore.totals(storyId))
                }.getOrDefault("用量暂时无法读取，请重新打开档案。") else "本次用量未能完整保存，请重新打开档案检查；回复已保留。"
                withContext(Dispatchers.Main) { if(activeStoryId == storyId) usageText = text }
            }
        }
    }

    private fun jobKey(storyId: String, workspace: StoryWorkspace): String = "$storyId|${workspace.dbValue}"
    private fun memoryJobKey(storyId: String, timelineId: String): String = "$storyId|$timelineId"

    override fun onCleared() {
        jobs.values.forEach { it.cancel() }
        organizerJobs.values.forEach { it.cancel() }
        val pending = jobs.values.toList() + organizerJobs.values.toList()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            pending.forEach { it.join() }
            usageStore.close()
            memoryStore.close()
            archiveStore.close()
            store.close()
        }
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
