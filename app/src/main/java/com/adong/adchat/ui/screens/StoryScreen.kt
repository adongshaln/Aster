package com.adong.adchat.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.layout.onSizeChanged
import coil.compose.AsyncImage
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.story.Story
import com.adong.adchat.data.story.StoryChangeEntry
import com.adong.adchat.data.story.StoryMemoryKind
import com.adong.adchat.data.story.StoryProposal
import com.adong.adchat.data.story.StoryMemoryRecord
import com.adong.adchat.data.story.StoryMessageWithRevision
import com.adong.adchat.data.story.StoryRevisionState
import com.adong.adchat.data.story.StoryWorkspace
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.components.AsterArtwork
import com.adong.adchat.ui.components.AsterIconButton
import com.adong.adchat.ui.components.AsterMark
import com.adong.adchat.ui.components.READING_BODY_FONT_SP
import com.adong.adchat.ui.components.READING_BODY_LINE_SP
import com.adong.adchat.ui.story.StoryViewModel
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun StoryScreen(
    mainVm: MainViewModel,
    storyVm: StoryViewModel,
    onOpenDrawer: () -> Unit,
    onCreateStory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val story = storyVm.activeStory
    if (story == null) {
        StoryEmptyState(onOpenDrawer, onCreateStory)
        return
    }

    var showStoryPicker by remember { mutableStateOf(false) }
    val profile = mainVm.profiles.firstOrNull { it.id == story.profileId }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        StoryHeader(
            story = story,
            workspace = storyVm.activeWorkspace,
            profile = profile,
            onOpenDrawer = onOpenDrawer,
            onStoryPicker = { showStoryPicker = true },
            onWorkspace = storyVm::switchWorkspace,
            onArchive = storyVm::openArchive,
            onCreateStory = onCreateStory
        )
        TextButton(onClick = storyVm::openTimelineHistory, modifier = Modifier.align(Alignment.End), enabled = !storyVm.revisionBusy) {
            Text("历史路线", style = MaterialTheme.typography.labelSmall, color = MutedInk)
        }
        key(story.id, story.currentTimelineId, storyVm.activeWorkspace) {
            StoryWorkspaceContent(
                storyVm = storyVm,
                profile = profile,
                onOpenSettings = onOpenSettings
            )
        }
    }

    if (storyVm.timelineHistoryOpen) {
        AlertDialog(onDismissRequest = storyVm::closeTimelineHistory, title = { Text("历史路线") },
            text = { Column {
                Text("切回旧路线会恢复其正文与资料，当前路线也会保留。", style = MaterialTheme.typography.bodySmall)
                storyVm.revisionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(storyVm.timelineHistory, key = { it.id }) { timeline ->
                        TextButton(onClick = { storyVm.restoreTimeline(timeline.id) },
                            enabled = !storyVm.revisionBusy && timeline.id != story.currentTimelineId && StoryWorkspace.entries.none { storyVm.isLoading(it) }) {
                            Text((if (timeline.parentTimelineId == null) "原路线" else "修订路线 · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timeline.createdAt))}") +
                                if (timeline.id == story.currentTimelineId) "（当前）" else "")
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = storyVm::closeTimelineHistory, enabled = !storyVm.revisionBusy) { Text("关闭") } })
    }

    if (showStoryPicker) {
        StoryPickerSheet(
            stories = storyVm.stories,
            activeStoryId = story.id,
            onSelect = {
                storyVm.selectStory(it.id)
                showStoryPicker = false
            },
            onCreate = {
                showStoryPicker = false
                onCreateStory()
            },
            onDismiss = { showStoryPicker = false }
        )
    }

    if (storyVm.archiveOpen) {
        StoryArchiveSheet(
            story = story,
            records = storyVm.archiveRecords,
            conflicts = storyVm.archiveConflicts,
            onResolveConflict = storyVm::resolveConflict,
            proposals = storyVm.archiveProposals,
            memoryStatus = storyVm.memoryStatus,
            usageText = storyVm.usageText,
            initialSection = storyVm.archiveInitialSection,
            reviewRecords = storyVm.archiveReviewRecords,
            reapplicableRecords = storyVm.archiveReapplicableRecords,
            onReapply = storyVm::reapplyArchiveSetting,
            changes = storyVm.archiveChanges,
            changeError = storyVm.archiveChangeError,
            undoBusy = storyVm.undoBusy,
            onUndo = storyVm::undoArchiveChange,
            onDecide = storyVm::decideProposal,
            onRetryMemory = storyVm::retryMemory,
            availableProfiles = mainVm.profiles,
            onReplaceRoute = storyVm::replaceActiveRoute,
            onAutomaticMemory = storyVm::setAutomaticMemoryEnabled,
            onAdd = storyVm::addArchiveRecord,
            onUpdate = storyVm::updateArchiveRecord,
            onPin = storyVm::setArchivePinned,
            onRemove = storyVm::removeArchiveRecord,
            onDismiss = storyVm::closeArchive
        )
    }
}

@Composable
private fun StoryHeader(
    story: Story,
    workspace: StoryWorkspace,
    profile: ApiProfile?,
    onOpenDrawer: () -> Unit,
    onStoryPicker: () -> Unit,
    onWorkspace: (StoryWorkspace) -> Unit,
    onArchive: () -> Unit,
    onCreateStory: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsterIconButton(Icons.Rounded.Menu, "打开侧栏", onOpenDrawer)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onStoryPicker)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(story.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile?.let { "${it.name} · ${story.model}" } ?: "服务已不可用 · 点击档案重新选择",
                        color = if (profile == null) Danger else MutedInk,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.size(15.dp), tint = MutedInk)
                }
            }
            AsterIconButton(Icons.Rounded.FolderOpen, "故事档案", onArchive)
            AsterIconButton(Icons.Rounded.Add, "新建故事", onCreateStory)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 2.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StoryWorkspace.entries.forEach { item ->
                val selected = workspace == item
                Surface(
                    onClick = { onWorkspace(item) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) AccentSoft else Color.Transparent,
                    contentColor = if (selected) Accent else MutedInk
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (item == StoryWorkspace.Discussion) Icons.Rounded.Forum else Icons.Rounded.AutoStories,
                            null,
                            Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (item == StoryWorkspace.Discussion) "讨论" else "正文",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryWorkspaceContent(
    storyVm: StoryViewModel,
    profile: ApiProfile?,
    onOpenSettings: () -> Unit
) {
    val workspace = storyVm.activeWorkspace
    val messages = storyVm.messages(workspace)
    val savedState = storyVm.workspaceState(workspace)
    val targetStory = storyVm.activeStory ?: return
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if(uris.isNotEmpty()) storyVm.importAttachments(uris,true,targetStory.id,targetStory.currentTimelineId,workspace)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) storyVm.importAttachments(listOf(uri),false,targetStory.id,targetStory.currentTimelineId,workspace)
    }
    val composerDensity=LocalDensity.current
    var composerHeight by remember { mutableStateOf(100.dp) }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedState.firstVisibleIndex.coerceAtMost(messages.lastIndex.coerceAtLeast(0)),
        initialFirstVisibleItemScrollOffset = savedState.firstVisibleOffset.coerceAtLeast(0)
    )
    val scope = rememberCoroutineScope()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var autoFollow by remember { mutableStateOf(true) }
    val loading = storyVm.isLoading(workspace)
    val lastIsStreamingAssistant = messages.lastOrNull()?.let {
        it.message.role == "assistant" && it.revision.state == StoryRevisionState.Streaming
    } == true

    DisposableEffect(workspace) {
        onDispose {
            storyVm.saveScroll(workspace, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, savedState.timelineId)
        }
    }
    LaunchedEffect(dragging, listState.canScrollForward) {
        if (dragging) autoFollow = !listState.canScrollForward
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.revision?.content?.length, loading) {
        if (autoFollow && !dragging) {
            val target = when {
                loading && !lastIsStreamingAssistant -> messages.size
                messages.isNotEmpty() -> messages.lastIndex
                loading -> 0
                else -> -1
            }
            if (target >= 0) runCatching { listState.animateScrollToItem(target) }
        }
    }

    storyVm.revisionTarget?.takeIf { !storyVm.rewriteOpen }?.let { target ->
        var revisedText by remember(target.revision.id) { mutableStateOf(TextFieldValue(target.revision.content)) }
        AlertDialog(
            onDismissRequest = storyVm::closeRevisionEditor,
            title = { Text("修订正文") },
            text = {
                Column(Modifier.heightIn(max = 480.dp)) {
                    Text("末尾正文可保存为新版本。较早正文请从这里另写：旧后续留在历史路线，新路线从生成前资料快照继续。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = revisedText, onValueChange = { revisedText = it },
                        enabled = !storyVm.revisionBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp))
                    if(target.revision.state == StoryRevisionState.Complete) {
                        TextButton(onClick = {
                            val selection = revisedText.selection
                            storyVm.discussProseSelection(
                                if(selection.collapsed) 0 else selection.start,
                                if(selection.collapsed) revisedText.text.length else selection.end
                            )
                        }, enabled = !storyVm.revisionBusy && revisedText.text == target.revision.content) {
                            Text(if(revisedText.selection.collapsed) "带入讨论草稿（整段）" else "带入讨论草稿（选中文字）")
                        }
                    }
                    if(storyVm.canModelRewrite(target)) TextButton(onClick=storyVm::openModelRewrite,
                        enabled=!storyVm.revisionBusy && revisedText.text==target.revision.content) { Text("让模型重写这一段") }
                    storyVm.revisionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { storyVm.saveProseRevision(revisedText.text, fork = true) },
                        enabled = !storyVm.revisionBusy && revisedText.text.isNotBlank() && revisedText.text.trim() != target.revision.content) {
                        Text("保留旧后续，从这里另写")
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(storyVm.revisionHistory, key = { it.id }) { version ->
                            TextButton(
                                onClick = { storyVm.saveProseRevision("", version.id) },
                                enabled = !storyVm.revisionBusy && version.id != target.revision.id && version.state == StoryRevisionState.Complete
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(if (version.id == target.revision.id) "当前版本" else "恢复此版本",
                                        style = MaterialTheme.typography.labelMedium)
                                    Text(version.content, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { storyVm.saveProseRevision(revisedText.text) },
                    enabled = !storyVm.revisionBusy && revisedText.text.isNotBlank() && revisedText.text.trim() != target.revision.content) {
                    Text(if (storyVm.revisionBusy) "保存中…" else "保存新版本")
                }
            },
            dismissButton = { TextButton(onClick = storyVm::closeRevisionEditor, enabled = !storyVm.revisionBusy) { Text("关闭") } }
        )
    }

    if(storyVm.rewriteOpen && storyVm.revisionTarget != null) {
        val candidate=storyVm.rewriteCandidate
        AlertDialog(onDismissRequest=storyVm::closeModelRewrite,
            title={ Text("重写候选") },
            text={ Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())) {
                Text(if(storyVm.isHistoricalRewrite()) "从这段原文生成前的资料重写。采用后从此另写，旧后续保留在历史路线，不会自动接入新路线。" else "请明确写下讨论后决定采用的修改；生成后先预览，原文在采用前保持不变。",
                    style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=storyVm.rewriteInstruction,onValueChange=storyVm::updateRewriteInstruction,
                    label={ Text("修改要求") },enabled=!storyVm.revisionBusy,
                    modifier=Modifier.fillMaxWidth().heightIn(min=80.dp,max=160.dp))
                var editOriginal by remember { mutableStateOf(false) }
                TextButton(onClick={editOriginal=!editOriginal}) { Text(if(editOriginal) "收起原始输入" else "修改这段的原始输入") }
                if(editOriginal) {
                    Text("修改原始输入会保留旧路线，从该段重新创作。",style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value=storyVm.rewriteOriginalInput,onValueChange=storyVm::updateRewriteOriginalInput,
                        enabled=!storyVm.revisionBusy,modifier=Modifier.fillMaxWidth().heightIn(max=160.dp))
                }
                TextButton(onClick=storyVm::generateModelRewrite,enabled=!storyVm.revisionBusy && storyVm.rewriteInstruction.isNotBlank()) {
                    Text(if(candidate==null) "生成候选" else "重新生成候选")
                }
                if(storyVm.revisionBusy && candidate?.state=="generating") TextButton(onClick=storyVm::stopModelRewrite) { Text("停止生成") }
                candidate?.let {
                    Text(when(it.state) { "ready" -> "已完整生成 · 尚未采用"; "generating" -> "正在生成…"; "adopted" -> "已采用"; else -> "未完整完成 · 不可采用，可重新生成" },
                        style=MaterialTheme.typography.labelMedium)
                    if(it.content.isNotBlank()) SelectionContainer { Text(it.content,modifier=Modifier.padding(top=8.dp)) }
                }
                storyVm.revisionError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            } },
            confirmButton={ TextButton(onClick=storyVm::adoptModelRewrite,
                enabled=!storyVm.revisionBusy && candidate?.state=="ready") { Text(if(candidate?.mode=="fork") "采用并从这里另写" else "采用为新版本") } },
            dismissButton={ TextButton(onClick=storyVm::closeModelRewrite,enabled=!storyVm.revisionBusy) { Text("返回原文") } })
    }

    storyVm.discussionActionTarget?.let {
        AlertDialog(onDismissRequest=storyVm::closeDiscussionAction,title={Text("应用讨论结果")},
            text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())) {
                Text("请删去备选、示例和解释，仅保留你决定采用的内容。",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value=storyVm.discussionActionText,onValueChange=storyVm::updateDiscussionActionText,
                    enabled=!storyVm.revisionBusy,modifier=Modifier.fillMaxWidth().heightIn(min=120.dp,max=240.dp))
                TextButton(onClick={storyVm.applyDiscussionAction("future")},enabled=!storyVm.revisionBusy) {Text("用于后续 · 放入正文草稿")}
                TextButton(onClick={storyVm.applyDiscussionAction("world")},enabled=!storyVm.revisionBusy) {Text("确认为世界设定")}
                TextButton(onClick={storyVm.applyDiscussionAction("plan")},enabled=!storyVm.revisionBusy) {Text("保存为作者计划 · 尚未发生")}
                Text("重写哪一段？",style=MaterialTheme.typography.labelMedium)
                storyVm.discussionRewriteTargets.forEach { target ->
                    TextButton(onClick={storyVm.applyDiscussionAction("rewrite",target)},enabled=!storyVm.revisionBusy) {
                        Text(target.revision.content.take(60),maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
                storyVm.discussionActionError?.let { error -> Text(error,color=MaterialTheme.colorScheme.error) }
            }},confirmButton={TextButton(onClick=storyVm::closeDiscussionAction,enabled=!storyVm.revisionBusy) {Text("关闭")}})
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        if (messages.isEmpty() && !loading) {
            StoryWorkspaceEmpty(workspace, Modifier.fillMaxSize().padding(bottom = composerHeight))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = composerHeight + 20.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp)
            ) {
                items(messages, key = { it.message.id }) { row ->
                    val assistant = row.message.role == "assistant"
                    val pending = if (assistant && workspace == StoryWorkspace.Discussion) {
                        storyVm.archiveProposals.count { it.sourceRevisionId == row.revision.id }
                    } else 0
                    StoryMessageItem(
                        row = row,
                        workspace = workspace,
                        pendingCount = pending,
                        actionsEnabled = !storyVm.revisionBusy && StoryWorkspace.entries.none { storyVm.isLoading(it) },
                        onOpenDiscussionAction = { storyVm.openDiscussionAction(row) },
                        onOpenPendingCandidates = storyVm::openPendingCandidates,
                        onOpenRevision = { storyVm.openRevisionEditor(row) }
                    )
                }
                if (loading && !lastIsStreamingAssistant) {
                    item(key = "story-thinking-${workspace.name}") {
                        StoryThinkingIndicator()
                    }
                }
            }
        }

        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(composerHeight).background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    .46f to Canvas.copy(alpha = .38f),
                    1f to Canvas.copy(alpha = .96f)
                )
            )
        )

        storyVm.error(workspace)?.let { error ->
            Surface(
                color = DangerSoft,
                contentColor = Danger,
                shape = RoundedCornerShape(13.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, bottom = composerHeight)
                    .clickable { storyVm.clearError(workspace) }
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(error, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.Close, "关闭", Modifier.size(16.dp))
                }
            }
        }

        if (listState.canScrollForward) {
            Surface(
                onClick = {
                    autoFollow = true
                    val target = if (loading && !lastIsStreamingAssistant) messages.size else messages.lastIndex
                    if (target >= 0) scope.launch { listState.animateScrollToItem(target) }
                },
                shape = RoundedCornerShape(24.dp),
                color = Surface,
                contentColor = Accent,
                border = BorderStroke(1.dp, Hairline),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = composerHeight + 8.dp)
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (loading) "跟随生成" else "回到底部", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        StoryComposer(
            value = storyVm.draft(workspace),
            attachments = savedState.attachments,
            attachmentBusy = storyVm.attachmentBusy,
            onPickImages = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPickDocument = { documentPicker.launch(com.adong.adchat.data.DocumentImport.mimeTypes) },
            onRemoveImage = { storyVm.removeDraftImage(it,workspace) },
            workspace = workspace,
            loading = loading,
            routeAvailable = profile != null,
            onValueChange = { storyVm.updateDraft(it, workspace) },
            onSend = {
                if (profile != null) storyVm.send(profile, workspace) else onOpenSettings()
                autoFollow = true
            },
            onStop = { storyVm.stop(workspace) },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().onSizeChanged { composerHeight=with(composerDensity) { it.height.toDp() } }
        )
    }
}

