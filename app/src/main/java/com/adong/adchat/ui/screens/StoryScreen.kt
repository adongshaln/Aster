package com.adong.adchat.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedState.firstVisibleIndex.coerceAtMost(messages.lastIndex.coerceAtLeast(0)),
        initialFirstVisibleItemScrollOffset = savedState.firstVisibleOffset.coerceAtLeast(0)
    )
    val scope = rememberCoroutineScope()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var autoFollow by remember { mutableStateOf(true) }
    val loading = storyVm.isLoading(workspace)

    DisposableEffect(workspace) {
        onDispose {
            storyVm.saveScroll(workspace, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, savedState.timelineId)
        }
    }
    LaunchedEffect(dragging, listState.canScrollForward) {
        if (dragging) autoFollow = !listState.canScrollForward
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.revision?.content?.length, loading) {
        if (messages.isNotEmpty() && autoFollow && !dragging) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    storyVm.revisionTarget?.let { target ->
        var revisedText by remember(target.revision.id) { mutableStateOf(target.revision.content) }
        AlertDialog(
            onDismissRequest = storyVm::closeRevisionEditor,
            title = { Text("修订正文") },
            text = {
                Column(Modifier.heightIn(max = 480.dp)) {
                    Text("末尾正文可保存为新版本。较早正文请从这里另写：旧后续留在历史路线，新路线从生成前资料快照继续。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = revisedText, onValueChange = { revisedText = it },
                        enabled = !storyVm.revisionBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp))
                    storyVm.revisionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { storyVm.saveProseRevision(revisedText, fork = true) },
                        enabled = !storyVm.revisionBusy && revisedText.isNotBlank() && revisedText.trim() != target.revision.content) {
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
                TextButton(onClick = { storyVm.saveProseRevision(revisedText) },
                    enabled = !storyVm.revisionBusy && revisedText.isNotBlank() && revisedText.trim() != target.revision.content) {
                    Text(if (storyVm.revisionBusy) "保存中…" else "保存新版本")
                }
            },
            dismissButton = { TextButton(onClick = storyVm::closeRevisionEditor, enabled = !storyVm.revisionBusy) { Text("关闭") } }
        )
    }

    Box(Modifier.fillMaxSize().imePadding()) {
        if (messages.isEmpty()) {
            StoryWorkspaceEmpty(workspace, Modifier.fillMaxSize().padding(bottom = 92.dp))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 124.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                items(messages, key = { it.message.id }) { row ->
                    Column {
                        StoryMessageItem(row)
                        if (workspace == StoryWorkspace.Prose &&
                            row.message.role == "assistant" && row.revision.state != StoryRevisionState.Streaming) {
                            TextButton(onClick = { storyVm.openRevisionEditor(row) },
                                enabled = !storyVm.revisionBusy && StoryWorkspace.entries.none { storyVm.isLoading(it) }) {
                                Text("修订 / 版本", color = MutedInk)
                            }
                        }
                    }
                }
            }
        }

        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(92.dp).background(
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, bottom = 92.dp)
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
                    if (messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(messages.lastIndex) }
                },
                shape = CircleShape,
                color = Surface,
                contentColor = Accent,
                border = BorderStroke(1.dp, Hairline),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 96.dp)
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, "回到底部", Modifier.padding(12.dp).size(20.dp))
            }
        }

        StoryComposer(
            value = storyVm.draft(workspace),
            workspace = workspace,
            loading = loading,
            routeAvailable = profile != null,
            onValueChange = { storyVm.updateDraft(it, workspace) },
            onSend = {
                if (profile != null) storyVm.send(profile, workspace) else onOpenSettings()
                autoFollow = true
            },
            onStop = { storyVm.stop(workspace) },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
        )
    }
}

