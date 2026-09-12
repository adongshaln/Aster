package com.adong.adchat.ui.story

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
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
import com.adong.adchat.data.SkillRuntime
import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPresetConfiguration
import com.adong.adchat.data.TavernPresetRuntime
import com.adong.adchat.data.TavernPresetStore
import com.adong.adchat.data.TavernPresetSummary
import com.adong.adchat.data.TavernRegexOutput
import com.adong.adchat.data.summary
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
    private val api = ApiRepository(skillLoader = SkillRuntime.persistent(application))
    private val tavernPresetStore = TavernPresetStore(application)
    private val jobs = linkedMapOf<String, Job>()
    private val organizerJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var stateEpoch = 0L
    private val stopRequested = ConcurrentHashMap.newKeySet<String>()

    var attachmentBusy by mutableStateOf(false)
        private set
    val stories = mutableStateListOf<Story>()
    val archiveProposals = mutableStateListOf<StoryProposal>()
    var memoryStatus by mutableStateOf("暂无整理任务")
        private set
    val archiveRecords = mutableStateListOf<StoryMemoryRecord>()
    val archiveReapplicableRecords = mutableStateListOf<StoryMemoryRecord>()
    val archiveReviewRecords = mutableStateListOf<StoryMemoryRecord>()
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
    val tavernPresets = mutableStateListOf<TavernPresetSummary>()
    var activeTavernPresetId by mutableStateOf<String?>(null)
        private set
    var tavernRegexEnabled by mutableStateOf(true)
        private set
    var tavernPresetBusy by mutableStateOf(false)
        private set
    var tavernPresetError by mutableStateOf<String?>(null)
        private set
    var activeTavernPresetConfiguration by mutableStateOf<TavernPresetConfiguration?>(null)
        private set
    @Volatile private var activeTavernPreset: TavernPreset? = null

    var activeStoryId by mutableStateOf<String?>(null)
        private set
    var activeWorkspace by mutableStateOf(StoryWorkspace.Discussion)
        private set
    var archiveOpen by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            refreshTavernPresetState()
            store.recoverInterruptedGenerations()
            store.recoverRewrites()
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

    val activeTavernPresetName: String
        get() = tavernPresets.firstOrNull { it.id == activeTavernPresetId }?.name ?: "未使用预设"

    fun selectTavernPreset(id: String?) {
        if (tavernPresetBusy || StoryWorkspace.entries.any(::isLoading)) return
        tavernPresetBusy = true
        tavernPresetError = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                tavernPresetStore.select(id)
                refreshTavernPresetState()
            }.onFailure { error ->
                withContext(Dispatchers.Main) { tavernPresetError = error.message ?: "切换酒馆预设失败" }
            }
            withContext(Dispatchers.Main) { tavernPresetBusy = false }
        }
    }

    fun setTavernRegexEnabled(enabled: Boolean) {
        tavernRegexEnabled = enabled
        tavernPresetStore.setRegexEnabled(enabled)
    }

    fun setTavernPromptEnabled(identifier: String, enabled: Boolean) {
        mutateTavernConfiguration("更新提示词模块失败") { presetId ->
            tavernPresetStore.setPromptEnabled(presetId, identifier, enabled)
        }
    }

    fun setTavernRegexScriptEnabled(index: Int, enabled: Boolean) {
        mutateTavernConfiguration("更新正则脚本失败") { presetId ->
            tavernPresetStore.setRegexScriptEnabled(presetId, index, enabled)
        }
    }

    fun resetTavernPresetConfiguration() {
        mutateTavernConfiguration("恢复预设默认失败") { presetId ->
            tavernPresetStore.resetConfiguration(presetId)
        }
    }

    private fun mutateTavernConfiguration(errorMessage: String, mutation: (String) -> Unit) {
        val presetId = activeTavernPresetId ?: return
        if (tavernPresetBusy || StoryWorkspace.entries.any { isLoading(it) }) return
        tavernPresetBusy = true
        tavernPresetError = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                mutation(presetId)
                refreshTavernPresetState()
            }.onFailure { error ->
                withContext(Dispatchers.Main) { tavernPresetError = error.message ?: errorMessage }
            }
            withContext(Dispatchers.Main) { tavernPresetBusy = false }
        }
    }

    fun importTavernPreset(uri: Uri) {
        if (tavernPresetBusy || StoryWorkspace.entries.any(::isLoading)) return
        tavernPresetBusy = true
        tavernPresetError = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty().ifBlank { "导入的酒馆预设.json" }
                val input = requireNotNull(resolver.openInputStream(uri)) { "无法读取所选文件" }
                val imported = tavernPresetStore.importPreset(input, displayName)
                tavernPresetStore.select(imported.id)
                refreshTavernPresetState()
            }.onFailure { error ->
                withContext(Dispatchers.Main) { tavernPresetError = error.message ?: "导入酒馆预设失败" }
            }
            withContext(Dispatchers.Main) { tavernPresetBusy = false }
        }
    }

    fun deleteTavernPreset(id: String) {
        if (tavernPresetBusy || StoryWorkspace.entries.any(::isLoading)) return
        tavernPresetBusy = true
        tavernPresetError = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                check(tavernPresetStore.delete(id)) { "预设文件已不存在" }
                refreshTavernPresetState()
            }.onFailure { error ->
                withContext(Dispatchers.Main) { tavernPresetError = error.message ?: "删除酒馆预设失败" }
            }
            withContext(Dispatchers.Main) { tavernPresetBusy = false }
        }
    }

    fun tavernDisplay(
        content: String,
        role: String,
        depth: Int,
        workspace: StoryWorkspace
    ): TavernRegexOutput {
        val preset = activeTavernPreset
        return if (workspace != StoryWorkspace.Prose || preset == null) {
            TavernRegexOutput(content, 0, emptyList())
        } else TavernPresetRuntime.display(preset, content, role, depth, tavernRegexEnabled)
    }

    private suspend fun refreshTavernPresetState() {
        val available = tavernPresetStore.list()
        var selected = runCatching(tavernPresetStore::active).getOrNull()
        if (selected == null && tavernPresetStore.activeId() != null) {
            tavernPresetStore.select(TavernPresetStore.BUILT_IN_ID)
            selected = runCatching(tavernPresetStore::active).getOrNull()
        }
        val regex = tavernPresetStore.regexEnabled()
        val configuration = selected?.id?.let(tavernPresetStore::configuration)
        activeTavernPreset = selected
        withContext(Dispatchers.Main) {
            tavernPresets.clear()
            tavernPresets.addAll(available.map { it.summary() })
            activeTavernPresetId = selected?.id
            activeTavernPresetConfiguration = configuration
            tavernRegexEnabled = regex
        }
    }

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

    var archiveInitialSection by mutableStateOf(0)
        private set
    fun openPendingCandidates() { openArchive();archiveInitialSection=3 }
    fun openArchive() {
        archiveInitialSection=0
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
            com.adong.adchat.data.story.StoryImages.directory(getApplication(),storyId).deleteRecursively()
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

    fun importAttachments(uris: List<android.net.Uri>, images: Boolean, storyId: String, timelineId: String, workspace: StoryWorkspace) {
        if(attachmentBusy || revisionBusy || isLoading(workspace)) return
        if(activeStoryId!=storyId || activeStory?.currentTimelineId!=timelineId) return
        val epoch=stateEpoch
        attachmentBusy=true
        viewModelScope.launch {
            try {
                if(images) {
                    val available=4-workspaceState(workspace).attachments.size
                    require(uris.size<=available) { "每条消息最多添加 4 张图片" }
                    val loaded=withContext(Dispatchers.IO) { uris.map { com.adong.adchat.data.story.StoryImages.importImage(getApplication(),storyId,it) } }
                    check(epoch==stateEpoch && activeStoryId==storyId && activeStory?.currentTimelineId==timelineId) { "故事路线已切换，请重新选择图片" }
                    updateDraft(draft(workspace),workspace,workspaceState(workspace).attachments+loaded)
                } else {
                    val blocks=withContext(Dispatchers.IO) { uris.map { com.adong.adchat.data.DocumentImport.read(getApplication(),it) } }
                    check(epoch==stateEpoch && activeStoryId==storyId && activeStory?.currentTimelineId==timelineId) { "故事路线已切换，请重新选择文件" }
                    val text=blocks.fold(draft(workspace),com.adong.adchat.data.DocumentImport::append)
                    updateDraft(text,workspace)
                }
            } catch(e: Exception) {
                if(epoch==stateEpoch && activeStoryId==storyId) errors[workspace]=e.message ?: "附件读取失败"
            } finally { attachmentBusy=false }
        }
    }
    fun removeDraftImage(id: String, workspace: StoryWorkspace) {
        if(attachmentBusy)return
        updateDraft(draft(workspace),workspace,workspaceState(workspace).attachments.filterNot { it.id==id })
    }

    fun updateDraft(value: String, workspace: StoryWorkspace = activeWorkspace, attachments: List<com.adong.adchat.data.ChatImageAttachment>? = null) {
        val storyId = activeStoryId ?: return
        val current = workspaceState(workspace)
        val next = current.copy(
            storyId = storyId,
            draft = value,
            attachments = attachments ?: current.attachments,
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

    var rewriteOpen by mutableStateOf(false)
        private set
    var rewriteCandidate by mutableStateOf<com.adong.adchat.data.story.StoryRewriteCandidate?>(null)
        private set
    var rewriteInstruction by mutableStateOf("")
        private set
    var rewriteOriginalInput by mutableStateOf("")
        private set
    private var rewriteOriginalBaseline = ""
    fun updateRewriteOriginalInput(value: String) { if(!revisionBusy) rewriteOriginalInput=value }
    private var rewriteJob: Job? = null
    fun updateRewriteInstruction(value: String) { if(!revisionBusy) rewriteInstruction=value }
    fun canModelRewrite(target: StoryMessageWithRevision): Boolean = target.revision.state == StoryRevisionState.Complete
    fun isHistoricalRewrite(): Boolean = revisionTarget?.revision?.id != messages(StoryWorkspace.Prose).lastOrNull()?.revision?.id

    fun openModelRewrite() {
        val target=revisionTarget ?: return
        if(revisionBusy || !canModelRewrite(target)) return
        rewriteOpen=true;revisionBusy=true;revisionError=null;rewriteCandidate=null;rewriteInstruction=""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val saved=store.latestRewrite(target.message.id)
                val original=store.loadMessages(target.message.storyId,target.message.timelineId,StoryWorkspace.Prose)
                    .lastOrNull { it.message.sequence<target.message.sequence && it.message.role=="user" }?.revision?.content.orEmpty()
                withContext(Dispatchers.Main) {
                    if(revisionTarget?.revision?.id==target.revision.id) {
                        rewriteCandidate=saved;rewriteInstruction=saved?.instruction.orEmpty()
                        rewriteOriginalBaseline=original;rewriteOriginalInput=saved?.replacementInput ?: original
                    }
                }
            } finally { withContext(NonCancellable+Dispatchers.Main) { revisionBusy=false } }
        }
    }
    fun closeModelRewrite() { if(!revisionBusy) rewriteOpen=false }
    fun stopModelRewrite() { rewriteJob?.cancel() }

    fun generateModelRewrite() {
        val target=revisionTarget ?: return
        val story=activeStory ?: return
        if(revisionBusy || StoryWorkspace.entries.any { isLoading(it) } || target.message.storyId!=story.id ||
            target.message.timelineId!=story.currentTimelineId) return
        val instruction=rewriteInstruction.trim()
        val replacementInput=rewriteOriginalInput.takeIf { it!=rewriteOriginalBaseline }
        if(instruction.isBlank()) { revisionError="请先填写明确的修改要求。";return }
        revisionBusy=true;revisionError=null
        val task=viewModelScope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            var candidate: com.adong.adchat.data.story.StoryRewriteCandidate?=null
            val output=StringBuilder();var lastPersist=0L
            try {
                val fresh=store.getStory(story.id) ?: error("故事已删除")
                val profile=organizerProfile(fresh) ?: error("请先配置故事使用的服务。")
                val prose=store.loadMessages(story.id,story.currentTimelineId,StoryWorkspace.Prose)
                val historical=replacementInput!=null || prose.lastOrNull()?.revision?.id!=target.revision.id
                val context=if(historical) store.historicalRewriteContext(target.message.id,target.revision.id,instruction,replacementInput,
                        com.adong.adchat.data.story.StoryContextBudget.forModel(profile, fresh.model))
                    else com.adong.adchat.data.story.StoryRewriteContext.compose(target,instruction,
                        archiveStore.contextMemorySnapshot(story.id,story.currentTimelineId),prose,
                        budget = com.adong.adchat.data.story.StoryContextBudget.forModel(profile, fresh.model))
                val created=store.beginRewrite(target.message.id,target.revision.id,fresh.memoryVersion,instruction,profile.name,fresh.model,historical,replacementInput)
                candidate=created
                withContext(Dispatchers.Main) { rewriteCandidate=created }
                val result=trackedChat(story.id,story.currentTimelineId,"prose",created.id,
                    profile.copy(webSearchEnabled=false,fileCreationEnabled=false),fresh.model,
                    context.systemPrompt,context.history,"aster-rewrite-${created.id}") { delta ->
                    check(output.length+delta.length<=100_000) { "候选超过 100,000 字符，已停止；保留已收到的内容。" }
                    output.append(delta)
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastPersist>=150L) {
                        lastPersist=now
                        check(store.updateRewrite(created.id,output.toString(),"generating"))
                        withContext(Dispatchers.Main) { rewriteCandidate=created.copy(content=output.toString()) }
                    }
                }
                val text=result.text.ifBlank { output.toString() }
                val state=if(result.outputComplete && text.isNotBlank()) "ready" else "incomplete"
                check(store.updateRewrite(created.id,text,state))
                withContext(Dispatchers.Main) { rewriteCandidate=created.copy(content=text,state=state) }
            } catch(error: Throwable) {
                withContext(NonCancellable+Dispatchers.IO) {
                    candidate?.let { saved ->
                        val state=if(error is CancellationException) "stopped" else "failed"
                        store.updateRewrite(saved.id,output.toString(),state)
                        withContext(Dispatchers.Main) { rewriteCandidate=saved.copy(content=output.toString(),state=state) }
                    }
                    withContext(Dispatchers.Main) { revisionError=if(error is CancellationException) "已停止，部分候选保留，不能直接采用。" else friendlyStoryError(error) }
                }
            } finally { withContext(NonCancellable+Dispatchers.Main) { revisionBusy=false;rewriteJob=null } }
        }
        rewriteJob=task;task.start()
    }

    fun adoptModelRewrite() {
        val candidate=rewriteCandidate ?: return
        val target=revisionTarget ?: return
        if(revisionBusy || activeStoryId!=candidate.storyId || target.message.id!=candidate.messageId) return
        revisionBusy=true;revisionError=null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result=store.adoptRewrite(candidate.id)
                memoryStore.enqueueForRevision(result.message.storyId,result.message.timelineId,result.revision.id)
                val updated=store.getStory(result.message.storyId) ?: error("故事已删除")
                withContext(Dispatchers.Main) {
                    if(activeStoryId==updated.id) {
                        stateEpoch++;replaceStory(updated);loadActiveStoryState(updated)
                        rewriteOpen=false;revisionTarget=null;rewriteCandidate=null;revisionHistory.clear()
                    }
                }
                scheduleMemoryMaintenance(updated.id,updated.currentTimelineId)
            } catch(error: Exception) {
                withContext(Dispatchers.Main) { revisionError=error.message ?: "候选未采用，请重试。" }
            } finally { withContext(NonCancellable+Dispatchers.Main) { revisionBusy=false } }
        }
    }

    var discussionActionTarget by mutableStateOf<StoryMessageWithRevision?>(null)
        private set
    var discussionActionText by mutableStateOf("")
        private set
    var discussionActionError by mutableStateOf<String?>(null)
        private set
    val discussionRewriteTargets=mutableStateListOf<StoryMessageWithRevision>()
    fun openDiscussionAction(row:StoryMessageWithRevision) {
        if(revisionBusy) return
        discussionActionTarget=row;discussionActionText=row.revision.content;discussionActionError=null
        discussionRewriteTargets.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val linked=store.proseForDiscussion(row.revision.id)
            val choices=linked.ifEmpty { store.loadMessages(row.message.storyId,row.message.timelineId,StoryWorkspace.Prose)
                .filter { it.message.role=="assistant" && it.revision.state==StoryRevisionState.Complete }.takeLast(12) }
            withContext(Dispatchers.Main) { if(discussionActionTarget?.revision?.id==row.revision.id) discussionRewriteTargets.addAll(choices) }
        }
    }
    fun updateDiscussionActionText(text:String) { discussionActionText=text }
    fun closeDiscussionAction() { if(!revisionBusy) discussionActionTarget=null }
    fun applyDiscussionAction(kind:String,target:StoryMessageWithRevision?=null) {
        val source=discussionActionTarget ?: return
        val story=activeStory ?: return
        if(revisionBusy || source.message.storyId!=story.id || source.message.timelineId!=story.currentTimelineId) return
        val text=discussionActionText.trim()
        if(text.isBlank() || text.length>8000) { discussionActionError="请整理为 1–8,000 字符的明确修改意见。";return }
        if(kind=="rewrite" && target!=null) {
            if(StoryWorkspace.entries.any { isLoading(it) }) { discussionActionError="请先等待生成结束。";return }
            discussionActionTarget=null;openRevisionEditor(target);rewriteOpen=true;rewriteInstruction=text
            rewriteCandidate=null;rewriteOriginalInput="";rewriteOriginalBaseline=""
            revisionBusy=true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val original=store.loadMessages(story.id,story.currentTimelineId,StoryWorkspace.Prose)
                        .lastOrNull { it.message.sequence<target.message.sequence && it.message.role=="user" }?.revision?.content.orEmpty()
                    withContext(Dispatchers.Main) { rewriteOriginalBaseline=original;rewriteOriginalInput=original }
                } finally { withContext(NonCancellable+Dispatchers.Main) { revisionBusy=false } }
            }
            return
        }
        if(kind=="future") {
            val existing=draft(StoryWorkspace.Prose)
            val next=existing+(if(existing.isBlank()) "" else "\n\n")+"[本轮明确采用的创作方向，尚未发生]\n"+text
            if(next.length>40000) { discussionActionError="正文草稿过长，请先处理已有草稿。";return }
            updateDraft(next,StoryWorkspace.Prose);discussionActionTarget=null;switchWorkspace(StoryWorkspace.Prose);return
        }
        revisionBusy=true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                archiveStore.addDiscussionRecord(story.id,story.currentTimelineId,source.revision.id,text,
                    if(kind=="plan") StoryMemoryKind.AuthorPlan else StoryMemoryKind.WorldFact)
                refreshArchive(story.id,story.currentTimelineId);refreshStory(story.id)
                withContext(Dispatchers.Main) { discussionActionTarget=null }
            } catch(error:Exception) { withContext(Dispatchers.Main) { discussionActionError=error.message } }
            finally { withContext(NonCancellable+Dispatchers.Main) { revisionBusy=false } }
        }
    }

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
        if (!revisionBusy) { rewriteOpen=false;revisionTarget = null; revisionHistory.clear(); revisionError = null }
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
        launchGeneration(profile, workspace, retryTarget = null)
    }

    fun regenerateInterrupted(profile: ApiProfile, target: StoryMessageWithRevision) {
        launchGeneration(profile, target.message.workspace, retryTarget = target)
    }

    private fun launchGeneration(
        profile: ApiProfile,
        workspace: StoryWorkspace,
        retryTarget: StoryMessageWithRevision?
    ) {
        val story = activeStory ?: return
        val retrying = retryTarget != null
        if (retryTarget != null && (
                retryTarget.message.storyId != story.id ||
                    retryTarget.message.timelineId != story.currentTimelineId ||
                    retryTarget.message.workspace != workspace ||
                    retryTarget.message.role != "assistant" ||
                    retryTarget.revision.state != StoryRevisionState.Interrupted
                )) return
        val attachments = if (retrying) emptyList() else workspaceState(workspace).attachments.toList()
        val input = if (retrying) "" else draft(workspace).trim()
            .ifBlank { if (attachments.isNotEmpty()) "请参考所附图片，按当前工作区处理。" else "" }
        if ((!retrying && input.isBlank()) || revisionBusy || attachmentBusy) return
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

        if (!retrying) updateDraft("", workspace, emptyList())
        errors.remove(workspace)
        loadingKeys[key] = true
        stopRequested.remove(key)

        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var assistant: StoryMessageWithRevision? = null
            val streamed = StringBuilder()
            var lastPersistAt = 0L
            try {
                val userMessage = if (!retrying) store.appendMessage(
                    storyId = story.id,
                    timelineId = story.currentTimelineId,
                    workspace = workspace,
                    role = "user",
                    content = input,
                    attachments = attachments,
                    state = StoryRevisionState.Complete
                ) else null
                val pendingDecisions = if (!retrying && workspace == StoryWorkspace.Discussion) {
                    archiveStore.listPendingProposals(story.id, story.currentTimelineId)
                } else emptyList()
                if(!retrying && workspace==StoryWorkspace.Discussion && (com.adong.adchat.data.story.StoryExplicitDecision.isExplicit(input) ||
                    com.adong.adchat.data.story.StoryExplicitDecision.needsClarification(input,pendingDecisions))) {
                    val proposal=com.adong.adchat.data.story.StoryExplicitDecision.match(input,pendingDecisions)
                    val accepted=proposal!=null && archiveStore.decideProposal(story.id,story.currentTimelineId,proposal.id,true,
                        decisionRevisionId=requireNotNull(userMessage).revision.id)
                    store.appendMessage(story.id,story.currentTimelineId,workspace,"assistant",
                        if(accepted) "已采用这一项候选；可在档案变更中撤销。" else "没有找到唯一对应的待定候选。请逐字引用候选内容，或到档案选择具体条目。")
                    refreshWorkspaceIfVisible(story.id,workspace);refreshArchive(story.id,story.currentTimelineId);refreshStory(story.id)
                    return@launch
                }
                assistant = retryTarget?.let { target ->
                    store.restartInterruptedRevision(
                        messageId = target.message.id,
                        expectedRevisionId = target.revision.id,
                        profileName = profile.name,
                        model = routeModel
                    )
                } ?: store.appendMessage(
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
                    discussionMessages = store.loadMessages(story.id, story.currentTimelineId, StoryWorkspace.Discussion),
                    budget = com.adong.adchat.data.story.StoryContextBudget.forModel(profile, routeModel)
                )
                context.truncationNotice?.let { notice ->
                    withContext(Dispatchers.Main) {
                        if (activeStoryId == story.id) errors[workspace] = notice
                    }
                }

                val preset = activeTavernPreset.takeIf { workspace == StoryWorkspace.Prose }
                val prepared = preset?.let {
                    TavernPresetRuntime.prepare(
                        preset = it,
                        baseSystemPrompt = context.systemPrompt,
                        history = context.history,
                        regexEnabled = tavernRegexEnabled
                    )
                }

                val result = trackedChat(
                    storyId = story.id, timelineId = story.currentTimelineId, category = workspace.dbValue, sourceId = assistant?.revision?.id,
                    profile = profile,
                    model = routeModel,
                    systemPrompt = prepared?.systemPrompt ?: context.systemPrompt,
                    history = prepared?.history ?: context.history,
                    cacheKey = "aster-story-${story.id}-${workspace.dbValue}",
                    generationOptions = prepared?.generationOptions ?: com.adong.adchat.data.ChatGenerationOptions()
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
                    else -> withContext(Dispatchers.Main) {
                        if (activeStoryId == story.id) errors[workspace] = friendlyStoryError(error)
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

    fun decideProposal(proposalId: String, accept: Boolean, editedContent: String? = null) {
        val story = activeStory ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                archiveStore.decideProposal(story.id, story.currentTimelineId, proposalId, accept,editedContent)
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
                            val summaryParts = memoryStore.summaryRequestParts(running) ?: continue
                            withContext(Dispatchers.Main) { if(activeStoryId == storyId) memoryStatus = "正在生成剧情摘要" }
                            val summaryRaw = com.adong.adchat.data.story.StorySummaryPipeline.run(summaryParts,
                                load = { node, hash -> memoryStore.summaryCheckpoint(running, node, hash) },
                                save = { node, hash, raw -> check(memoryStore.summaryCheckpoint(running, node, hash, raw) != null) { "摘要来源或记忆版本已变化" } },
                                generate = { input ->
                                    check(memoryStore.summaryRequestParts(running) != null) { "摘要任务已失效" }
                                    val response = trackedChat(
                                        storyId = storyId, timelineId = timelineId, category = "summary", sourceId = running.id,
                                        profile = resolvedProfile.copy(webSearchEnabled=false,fileCreationEnabled=false), model=story.model,
                                        systemPrompt=com.adong.adchat.data.story.StorySummaries.prompt,
                                        history=listOf(ChatMessage(role="user",content=input)), cacheKey="aster-summary-${running.id}"
                                    ) { }
                                    check(response.outputComplete) { "摘要未完整结束，未提交" }
                                    response.text
                                })
                            if(memoryStore.applySummary(running,summaryRaw)) {
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

    fun reapplyArchiveSetting(id: String, content: String, review: Boolean) {
        val story = activeStory ?: return
        if (undoBusy) return
        undoBusy = true; archiveChangeError = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (review) archiveStore.reconfirmReviewedRecord(story.id,story.currentTimelineId,id,content)
                else archiveStore.reapplySetting(story.id,story.currentTimelineId,id,content)
                refreshArchive(story.id,story.currentTimelineId);refreshStory(story.id)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { archiveChangeError=e.message ?: "资料未保存，请重试" }
            } finally { withContext(Dispatchers.Main) { undoBusy=false } }
        }
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
        val reviews=archiveStore.listReviewRecords(storyId,timelineId)
        val reapplicable=archiveStore.listReapplicableSettings(storyId,timelineId)
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
                archiveReviewRecords.clear();archiveReviewRecords.addAll(reviews)
                archiveReapplicableRecords.clear();archiveReapplicableRecords.addAll(reapplicable)
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
        profile: ApiProfile, model: String, systemPrompt: String, history: List<ChatMessage>,
        cacheKey: String,
        generationOptions: com.adong.adchat.data.ChatGenerationOptions = com.adong.adchat.data.ChatGenerationOptions(),
        onDelta: suspend (String) -> Unit
    ): com.adong.adchat.data.ChatCompletionResult {
        val preparedHistory = com.adong.adchat.data.story.StoryImages.hydrate(getApplication(),storyId,history)
        val id = usageStore.begin(storyId,timelineId,category,profile.id,model,sourceId)
        var result: com.adong.adchat.data.ChatCompletionResult? = null
        var state = "failed"
        try {
            val response = api.streamChat(
                profile, model, systemPrompt, preparedHistory, cacheKey,
                trimHistory = false,
                skillsAllowed = category in setOf("prose", "discussion") && cacheKey.startsWith("aster-story-"),
                generationOptions = generationOptions,
                onDelta = onDelta
            )
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
        rewriteJob?.cancel()
        jobs.values.forEach { it.cancel() }
        organizerJobs.values.forEach { it.cancel() }
        val pending = jobs.values.toList() + organizerJobs.values.toList() + listOfNotNull(rewriteJob)
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