@Composable
private fun StoryMessageItem(
    row: StoryMessageWithRevision,
    workspace: StoryWorkspace,
    pendingCount: Int,
    actionsEnabled: Boolean,
    onOpenDiscussionAction: () -> Unit,
    onOpenPendingCandidates: () -> Unit,
    onOpenRevision: () -> Unit
) {
    val user = row.message.role == "user"
    val waitingForFirstToken = !user && row.revision.content.isBlank() && row.revision.state == StoryRevisionState.Streaming
    val context = LocalContext.current
    var showDetails by remember(row.message.id) { mutableStateOf(false) }
    val hasDetails = !user && row.revision.state != StoryRevisionState.Streaming && when (workspace) {
        StoryWorkspace.Discussion -> row.revision.state == StoryRevisionState.Complete
        StoryWorkspace.Prose -> true
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            if (user) Modifier.widthIn(max = 520.dp) else Modifier.weight(1f).widthIn(max = 680.dp),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start
        ) {
            if (user) {
                Surface(color = SurfaceInset, contentColor = Ink, shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)) {
                    Column(Modifier.padding(7.dp)) {
                        if (row.revision.attachments.isNotEmpty()) StoryImageStrip(row.revision.attachments)
                        if (row.revision.content.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = storyAnnotatedText(row.revision.content),
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Ink
                                )
                            }
                        }
                    }
                }
            } else {
                if (!waitingForFirstToken) {
                    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsterMark(Modifier.size(26.dp), tint = Accent)
                        Spacer(Modifier.width(5.dp))
                        Text("Aster", color = MutedInk, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                }
                if (waitingForFirstToken) {
                    StoryThinkingIndicator()
                } else {
                    StructuredMessageText(
                        content = row.revision.content,
                        streaming = row.revision.state == StoryRevisionState.Streaming,
                        error = false
                    )
                }
                if (row.revision.state in setOf(StoryRevisionState.Interrupted, StoryRevisionState.Stopped)) {
                    Surface(
                        color = if (row.revision.state == StoryRevisionState.Stopped) Color(0xFFF0EDE8) else Color(0xFFFFF1D8),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            if (row.revision.state == StoryRevisionState.Stopped) "已停止生成，当前内容已保留" else "回复未完整结束，内容已保留且不计入正式剧情",
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            color = MutedInk,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (row.revision.state != StoryRevisionState.Streaming && row.revision.content.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StoryMessageActionButton(
                                icon = Icons.Outlined.ContentCopy,
                                label = "复制",
                                onClick = {
                                    context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                        android.content.ClipData.newPlainText("Aster Story", row.revision.content)
                                    )
                                }
                            )
                            if (hasDetails) {
                                StoryMessageActionButton(
                                    icon = Icons.Rounded.MoreHoriz,
                                    label = if (showDetails) "收起" else "详情",
                                    onClick = { showDetails = !showDetails }
                                )
                            }
                        }
                        if (showDetails && hasDetails) {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (workspace == StoryWorkspace.Discussion && row.revision.state == StoryRevisionState.Complete) {
                                    StoryDetailAction(
                                        icon = Icons.Rounded.Rule,
                                        label = "应用讨论结果",
                                        detail = "确认采用的设定、计划，或用于重写正文",
                                        enabled = actionsEnabled,
                                        onClick = onOpenDiscussionAction
                                    )
                                    if (pendingCount > 0) {
                                        StoryDetailAction(
                                            icon = Icons.Rounded.PendingActions,
                                            label = "$pendingCount 项待定设定",
                                            detail = "查看自动整理出的候选，并决定采用或废弃",
                                            enabled = true,
                                            onClick = onOpenPendingCandidates
                                        )
                                    }
                                }
                                if (workspace == StoryWorkspace.Prose) {
                                    StoryDetailAction(
                                        icon = Icons.Rounded.History,
                                        label = "修订 / 版本",
                                        detail = "编辑这一段、查看历史版本或从这里另写",
                                        enabled = actionsEnabled,
                                        onClick = onOpenRevision
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryMessageActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = Ink,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.heightIn(min = 36.dp)
    ) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StoryDetailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = if (enabled) Surface else SurfaceInset,
        contentColor = if (enabled) Ink else MutedInk.copy(alpha = .55f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = if (enabled) Accent else MutedInk.copy(alpha = .45f))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(detail, color = MutedInk, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MutedInk)
        }
    }
}

@Composable
private fun StoryThinkingIndicator() {
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "story-thinking")
    val motion by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(3520, easing = LinearEasing)),
        label = "story-thinking-motion"
    )
    val subtitleAlpha by transition.animateFloat(
        initialValue = .58f,
        targetValue = .86f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
        label = "story-thinking-subtitle"
    )
    val step = motion.toInt().coerceIn(0, 3)
    val local = (motion - step).coerceIn(0f, 1f)
    val hopPortion = .62f
    val hopProgress = (local / hopPortion).coerceIn(0f, 1f)
    val eased = hopProgress * hopProgress * (3f - 2f * hopProgress)
    val jumpDp = if (local < hopPortion) (-6f * sin(PI * hopProgress)).toFloat() else 0f
    val rotation = step * 90f + if (local < hopPortion) eased * 90f else 90f
    val jumpPx = with(density) { jumpDp.dp.toPx() }
    Row(Modifier.heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            AsterArtwork(
                Modifier.size(28.dp).graphicsLayer {
                    translationY = jumpPx
                    rotationZ = rotation
                }
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "Aster 正在思考",
                color = Ink,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                "正在组织回答…",
                color = MutedInk.copy(alpha = subtitleAlpha),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryComposer(
    value: String,
    attachments: List<com.adong.adchat.data.ChatImageAttachment>,
    attachmentBusy: Boolean,
    onPickImages: () -> Unit,
    onPickDocument: () -> Unit,
    onRemoveImage: (String) -> Unit,
    workspace: StoryWorkspace,
    loading: Boolean,
    routeAvailable: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAttachments by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val capsuleShape = RoundedCornerShape(31.dp)
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (focused) 230 else 180,
            easing = FastOutSlowInEasing
        ),
        label = "story-composer-focus-progress"
    )
    val minimumHeight = 58.dp + 52.dp * focusProgress
    val fieldStart = 58.dp - 40.dp * focusProgress
    val fieldEnd = 58.dp - 40.dp * focusProgress
    val fieldTop = 17.dp - 2.dp * focusProgress
    val fieldBottom = 15.dp + 42.dp * focusProgress
    LaunchedEffect(focused, workspace, density) {
        if (!focused) return@LaunchedEffect
        var imeWasVisible = imeInsets.getBottom(density) > 0
        snapshotFlow { imeInsets.getBottom(density) to imeTarget.getBottom(density) }
            .collect { (bottom, target) ->
                if (bottom > 0) imeWasVisible = true
                if (imeWasVisible && target == 0) focusManager.clearFocus()
            }
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        if (attachments.isNotEmpty()) {
            Surface(
                color = Surface.copy(alpha = .94f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Hairline.copy(alpha = .72f)),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
            ) {
                StoryImageStrip(attachments, onRemoveImage)
            }
        }
        Surface(
            color = Surface.copy(alpha = .97f),
            shape = capsuleShape,
            border = BorderStroke(1.dp, if (focused) Accent.copy(alpha = .35f) else Hairline),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().shadow(
                elevation = 4.dp,
                shape = capsuleShape,
                ambientColor = Color.Black.copy(alpha = .07f),
                spotColor = Color.Black.copy(alpha = .10f)
            )
        ) {
            Column {
                if (attachmentBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = minimumHeight)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = fieldStart, end = fieldEnd, top = fieldTop, bottom = fieldBottom)
                            .heightIn(min = 24.dp, max = 132.dp)
                            .onFocusChanged { focused = it.isFocused },
                        maxLines = if (focused) 5 else 1,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                        cursorBrush = SolidColor(Accent),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (value.isEmpty() && !focused) {
                                    Text(
                                        if (!routeAvailable) "先选择故事使用的模型" else if (workspace == StoryWorkspace.Discussion) "讨论设定、人物或下一步…" else "告诉 Aster 接下来发生什么…",
                                        color = MutedInk,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                inner()
                            }
                        }
                    )
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(58.dp).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                showAttachments = true
                            },
                            enabled = !loading && !attachmentBusy,
                            modifier = Modifier.size(46.dp)
                        ) {
                            if (attachmentBusy) CircularProgressIndicator(Modifier.size(19.dp), color = Accent, strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.Add, "添加图片或文件", Modifier.size(29.dp), tint = Ink)
                        }
                        Spacer(Modifier.weight(1f))
                        FilledIconButton(
                            onClick = if (loading) onStop else { { focusManager.clearFocus(); onSend() } },
                            enabled = loading || (!attachmentBusy && (value.isNotBlank() || attachments.isNotEmpty() || !routeAvailable)),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Night,
                                contentColor = Color.White,
                                disabledContainerColor = Color(0xFFE6E1DB),
                                disabledContentColor = Color(0xFFA9A39C)
                            ),
                            modifier = Modifier.size(46.dp)
                        ) {
                            Icon(if (loading) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward, if (loading) "停止生成" else "发送", Modifier.size(if (loading) 21.dp else 23.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAttachments) {
        ModalBottomSheet(
            onDismissRequest = { showAttachments = false },
            containerColor = Canvas
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("添加到故事", style = MaterialTheme.typography.titleLarge, color = Ink)
                Text(
                    if (workspace == StoryWorkspace.Discussion) "图片和文档会作为本轮讨论的上下文" else "图片和文档会随本轮正文输入一起保存",
                    color = MutedInk,
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StoryComposerSheetAction(
                        icon = Icons.Rounded.AddPhotoAlternate,
                        label = "图片",
                        detail = "最多 4 张",
                        enabled = !loading && !attachmentBusy && attachments.size < 4,
                        onClick = { showAttachments = false; onPickImages() },
                        modifier = Modifier.weight(1f)
                    )
                    StoryComposerSheetAction(
                        icon = Icons.Rounded.AttachFile,
                        label = "文件",
                        detail = "文本 / DOCX / PDF",
                        enabled = !loading && !attachmentBusy,
                        onClick = { showAttachments = false; onPickDocument() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryComposerSheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Surface,
        contentColor = if (enabled) Ink else MutedInk.copy(alpha = .45f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Hairline.copy(alpha = .75f)),
        modifier = modifier
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, Modifier.size(23.dp), tint = if (enabled) Accent else MutedInk.copy(alpha = .4f))
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MutedInk, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun StoryWorkspaceEmpty(workspace: StoryWorkspace, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        AsterMark(Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(if (workspace == StoryWorkspace.Discussion) "先聊聊这个故事。" else "从这里开始正文。", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(9.dp))
        Text(
            if (workspace == StoryWorkspace.Discussion)
                "设定、人物、文风和剧情计划都可以先讨论。没有明确采用的方案，不会自动变成正式设定。"
            else
                "告诉 Aster 剧情方向、对白或人物行动。正文与讨论分开保存，不会把废案混进故事。",
            color = MutedInk,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun StoryEmptyState(onOpenDrawer: () -> Unit, onCreateStory: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AsterIconButton(Icons.Rounded.Menu, "打开侧栏", onOpenDrawer)
            Text("故事", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 30.dp), verticalArrangement = Arrangement.Center) {
            AsterMark(Modifier.size(74.dp))
            Spacer(Modifier.height(18.dp))
            Text("写一个会记得的故事。", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Text("先讨论设定，或直接开始正文。Aster 会把两者分开处理。", color = MutedInk, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onCreateStory, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("新建故事")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryPickerSheet(
    stories: List<Story>,
    activeStoryId: String,
    onSelect: (Story) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canvas) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的故事", style = MaterialTheme.typography.titleLarge)
                    Text("讨论、正文和档案都会随故事保存", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onCreate) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
            }
            Spacer(Modifier.height(12.dp))
            stories.forEach { story ->
                val selected = story.id == activeStoryId
                Surface(
                    onClick = { onSelect(story) },
                    color = if (selected) AccentSoft else Surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoStories, null, tint = if (selected) Accent else MutedInk)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(story.title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(story.model.ifBlank { "未选择模型" }, color = MutedInk, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        if (selected) Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StoryArchiveSheet(
    story: Story,
    records: List<StoryMemoryRecord>,
    conflicts: List<com.adong.adchat.data.story.StoryConflictEntry>,
    onResolveConflict: (com.adong.adchat.data.story.StoryConflictEntry, Boolean) -> Unit,
    proposals: List<StoryProposal>,
    memoryStatus: String,
    usageText: String,
    initialSection: Int,
    reviewRecords: List<StoryMemoryRecord>,
    reapplicableRecords: List<StoryMemoryRecord>,
    onReapply: (String, String, Boolean) -> Unit,
    changes: List<StoryChangeEntry>,
    changeError: String?,
    undoBusy: Boolean,
    onUndo: (String, Boolean) -> Unit,
    onDecide: (String, Boolean, String?) -> Unit,
    onRetryMemory: () -> Unit,
    availableProfiles: List<ApiProfile>,
    onReplaceRoute: (ApiProfile) -> Unit,
    onAutomaticMemory: (Boolean) -> Unit,
    onAdd: (StoryMemoryKind, String, Boolean) -> Unit,
    onUpdate: (String, String, Boolean) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var section by remember { mutableIntStateOf(initialSection) }
    var reapplying by remember { mutableStateOf<Pair<StoryMemoryRecord,Boolean>?>(null) }
    reapplying?.let { (record, review) ->
        var text by remember(record.id) { mutableStateOf(record.content) }
        AlertDialog(onDismissRequest={reapplying=null},title={Text(if(review) "复核并重新确认" else "用于当前路线")},
            text={Column {
                Text(if(review) "请根据新正文修正内容。确认后保存为独立资料，旧记录保留在变更历史中。" else "这是旧路线中的独立设定。确认适用于当前路线后再保存；旧路线保持原样。")
                OutlinedTextField(value=text,onValueChange={text=it},modifier=Modifier.heightIn(max=280.dp))
            }},confirmButton={TextButton(onClick={onReapply(record.id,text,review);reapplying=null},enabled=!undoBusy && text.isNotBlank() && text.length<=8000) {Text("确认保存")}},
            dismissButton={TextButton(onClick={reapplying=null}) {Text("取消")}})
    }
    var editingProposal by remember { mutableStateOf<StoryProposal?>(null) }
    editingProposal?.let { proposal ->
        var text by remember(proposal.id) { mutableStateOf(proposal.content) }
        AlertDialog(onDismissRequest={editingProposal=null},title={Text("编辑后采用")},
            text={OutlinedTextField(value=text,onValueChange={text=it},modifier=Modifier.heightIn(max=280.dp))},
            confirmButton={Button(onClick={onDecide(proposal.id,true,text);editingProposal=null},enabled=text.isNotBlank() && text.length<=8000) {Text("采用这一项")}},
            dismissButton={OutlinedButton(onClick={editingProposal=null}) {Text("取消")}})
    }
    var viewingChange by remember { mutableStateOf<StoryChangeEntry?>(null) }
    viewingChange?.let { change ->
        AlertDialog(onDismissRequest = { viewingChange = null }, title = { Text(change.title) },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("记忆版本 ${change.version}", style = MaterialTheme.typography.labelMedium)
                if (change.before.isNotBlank()) { Text("变更前", fontWeight = FontWeight.SemiBold); Text(change.before) }
                if (change.after.isNotBlank()) { Text("变更后", fontWeight = FontWeight.SemiBold); Text(change.after) }
                if (change.source.isNotBlank()) { Text("来源正文 / 讨论", fontWeight = FontWeight.SemiBold); Text(change.source) }
                if (change.note.isNotBlank()) Text(change.note, color = MutedInk)
            } }, confirmButton = { Button(onClick = { viewingChange = null }) { Text("关闭") } })
    }
    var editing by remember { mutableStateOf<StoryMemoryRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showRouteMenu by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Canvas) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("故事档案", style = MaterialTheme.typography.titleLarge)
                    Text(story.title, color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
                if (section != 3) OutlinedButton(onClick = { adding = true }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("设定", "人物", "剧情", "变更").forEachIndexed { index, label ->
                    FilterChip(selected = section == index, onClick = { section = index }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(10.dp))
            if (section == 3) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        ArchiveInfoCard("自动记忆", if (story.automaticMemoryEnabled) "已开启" else "已关闭") {
                            Switch(checked = story.automaticMemoryEnabled, onCheckedChange = onAutomaticMemory)
                        }
                    }
                    item { ArchiveInfoCard("整理状态", memoryStatus) {
                        ArchiveActionButton("重试失败项", onRetryMemory, enabled = story.automaticMemoryEnabled)
                    } }
                    item {
                        var expanded by remember { mutableStateOf(false) }
                        ArchiveInfoCard("故事用量", if (expanded) usageText else "创作、讨论、整理与摘要的调用记录") {
                            ArchiveActionButton(if (expanded) "收起" else "查看", { expanded = !expanded })
                        }
                    }
                    item { ArchiveInfoCard("记忆版本", story.memoryVersion.toString()) }
                    changeError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    if (conflicts.isNotEmpty()) item { Text("资料冲突 · ${conflicts.size}", style = MaterialTheme.typography.titleSmall) }
                    items(conflicts, key = { "conflict-${it.id}" }) { entry ->
                        var showSources by remember(entry.id) { mutableStateOf(false) }
                        Surface(color = Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Hairline)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(entry.conflict.description, style = MaterialTheme.typography.bodyMedium)
                                Text("选择后停用另一条资料，并保留固定约束；决定可在最近变更中整体撤销。",
                                    color = MutedInk, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                                ArchiveActionButton(if (showSources) "收起来源" else "查看双方来源", { showSources = !showSources })
                                if (showSources) {
                                    Text("原资料来源", fontWeight = FontWeight.SemiBold)
                                    Text(entry.earlierSource, style = MaterialTheme.typography.bodySmall)
                                    Text("新资料来源", fontWeight = FontWeight.SemiBold)
                                    Text(entry.latestSource, style = MaterialTheme.typography.bodySmall)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ArchiveActionButton("保留原资料", { onResolveConflict(entry, false) }, enabled = !undoBusy)
                                    ArchiveActionButton("采用新资料", { onResolveConflict(entry, true) }, enabled = !undoBusy, primary = true)
                                }
                            }
                        }
                    }
                    if(reviewRecords.isNotEmpty()) item {Text("待复核 · 引用的正文已变化",style=MaterialTheme.typography.titleSmall)}
                    items(reviewRecords,key={"review-${it.id}"}) { record ->
                        ArchiveInfoCard("暂不用于正文",record.content+"\n引用来源已变化，需要重新判断是否适用。") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ArchiveActionButton("复核并确认", { reapplying=record to true }, enabled=!undoBusy, primary = true)
                                if(record.timelineId==story.currentTimelineId) ArchiveActionButton("停用", { onRemove(record.id) }, enabled=!undoBusy, danger = true)
                            }
                        }
                    }
                    if(reapplicableRecords.isNotEmpty()) item {Text("旧路线的独立设定",style=MaterialTheme.typography.titleSmall)}
                    items(reapplicableRecords,key={"reapply-${it.id}"}) { record ->
                        ArchiveInfoCard("尚未用于当前路线",record.content) {
                            ArchiveActionButton("检查并采用", { reapplying=record to false }, enabled=!undoBusy, primary = true)
                        }
                    }
                    if (proposals.isNotEmpty()) item { Text("待确认 · ${proposals.size}", style = MaterialTheme.typography.titleSmall) }
                    items(proposals, key = { "proposal-${it.id}" }) { proposal ->
                        Surface(color = Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Hairline)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("待确认候选", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                                Text(proposal.content, modifier = Modifier.padding(vertical = 8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ArchiveActionButton("采用", { onDecide(proposal.id, true,null) }, primary = true)
                                    ArchiveActionButton("编辑后采用", { editingProposal=proposal })
                                    ArchiveActionButton("废弃", { onDecide(proposal.id, false,null) }, danger = true)
                                }
                            }
                        }
                    }
                    item { Text("最近变更（最多 100 条）", style = MaterialTheme.typography.titleSmall) }
                    items(changes, key = { "change-${it.id}" }) { change ->
                        Surface(color = Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Hairline)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(change.title, fontWeight = FontWeight.SemiBold)
                                Text("记忆版本 ${change.version}", style = MaterialTheme.typography.labelSmall, color = MutedInk)
                                val preview = change.after.ifBlank { change.before.ifBlank { change.source } }
                                if (preview.isNotBlank()) Text(preview, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                                if (change.note.isNotBlank()) Text(change.note, color = MutedInk, style = MaterialTheme.typography.labelSmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ArchiveActionButton("查看详情", { viewingChange = change })
                                    if (change.canUndo) ArchiveActionButton(if (change.batch) "整体撤销" else "撤销此改动", { onUndo(change.id, change.batch) }, enabled = !undoBusy, danger = true)
                                }
                            }
                        }
                    }
                    item {
                        Surface(
                            color = Surface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Hairline),
                            modifier = Modifier.fillMaxWidth().clickable { showRouteMenu = true }
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("故事模型", fontWeight = FontWeight.SemiBold)
                                    val route = availableProfiles.firstOrNull { it.id == story.profileId }
                                    Text(
                                        route?.let { "${it.name} · ${story.model}" } ?: "原服务已不可用，点击重新选择",
                                        color = if (route == null) Danger else MutedInk,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Icon(Icons.Rounded.ChevronRight, null, tint = MutedInk)
                            }
                        }
                    }
                    item {
                        Text(
                            "API 配置导入/导出目前只迁移服务配置，不包含故事正文与故事档案。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            } else {
                val stateView = com.adong.adchat.data.story.StoryStateProjection.project(records)
                val visible = stateView.records.filter { recordBelongsToSection(it.kind, section) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (stateView.conflicts.isNotEmpty()) item {
                        Text("状态待处理：请在「变更」中查看双方来源并决定保留哪一方，也可手动修正资料。\n" +
                            stateView.conflicts.joinToString("\n") { it.description },
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                "这里还没有记录。你可以先手动添加，后续自动整理也会写入档案。",
                                color = MutedInk,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                    items(visible, key = { it.id }) { record ->
                        ArchiveRecordRow(
                            record,
                            onEdit = { editing = record },
                            onPin = { onPin(record.id, !record.pinned) },
                            onRemove = { onRemove(record.id) }
                        )
                    }
                }
            }
        }
    }

    if (showRouteMenu) {
        AlertDialog(
            onDismissRequest = { showRouteMenu = false },
            title = { Text("选择故事模型") },
            text = {
                Column {
                    availableProfiles.forEach { profile ->
                        Surface(
                            onClick = { onReplaceRoute(profile); showRouteMenu = false },
                            color = if (profile.id == story.profileId) AccentSoft else Color.Transparent,
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(profile.name, fontWeight = FontWeight.SemiBold)
                                Text(profile.chatModel.ifBlank { "未选择模型" }, color = MutedInk, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showRouteMenu = false }) { Text("关闭") } }
        )
    }

    if (adding) {
        MemoryEditDialog(
            initial = null,
            defaultSection = section,
            onSave = { kind, text, pinned -> onAdd(kind, text, pinned); adding = false },
            onDismiss = { adding = false }
        )
    }
    editing?.let { record ->
        MemoryEditDialog(
            initial = record,
            defaultSection = section,
            onSave = { _, text, pinned -> onUpdate(record.id, text, pinned); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun ArchiveActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false
) {
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = WarmWhite),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (danger) Danger.copy(alpha = .38f) else Hairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (danger) Danger else Ink),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 8.dp)
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun ArchiveRecordRow(record: StoryMemoryRecord, onEdit: () -> Unit, onPin: () -> Unit, onRemove: () -> Unit) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(17.dp),
        border = BorderStroke(1.dp, Hairline),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(memoryKindLabel(record.kind), color = Accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (record.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.PushPin, "已固定", Modifier.size(14.dp), tint = Accent)
                    }
                }
                Text(com.adong.adchat.data.story.storyMemoryNatureLabel(record), color = MutedInk, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                if (record.subjectEntityNames.isNotEmpty()) {
                    Text(record.subjectEntityNames.first() + record.objectEntityNames.firstOrNull()?.let { " → $it" }.orEmpty(),
                        color = MutedInk, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                }
                if (record.summarySourceRevisionIds.isNotEmpty()) Text("覆盖 ${record.summarySourceRevisionIds.size} 轮正文，来源重写后自动失效",
                    color = MutedInk, style = MaterialTheme.typography.labelSmall)
                record.stateKey?.let { Text("属性：$it · 正文轮次 ${record.effectiveSequence}",
                    color = MutedInk, style = MaterialTheme.typography.labelSmall) }
                Text(record.content, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onPin, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.PushPin, "固定", Modifier.size(17.dp), tint = if (record.pinned) Accent else MutedInk)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.DeleteOutline, "停用资料", Modifier.size(17.dp), tint = MutedInk)
            }
        }
    }
}

@Composable
private fun ArchiveInfoCard(title: String, detail: String, trailing: @Composable (() -> Unit)? = null) {
    Surface(color = Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Hairline), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, color = MutedInk, style = MaterialTheme.typography.bodySmall)
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun MemoryEditDialog(
    initial: StoryMemoryRecord?,
    defaultSection: Int,
    onSave: (StoryMemoryKind, String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var content by remember(initial?.id) { mutableStateOf(initial?.content.orEmpty()) }
    var pinned by remember(initial?.id) { mutableStateOf(initial?.pinned == true) }
    var kind by remember(initial?.id) { mutableStateOf(initial?.kind ?: defaultKindForSection(defaultSection)) }
    var showKinds by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加档案记录" else "修改档案记录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initial == null) {
                    Box {
                        OutlinedButton(onClick = { showKinds = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(memoryKindLabel(kind), modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ExpandMore, null)
                        }
                        DropdownMenu(expanded = showKinds, onDismissRequest = { showKinds = false }) {
                            StoryMemoryKind.entries.filter { it != StoryMemoryKind.Summary }.forEach { option ->
                                DropdownMenuItem(text = { Text(memoryKindLabel(option)) }, onClick = { kind = option; showKinds = false })
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                    placeholder = { Text("写下已经确认的设定、人物信息或计划") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("固定", fontWeight = FontWeight.SemiBold)
                        Text("优先进入故事上下文，并禁止自动覆盖", color = MutedInk, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { Button(onClick = { onSave(kind, content.trim(), pinned) }, enabled = content.isNotBlank()) { Text("保存") } }
    )
}

private fun defaultKindForSection(section: Int): StoryMemoryKind = when (section) {
    1 -> StoryMemoryKind.CharacterProfile
    2 -> StoryMemoryKind.PlotEvent
    else -> StoryMemoryKind.WorldFact
}

private fun recordBelongsToSection(kind: StoryMemoryKind, section: Int): Boolean = when (section) {
    0 -> kind in setOf(StoryMemoryKind.WorldFact, StoryMemoryKind.AuthorPlan)
    1 -> kind in setOf(
        StoryMemoryKind.CharacterProfile,
        StoryMemoryKind.DirectedRelationship,
        StoryMemoryKind.CharacterKnowledge,
        StoryMemoryKind.CurrentState
    )
    2 -> kind in setOf(StoryMemoryKind.PlotEvent, StoryMemoryKind.OpenThread, StoryMemoryKind.Summary)
    else -> false
}

private fun memoryKindLabel(kind: StoryMemoryKind): String = when (kind) {
    StoryMemoryKind.WorldFact -> "世界设定"
    StoryMemoryKind.CharacterProfile -> "人物档案"
    StoryMemoryKind.CurrentState -> "当前状态"
    StoryMemoryKind.DirectedRelationship -> "人物关系"
    StoryMemoryKind.CharacterKnowledge -> "人物认知"
    StoryMemoryKind.PlotEvent -> "剧情事件"
    StoryMemoryKind.OpenThread -> "未完事项"
    StoryMemoryKind.AuthorPlan -> "作者计划"
    StoryMemoryKind.Summary -> "剧情摘要"
}

private fun storyAnnotatedText(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    var cursor = 0
    while (cursor < text.length) {
        val start = text.indexOf('『', cursor)
        if (start < 0) break
        val end = text.indexOf('』', start + 1)
        if (end < 0) break
        addStyle(SpanStyle(color = Accent), start, end + 1)
        cursor = end + 1
    }
}

@Composable
private fun StoryImageStrip(images: List<com.adong.adchat.data.ChatImageAttachment>, onRemove: ((String)->Unit)? = null) {
    if(images.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=14.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        images.forEach { image ->
            Box {
                AsyncImage(model=image.uri,contentDescription=image.name,modifier=Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
                if(onRemove!=null) IconButton(onClick={onRemove(image.id)},modifier=Modifier.align(Alignment.TopEnd).size(28.dp).background(Surface,CircleShape)) {
                    Icon(Icons.Rounded.Close,"移除 ${image.name}",modifier=Modifier.size(16.dp))
                }
            }
        }
    }
}