@Composable
private fun StoryMessageItem(row: StoryMessageWithRevision) {
    val user = row.message.role == "user"
    val context = LocalContext.current
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
                    StructuredMessageText(
                        content = row.revision.content,
                        streaming = row.revision.state == StoryRevisionState.Streaming,
                        error = false
                    )
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
                        TextButton(
                            onClick = {
                                context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                    android.content.ClipData.newPlainText("Aster Story", row.revision.content)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("复制", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
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
            AsterMark(
                Modifier.size(28.dp).graphicsLayer {
                    translationY = jumpPx
                    rotationZ = rotation
                },
                tint = Accent
            )
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text("Aster 正在思考", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Text("正在组织回答…", color = MutedInk, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StoryComposer(
    value: String,
    workspace: StoryWorkspace,
    loading: Boolean,
    routeAvailable: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        color = Surface.copy(alpha = .97f),
        shape = RoundedCornerShape(31.dp),
        border = BorderStroke(1.dp, if (focused) Accent.copy(alpha = .35f) else Hairline),
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.defaultMinSize(minHeight = if (focused) 92.dp else 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = if (focused) Alignment.Bottom else Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) {
                Icon(
                    if (workspace == StoryWorkspace.Discussion) Icons.Rounded.Forum else Icons.Rounded.AutoStories,
                    null,
                    Modifier.size(19.dp),
                    tint = Accent
                )
            }
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 28.dp, max = 128.dp).onFocusChanged { focused = it.isFocused },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Accent),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                if (!routeAvailable) "先选择故事使用的模型" else if (workspace == StoryWorkspace.Discussion) "讨论设定、人物或下一步…" else "告诉 Aster 接下来发生什么…",
                                color = MutedInk,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        inner()
                    }
                }
            )
            Spacer(Modifier.width(9.dp))
            FilledIconButton(
                onClick = if (loading) onStop else onSend,
                enabled = loading || value.isNotBlank() || !routeAvailable,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Night, contentColor = WarmWhite),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(if (loading) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward, if (loading) "停止" else "发送")
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryArchiveSheet(
    story: Story,
    records: List<StoryMemoryRecord>,
    conflicts: List<com.adong.adchat.data.story.StoryConflictEntry>,
    onResolveConflict: (com.adong.adchat.data.story.StoryConflictEntry, Boolean) -> Unit,
    proposals: List<StoryProposal>,
    memoryStatus: String,
    changes: List<StoryChangeEntry>,
    changeError: String?,
    undoBusy: Boolean,
    onUndo: (String, Boolean) -> Unit,
    onDecide: (String, Boolean) -> Unit,
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
    var section by remember { mutableIntStateOf(0) }
    var viewingChange by remember { mutableStateOf<StoryChangeEntry?>(null) }
    viewingChange?.let { change ->
        AlertDialog(onDismissRequest = { viewingChange = null }, title = { Text(change.title) },
            text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("记忆版本 ${change.version}", style = MaterialTheme.typography.labelMedium)
                if (change.before.isNotBlank()) { Text("变更前", fontWeight = FontWeight.SemiBold); Text(change.before) }
                if (change.after.isNotBlank()) { Text("变更后", fontWeight = FontWeight.SemiBold); Text(change.after) }
                if (change.source.isNotBlank()) { Text("来源正文 / 讨论", fontWeight = FontWeight.SemiBold); Text(change.source) }
                if (change.note.isNotBlank()) Text(change.note, color = MutedInk)
            } }, confirmButton = { TextButton(onClick = { viewingChange = null }) { Text("关闭") } })
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
                if (section != 3) TextButton(onClick = { adding = true }) {
                    Icon(Icons.Rounded.Add, null)
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
                        TextButton(onClick = onRetryMemory, enabled = story.automaticMemoryEnabled) { Text("重试失败项") }
                    } }
                    item { ArchiveInfoCard("记忆版本", story.memoryVersion.toString()) }
                    changeError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    if (conflicts.isNotEmpty()) item { Text("状态冲突 · ${conflicts.size}", style = MaterialTheme.typography.titleSmall) }
                    items(conflicts, key = { "conflict-${it.id}" }) { entry ->
                        var showSources by remember(entry.id) { mutableStateOf(false) }
                        Surface(color = Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Hairline)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(entry.conflict.description, style = MaterialTheme.typography.bodyMedium)
                                Text("选择后停用另一条资料，并保留固定约束；决定可在最近变更中整体撤销。",
                                    color = MutedInk, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                                TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "收起来源" else "查看双方来源") }
                                if (showSources) {
                                    Text("原状态来源", fontWeight = FontWeight.SemiBold)
                                    Text(entry.earlierSource, style = MaterialTheme.typography.bodySmall)
                                    Text("新状态来源", fontWeight = FontWeight.SemiBold)
                                    Text(entry.latestSource, style = MaterialTheme.typography.bodySmall)
                                }
                                Row {
                                    TextButton(onClick = { onResolveConflict(entry, false) }, enabled = !undoBusy) { Text("保留原状态") }
                                    TextButton(onClick = { onResolveConflict(entry, true) }, enabled = !undoBusy) { Text("采用新状态") }
                                }
                            }
                        }
                    }
                    if (proposals.isNotEmpty()) item { Text("待确认 · ${proposals.size}", style = MaterialTheme.typography.titleSmall) }
                    items(proposals, key = { "proposal-${it.id}" }) { proposal ->
                        Surface(color = Surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Hairline)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("待确认候选", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                                Text(proposal.content, modifier = Modifier.padding(vertical = 8.dp))
                                Row {
                                    TextButton(onClick = { onDecide(proposal.id, true) }) { Text("采用") }
                                    TextButton(onClick = { onDecide(proposal.id, false) }) { Text("废弃") }
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
                                Row {
                                    TextButton(onClick = { viewingChange = change }) { Text("查看详情") }
                                    if (change.canUndo) TextButton(onClick = { onUndo(change.id, change.batch) }, enabled = !undoBusy) { Text(if (change.batch) "整体撤销" else "撤销此改动") }
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
            confirmButton = { TextButton(onClick = { showRouteMenu = false }) { Text("关闭") } }
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
