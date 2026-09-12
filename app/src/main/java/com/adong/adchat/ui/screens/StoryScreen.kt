package com.adong.adchat.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.TavernPresetConfiguration
import com.adong.adchat.data.TavernPromptSetting
import com.adong.adchat.data.TavernPresetSummary
import com.adong.adchat.data.TavernRegexSetting
import com.adong.adchat.data.story.Story
import com.adong.adchat.data.story.StoryChangeEntry
import com.adong.adchat.data.story.StoryMemoryKind
import com.adong.adchat.data.story.StoryProposal
import com.adong.adchat.data.story.StoryMemoryRecord
import com.adong.adchat.data.story.StoryMessageWithRevision
import com.adong.adchat.data.story.StoryRevisionState
import com.adong.adchat.data.story.StoryWorkspace
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.components.*
import com.adong.adchat.ui.components.AsterIconButton
import com.adong.adchat.ui.components.AsterMark
import com.adong.adchat.ui.story.StoryViewModel
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.launch

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
    var showModelSwitcher by remember(story.id) { mutableStateOf(false) }
    var showTavernPresets by remember { mutableStateOf(false) }
    var showTavernPresetEditor by remember { mutableStateOf(false) }
    val tavernPresetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) storyVm.importTavernPreset(uri)
    }
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
            onModelClick = { showModelSwitcher = true },
            onCreateStory = onCreateStory,
            onHistory = storyVm::openTimelineHistory,
            tavernPresetName = storyVm.activeTavernPresetName,
            onTavernPresets = { showTavernPresets = true },
            historyEnabled = !storyVm.revisionBusy
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(story.id, story.currentTimelineId, storyVm.activeWorkspace) {
                StoryWorkspaceContent(
                    storyVm = storyVm,
                    profile = profile,
                    onOpenSettings = onOpenSettings
                )
            }
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

    if (showModelSwitcher) com.adong.adchat.ui.components.QuickModelSwitcher(
        kind = com.adong.adchat.ui.components.RouteKind.Chat, vm = mainVm,
        routeProfileId = story.profileId, routeModel = story.model,
        onSelectChatModel = { profileId, model -> mainVm.profiles.firstOrNull { it.id == profileId }
            ?.let { storyVm.replaceActiveRoute(it.copy(chatModel = model)) } },
        modelSelectionEnabled = !storyVm.revisionBusy && StoryWorkspace.entries.none { storyVm.isLoading(it) },
        onDismiss = { showModelSwitcher = false }, onManageApis = onOpenSettings)

    if (showTavernPresets) {
        TavernPresetSheet(
            presets = storyVm.tavernPresets,
            activeId = storyVm.activeTavernPresetId,
            regexEnabled = storyVm.tavernRegexEnabled,
            busy = storyVm.tavernPresetBusy || storyVm.revisionBusy || StoryWorkspace.entries.any { storyVm.isLoading(it) },
            error = storyVm.tavernPresetError,
            onSelect = storyVm::selectTavernPreset,
            onRegexEnabled = storyVm::updateTavernRegexEnabled,
            onImport = {
                tavernPresetPicker.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"))
            },
            onDelete = storyVm::deleteTavernPreset,
            onConfigure = {
                showTavernPresets = false
                showTavernPresetEditor = true
            },
            onDismiss = { showTavernPresets = false }
        )
    }

    if (showTavernPresetEditor) {
        storyVm.activeTavernPresetConfiguration?.let { configuration ->
            TavernPresetConfigurationSheet(
                configuration = configuration,
                regexEnabled = storyVm.tavernRegexEnabled,
                busy = storyVm.tavernPresetBusy || storyVm.revisionBusy || StoryWorkspace.entries.any { storyVm.isLoading(it) },
                error = storyVm.tavernPresetError,
                onPromptEnabled = storyVm::setTavernPromptEnabled,
                onRegexScriptEnabled = storyVm::setTavernRegexScriptEnabled,
                onRegexEnabled = storyVm::updateTavernRegexEnabled,
                onReset = storyVm::resetTavernPresetConfiguration,
                onBack = {
                    showTavernPresetEditor = false
                    showTavernPresets = true
                },
                onDismiss = { showTavernPresetEditor = false }
            )
        }
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
            onChooseRoute = { storyVm.closeArchive(); showModelSwitcher = true },
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
    onModelClick: () -> Unit,
    onCreateStory: () -> Unit,
    onHistory: () -> Unit,
    tavernPresetName: String,
    onTavernPresets: () -> Unit,
    historyEnabled: Boolean
) {
    var showActions by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ConversationHeader(
            title = story.title,
            model = profile?.let { "${it.name} · ${story.model}" } ?: "选择故事模型",
            onOpenDrawer = onOpenDrawer, onModelClick = onModelClick, modelUnavailable = profile == null
        ) {
            AsterIconButton(Icons.Rounded.MoreHoriz, "故事选项", { showActions = true })
            AsterIconButton(Icons.Rounded.AddComment, "新建故事", onCreateStory)
        }
        AsterSegmentedControl(
            labels = listOf("讨论", "正文"),
            selectedIndex = StoryWorkspace.entries.indexOf(workspace),
            onSelect = { onWorkspace(StoryWorkspace.entries[it]) },
            modifier = Modifier.padding(start = 48.dp, end = 48.dp, top = 2.dp, bottom = 4.dp)
        )
    }
    if (showActions) AdActionSheet(
        title = "故事选项", subtitle = "管理故事与创作路线",
        actions = listOf(
            AdActionOption("archive", "故事档案", "设定、人物与剧情记忆", Icons.Rounded.FolderOpen),
            AdActionOption("preset", "酒馆预设", "正文 · $tavernPresetName", Icons.Rounded.Tune),
            AdActionOption("history", "历史路线", "查看与切换创作路线", Icons.Rounded.History, enabled = historyEnabled),
            AdActionOption("stories", "切换故事", icon = Icons.Rounded.AutoStories)
        ),
        onAction = { action ->
            showActions = false
            when (action.id) {
                "archive" -> onArchive()
                "preset" -> onTavernPresets()
                "history" -> onHistory()
                "stories" -> onStoryPicker()
            }
        },
        onDismiss = { showActions = false }
    )
}

@Composable
private fun TavernPresetSheet(
    presets: List<TavernPresetSummary>,
    activeId: String?,
    regexEnabled: Boolean,
    busy: Boolean,
    error: String?,
    onSelect: (String?) -> Unit,
    onRegexEnabled: (Boolean) -> Unit,
    onImport: () -> Unit,
    onDelete: (String) -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit
) {
    var deleteCandidate by remember { mutableStateOf<TavernPresetSummary?>(null) }
    AsterOptionsSheet(
        title = "酒馆预设",
        subtitle = "用于故事正文的提示词、参数与正则",
        headerIcon = Icons.Rounded.Tune,
        onDismiss = onDismiss
    ) {
        TavernPresetRow(
            title = "不使用预设",
            subtitle = "仅使用 Aster 的故事规则与档案",
            selected = activeId == null,
            builtIn = false,
            enabled = !busy,
            onClick = { onSelect(null) }
        )
        presets.forEach { preset ->
            TavernPresetRow(
                title = preset.name,
                subtitle = "${preset.enabledPromptCount}/${preset.promptCount} 条提示 · ${preset.enabledRegexCount}/${preset.regexCount} 条正则",
                selected = preset.id == activeId,
                builtIn = preset.builtIn,
                enabled = !busy,
                onClick = { onSelect(preset.id) },
                onDelete = if (preset.builtIn) null else { { deleteCandidate = preset } }
            )
        }
        val active = presets.firstOrNull { it.id == activeId }
        if (active != null) {
            Surface(
                onClick = onConfigure,
                enabled = !busy,
                color = Surface,
                contentColor = Ink,
                border = BorderStroke(1.dp, Hairline),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(AccentSoft),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Tune, null, Modifier.size(20.dp), tint = Accent) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("配置提示词与正则", fontWeight = FontWeight.SemiBold)
                        Text(
                            "逐项选择 ${active.enabledPromptCount}/${active.promptCount} 条提示与 ${active.enabledRegexCount}/${active.regexCount} 条正则",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MutedInk)
                }
            }
        }
        AdToggleCard(
            title = "执行预设正则",
            subtitle = "请求前清理上下文，回复显示时执行美化；不会改写数据库原文",
            checked = regexEnabled,
            onCheckedChange = onRegexEnabled,
            enabled = !busy,
            modifier = Modifier.padding(top = 2.dp)
        )
        if ((active?.helperScriptCount ?: 0) > 0) {
            Surface(color = Color(0xFFFFF1D8), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Security, null, Modifier.size(19.dp), tint = MutedInk)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "预设中的 ${active?.helperScriptCount} 个 Tavern Helper 脚本已随文件保留，但不会执行第三方 JavaScript。正则生成的 HTML 会关闭脚本与网络后预览。",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        error?.let {
            Surface(color = DangerSoft, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(12.dp), color = Danger, style = MaterialTheme.typography.labelMedium)
            }
        }
        Button(
            onClick = onImport,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.FileUpload, null, Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "正在处理…" else "导入酒馆 JSON 预设")
        }
        Text(
            "兼容 prompt_order、角色顺序、setvar/getvar、random、roll、lastUserMessage，以及酒馆正则的角色、深度、全局标志和显示/请求范围。",
            color = MutedInk,
            style = MaterialTheme.typography.labelSmall
        )
    }
    deleteCandidate?.let { preset ->
        AdConfirmDialog(
            title = "删除 ${preset.name}？",
            message = "将删除这台设备上导入的预设文件，故事正文不会被删除。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            icon = Icons.Rounded.DeleteOutline,
            destructive = true,
            onConfirm = { onDelete(preset.id); deleteCandidate = null },
            onDismiss = { deleteCandidate = null }
        )
    }
}

@Composable
private fun TavernPresetRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    builtIn: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        color = if (selected) AccentSoft else Surface,
        contentColor = Ink,
        border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = .4f) else Hairline),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 14.dp, end = 5.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (selected) Color.White.copy(alpha = .7f) else Canvas),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (selected) Icons.Rounded.Check else Icons.Rounded.Description, null, Modifier.size(19.dp), tint = if (selected) Accent else MutedInk)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (builtIn) {
                        Spacer(Modifier.width(7.dp))
                        Text("内置", color = Accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(subtitle, color = MutedInk, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            onDelete?.let { delete ->
                IconButton(onClick = delete, enabled = enabled) {
                    Icon(Icons.Rounded.DeleteOutline, "删除预设", Modifier.size(19.dp), tint = Danger)
                }
            }
        }
    }
}

private enum class TavernConfigurationSection { Prompts, Regex }
private enum class TavernConfigurationFilter { All, Enabled, Disabled, Modified }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TavernPresetConfigurationSheet(
    configuration: TavernPresetConfiguration,
    regexEnabled: Boolean,
    busy: Boolean,
    error: String?,
    onPromptEnabled: (String, Boolean) -> Unit,
    onRegexScriptEnabled: (Int, Boolean) -> Unit,
    onRegexEnabled: (Boolean) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    var section by remember(configuration.id) { mutableStateOf(TavernConfigurationSection.Prompts) }
    var filter by remember(configuration.id, section) { mutableStateOf(TavernConfigurationFilter.All) }
    var query by remember(configuration.id, section) { mutableStateOf("") }
    var inspectedPrompt by remember { mutableStateOf<TavernPromptSetting?>(null) }
    var inspectedRegex by remember { mutableStateOf<TavernRegexSetting?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val needle = query.trim()
    val visiblePrompts = remember(configuration.prompts, needle, filter) {
        configuration.prompts.filter { prompt ->
            (needle.isBlank() || prompt.name.contains(needle, ignoreCase = true) ||
                prompt.identifier.contains(needle, ignoreCase = true) || prompt.content.contains(needle, ignoreCase = true)) &&
                filter.matches(prompt.enabled, prompt.modified)
        }
    }
    val visibleRegex = remember(configuration.regexScripts, needle, filter) {
        configuration.regexScripts.filter { script ->
            (needle.isBlank() || script.name.contains(needle, ignoreCase = true) ||
                script.findRegex.contains(needle, ignoreCase = true)) &&
                filter.matches(script.enabled, script.modified)
        }
    }
    val maximumHeight = LocalConfiguration.current.screenHeightDp.dp * .93f
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Canvas,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 42.dp, color = Hairline) }
    ) {
        Column(
            Modifier.fillMaxWidth().height(maximumHeight).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Surface, contentColor = MutedInk)
                ) { Icon(Icons.Rounded.ArrowBack, "返回预设列表") }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(configuration.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("提示词与正则配置", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Surface, contentColor = MutedInk)
                ) { Icon(Icons.Rounded.Close, "关闭") }
            }

            Surface(color = Surface, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Hairline)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "提示词 ${configuration.enabledPromptCount}/${configuration.prompts.size} · 正则 ${configuration.enabledRegexCount}/${configuration.regexScripts.size}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (configuration.modifiedCount == 0) "正在使用文件内的默认启用与停用状态"
                            else "已修改 ${configuration.modifiedCount} 项；未修改项仍跟随文件默认值",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (configuration.modifiedCount > 0) TextButton(onClick = { confirmReset = true }, enabled = !busy) {
                        Icon(Icons.Rounded.Restore, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("恢复默认")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = section == TavernConfigurationSection.Prompts,
                    onClick = { section = TavernConfigurationSection.Prompts },
                    label = { Text("提示词 ${configuration.prompts.size}") },
                    leadingIcon = { Icon(Icons.Rounded.Notes, null, Modifier.size(17.dp)) }
                )
                FilterChip(
                    selected = section == TavernConfigurationSection.Regex,
                    onClick = { section = TavernConfigurationSection.Regex },
                    label = { Text("正则 ${configuration.regexScripts.size}") },
                    leadingIcon = { Icon(Icons.Rounded.Code, null, Modifier.size(17.dp)) }
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = if (query.isBlank()) null else {{
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清空搜索") }
                }},
                placeholder = { Text(if (section == TavernConfigurationSection.Prompts) "搜索提示词名称或内容" else "搜索正则名称或表达式") },
                shape = RoundedCornerShape(15.dp),
                modifier = Modifier.fillMaxWidth()
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    TavernConfigurationFilter.All to "全部",
                    TavernConfigurationFilter.Enabled to "已启用",
                    TavernConfigurationFilter.Disabled to "已停用",
                    TavernConfigurationFilter.Modified to "已修改"
                ).forEach { (value, label) ->
                    FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) })
                }
            }

            error?.let {
                Surface(color = DangerSoft, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(11.dp), color = Danger, style = MaterialTheme.typography.labelMedium)
                }
            }

            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (section == TavernConfigurationSection.Regex) {
                    item(key = "regex-master") {
                        AdToggleCard(
                            title = "执行预设正则",
                            subtitle = if (regexEnabled) "总开关已开启；下方逐条状态会生效" else "总开关已关闭；逐条选择会保留但暂不执行",
                            checked = regexEnabled,
                            onCheckedChange = onRegexEnabled,
                            enabled = !busy
                        )
                    }
                }
                if (section == TavernConfigurationSection.Prompts) {
                    items(visiblePrompts, key = { it.identifier }) { prompt ->
                        TavernPromptSettingRow(
                            setting = prompt,
                            enabled = !busy,
                            onInspect = { inspectedPrompt = prompt },
                            onEnabled = { onPromptEnabled(prompt.identifier, it) }
                        )
                    }
                    if (visiblePrompts.isEmpty()) item { TavernConfigurationEmptyState() }
                } else {
                    items(visibleRegex, key = { "${it.index}:${it.id}" }) { script ->
                        TavernRegexSettingRow(
                            setting = script,
                            enabled = !busy,
                            onInspect = { inspectedRegex = script },
                            onEnabled = { onRegexScriptEnabled(script.index, it) }
                        )
                    }
                    if (visibleRegex.isEmpty()) item { TavernConfigurationEmptyState() }
                }
            }
        }
    }

    inspectedPrompt?.let { prompt ->
        TavernTextPreviewDialog(
            title = prompt.name,
            meta = "${prompt.role.uppercase()} · ${if (prompt.defaultEnabled) "默认启用" else "默认停用"}${if (prompt.modified) " · 已修改" else ""}",
            sections = listOf("提示词内容" to prompt.content),
            onDismiss = { inspectedPrompt = null }
        )
    }
    inspectedRegex?.let { script ->
        TavernTextPreviewDialog(
            title = script.name,
            meta = "${script.scopeLabel()} · ${if (script.defaultEnabled) "默认启用" else "默认停用"}${if (script.modified) " · 已修改" else ""}",
            sections = listOf("查找表达式" to script.findRegex, "替换内容" to script.replaceString),
            onDismiss = { inspectedRegex = null }
        )
    }
    if (confirmReset) {
        AdConfirmDialog(
            title = "恢复预设默认？",
            message = "提示词和正则的逐项选择将恢复为 JSON 文件中定义的启用与停用状态。全局正则开关不受影响。",
            confirmLabel = "恢复默认",
            dismissLabel = "取消",
            icon = Icons.Rounded.Restore,
            onConfirm = { onReset(); confirmReset = false },
            onDismiss = { confirmReset = false }
        )
    }
}

@Composable
private fun TavernPromptSettingRow(
    setting: TavernPromptSetting,
    enabled: Boolean,
    onInspect: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    TavernSettingRow(
        title = setting.name,
        subtitle = buildString {
            append(setting.role.uppercase())
            if (setting.marker) append(" · 占位标记")
            append(if (setting.defaultEnabled) " · 默认启用" else " · 默认停用")
            if (setting.modified) append(" · 已修改")
        },
        checked = setting.enabled,
        defaultEnabled = setting.defaultEnabled,
        enabled = enabled,
        onInspect = onInspect,
        onEnabled = onEnabled
    )
}

@Composable
private fun TavernRegexSettingRow(
    setting: TavernRegexSetting,
    enabled: Boolean,
    onInspect: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    TavernSettingRow(
        title = setting.name,
        subtitle = "${setting.scopeLabel()}${setting.depthLabel()} · ${if (setting.defaultEnabled) "默认启用" else "默认停用"}${if (setting.modified) " · 已修改" else ""}",
        checked = setting.enabled,
        defaultEnabled = setting.defaultEnabled,
        enabled = enabled,
        onInspect = onInspect,
        onEnabled = onEnabled
    )
}

@Composable
private fun TavernSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    defaultEnabled: Boolean,
    enabled: Boolean,
    onInspect: () -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    Surface(
        color = if (checked != defaultEnabled) AccentSoft.copy(alpha = .52f) else Surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (checked != defaultEnabled) Accent.copy(alpha = .3f) else Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.clickable(enabled = enabled, onClick = onInspect).padding(start = 14.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MutedInk, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(9.dp))
            Switch(checked = checked, onCheckedChange = onEnabled, enabled = enabled)
        }
    }
}

@Composable
private fun TavernConfigurationEmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.SearchOff, null, tint = MutedInk)
        Spacer(Modifier.height(8.dp))
        Text("没有符合条件的项目", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TavernTextPreviewDialog(
    title: String,
    meta: String,
    sections: List<Pair<String, String>>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta, color = MutedInk, style = MaterialTheme.typography.labelSmall)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sections.forEach { (label, value) ->
                    Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(
                            value.take(MAX_TAVERN_PREVIEW_CHARS).ifBlank { "（空）" },
                            color = Ink,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (value.length > MAX_TAVERN_PREVIEW_CHARS) {
                        Text("内容较长，此处仅预览前 $MAX_TAVERN_PREVIEW_CHARS 个字符。发送请求时仍使用完整内容。", color = MutedInk, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun TavernConfigurationFilter.matches(enabled: Boolean, modified: Boolean): Boolean = when (this) {
    TavernConfigurationFilter.All -> true
    TavernConfigurationFilter.Enabled -> enabled
    TavernConfigurationFilter.Disabled -> !enabled
    TavernConfigurationFilter.Modified -> modified
}

private fun TavernRegexSetting.scopeLabel(): String = when {
    promptOnly && !markdownOnly -> "发送给模型"
    markdownOnly && !promptOnly -> "仅显示"
    else -> "请求与显示"
}

private fun TavernRegexSetting.depthLabel(): String {
    val roles = buildList {
        if (1 in placement) add("用户")
        if (2 in placement) add("助手")
    }.joinToString("/").ifBlank { "无角色" }
    val depth = when {
        minDepth != null && minDepth >= 0 && maxDepth != null && maxDepth >= 0 -> " · 深度 $minDepth–$maxDepth"
        minDepth != null && minDepth >= 0 -> " · 深度 ≥$minDepth"
        maxDepth != null && maxDepth >= 0 -> " · 深度 ≤$maxDepth"
        else -> ""
    }
    return " · $roles$depth"
}

private const val MAX_TAVERN_PREVIEW_CHARS = 40_000

@OptIn(ExperimentalLayoutApi::class)
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
    val imeInsets = WindowInsets.ime
    val imeAnimationTarget = WindowInsets.imeAnimationTarget
    var composerFocused by remember { mutableStateOf(false) }
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
    val hasStandaloneThinking = loading && !lastIsStreamingAssistant
    val retryableMessageId = messages.lastOrNull()?.takeIf {
        it.message.role == "assistant" && it.revision.state == StoryRevisionState.Interrupted
    }?.message?.id
    // Match ordinary chat: always keep a real trailing LazyColumn item that can be
    // anchored during IME animation. Scrolling to lastIndex only aligns the message
    // itself and does not keep the conversation bottom attached to the composer.
    val bottomItemIndex = messages.size + if (hasStandaloneThinking) 1 else 0

    DisposableEffect(workspace) {
        onDispose {
            storyVm.saveScroll(workspace, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, savedState.timelineId)
        }
    }
    LaunchedEffect(dragging) {
        if (dragging) autoFollow = false
        else if (!listState.canScrollForward) autoFollow = true
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.revision?.content?.length, loading) {
        if (autoFollow && !dragging && (messages.isNotEmpty() || loading)) {
            runCatching { listState.animateScrollToItem(bottomItemIndex) }
        }
    }
    LaunchedEffect(
        composerFocused,
        targetStory.id,
        targetStory.currentTimelineId,
        workspace,
        messages.size,
        loading,
        lastIsStreamingAssistant
    ) {
        if (!composerFocused) return@LaunchedEffect
        autoFollow = true

        snapshotFlow {
            imeInsets.getBottom(composerDensity) to imeAnimationTarget.getBottom(composerDensity)
        }.collect { (imeBottom, imeTargetBottom) ->
            if (messages.isNotEmpty() || loading) {
                // Same contract as ordinary chat: the list follows every IME inset
                // frame and anchors to the trailing spacer, so content and composer
                // move as one surface while the keyboard opens/closes.
                runCatching { listState.scrollToItem(bottomItemIndex) }
            }

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
            StoryWorkspaceEmpty(workspace, Modifier.fillMaxSize().padding(bottom = composerHeight)) { prompt ->
                val draft = storyVm.draft(workspace)
                storyVm.updateDraft(if (draft.isBlank()) prompt else "$draft\n\n$prompt", workspace)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = composerHeight + 20.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp)
            ) {
                itemsIndexed(messages, key = { _, row -> row.message.id }) { index, row ->
                    val assistant = row.message.role == "assistant"
                    val pending = if (assistant && workspace == StoryWorkspace.Discussion) {
                        storyVm.archiveProposals.count { it.sourceRevisionId == row.revision.id }
                    } else 0
                    val display = remember(
                        row.revision.id,
                        row.revision.content,
                        row.revision.state,
                        storyVm.activeTavernPresetId,
                        storyVm.tavernRegexEnabled,
                        index,
                        messages.size
                    ) {
                        if (row.revision.state == StoryRevisionState.Streaming) {
                            com.adong.adchat.data.TavernRegexOutput(row.revision.content, 0, emptyList())
                        } else storyVm.tavernDisplay(
                            content = row.revision.content,
                            role = row.message.role,
                            depth = messages.lastIndex - index,
                            workspace = workspace
                        )
                    }
                    StoryMessageItem(
                        row = row,
                        displayContent = display.structuredText(),
                        regexHtml = display.containsHtml,
                        workspace = workspace,
                        pendingCount = pending,
                        actionsEnabled = !storyVm.revisionBusy && StoryWorkspace.entries.none { storyVm.isLoading(it) },
                        regenerateEnabled = row.message.id == retryableMessageId && profile != null &&
                            !storyVm.revisionBusy && StoryWorkspace.entries.none { storyVm.isLoading(it) },
                        onRegenerate = { profile?.let { storyVm.regenerateInterrupted(it, row) } },
                        onOpenDiscussionAction = { storyVm.openDiscussionAction(row) },
                        onOpenPendingCandidates = storyVm::openPendingCandidates,
                        onOpenRevision = { storyVm.openRevisionEditor(row) },
                        onDetailsExpanded = { messageId ->
                            val messageIndex = messages.indexOfFirst { it.message.id == messageId }
                            if (messageIndex >= 0) {
                                autoFollow = false
                                scope.launch {
                                    listState.revealConversationDetails(messageIndex,
                                        with(composerDensity) { (composerHeight + 12.dp).toPx() })
                                }
                            }
                        }
                    )
                }
                if (hasStandaloneThinking) {
                    item(key = "story-thinking-${workspace.name}") {
                        ConversationThinkingIndicator()
                    }
                }
                item(key = "story-bottom-spacer") { Spacer(Modifier.height(4.dp)) }
            }
        }

        ConversationReadingVeil(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(composerHeight))

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

        ConversationJumpToBottom(
            visible = listState.canScrollForward && storyVm.error(workspace) == null,
            loading = loading,
            onClick = { autoFollow = true; scope.launch { listState.animateScrollToItem(bottomItemIndex) } },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = composerHeight + 18.dp)
        )

        StoryComposer(
            skillScope = "aster-story-${storyVm.activeStoryId}-${workspace.dbValue}",
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
                if (profile != null) storyVm.send(profile, workspace) else storyVm.openArchive()
                autoFollow = true
            },
            onStop = { storyVm.stop(workspace) },
            onFocusChange = { composerFocused = it },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().onSizeChanged { composerHeight=with(composerDensity) { it.height.toDp() } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoryMessageItem(
    row: StoryMessageWithRevision,
    displayContent: String,
    regexHtml: Boolean,
    workspace: StoryWorkspace,
    pendingCount: Int,
    actionsEnabled: Boolean,
    regenerateEnabled: Boolean,
    onRegenerate: () -> Unit,
    onOpenDiscussionAction: () -> Unit,
    onOpenPendingCandidates: () -> Unit,
    onOpenRevision: () -> Unit,
    onDetailsExpanded: (String) -> Unit
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
                Surface(color = SurfaceInset, contentColor = Ink, shape = RoundedCornerShape(22.dp, 22.dp, 8.dp, 22.dp)) {
                    Column(Modifier.padding(7.dp)) {
                        if (row.revision.attachments.isNotEmpty()) ConversationImages(row.revision.attachments)
                        if (row.revision.content.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    text = storyAnnotatedText(displayContent),
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Ink
                                )
                            }
                        }
                    }
                }
            } else {
                if (!waitingForFirstToken) {
                    ConversationAuthor()
                }
                if (waitingForFirstToken) {
                    ConversationThinkingIndicator()
                } else {
                    StructuredMessageText(
                        content = displayContent,
                        streaming = row.revision.state == StoryRevisionState.Streaming,
                        error = false,
                        htmlScriptsAllowed = !regexHtml
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
                if (row.revision.state != StoryRevisionState.Streaming &&
                    (row.revision.content.isNotBlank() || regenerateEnabled)) {
                    Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (row.revision.content.isNotBlank()) ConversationCopyAction(row.revision.content)
                            if (row.revision.state == StoryRevisionState.Interrupted && regenerateEnabled) {
                                ConversationMessageAction(
                                    icon = Icons.Rounded.Refresh,
                                    label = "重新生成",
                                    onClick = onRegenerate
                                )
                            }
                            if (hasDetails) {
                                ConversationMessageAction(
                                    icon = Icons.Rounded.MoreHoriz,
                                    label = if (showDetails) "收起" else "详情",
                                    onClick = {
                                        val expanding = !showDetails
                                        showDetails = expanding
                                        if (expanding) onDetailsExpanded(row.message.id)
                                    }
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StoryComposer(
    skillScope: String,
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
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAttachments by remember { mutableStateOf(false) }
    ConversationComposer(
        value = value, attachments = attachments, loading = loading, attachmentLoading = attachmentBusy,
        onValueChange = onValueChange, onOptionsClick = { showAttachments = true }, onRemoveImage = onRemoveImage,
        onSend = onSend, onStop = onStop, onFocusChange = onFocusChange, modifier = modifier,
        configureRequired = !routeAvailable, testTag = "story",
        placeholder = if (!routeAvailable) "先选择故事使用的模型" else if (workspace == StoryWorkspace.Discussion)
            "讨论设定、人物或下一步…" else "告诉 Aster 接下来发生什么…"
    )

    if (showAttachments) {
        AsterOptionsSheet(
            title = "输入选项",
            subtitle = if (workspace == StoryWorkspace.Discussion) "添加参考资料，继续讨论设定" else "添加参考资料，继续创作正文",
            onDismiss = { showAttachments = false }
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ConversationSheetAction(Icons.Rounded.AddPhotoAlternate, "图片",
                    !loading && !attachmentBusy && attachments.size < 4,
                    { showAttachments = false; onPickImages() }, Modifier.weight(1f), "${attachments.size} / 4 张")
                ConversationSheetAction(Icons.Rounded.AttachFile, "文件", !loading && !attachmentBusy,
                    { showAttachments = false; onPickDocument() }, Modifier.weight(1f), "文本 / DOCX / PDF")
            }
            SkillPickerEntry(skillScope, enabled = !loading && !attachmentBusy)
        }
    }
}

@Composable
internal fun StoryWorkspaceEmpty(workspace: StoryWorkspace, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val discussion = workspace == StoryWorkspace.Discussion
    ConversationWelcome(
        title = if (discussion) "故事，从想象开始。" else "让故事，继续发生。",
        subtitle = if (discussion) "聊设定、人物与文风。\n决定采用后，再写进故事。" else "给出剧情方向或人物行动。\n让这一幕从这里展开。",
        label = if (discussion) "讨论设定" else "创作正文",
        starters = if (discussion) listOf(
            ConversationStarter("构建设定", "为故事搭一个世界", Icons.Rounded.Public, "我想先讨论这个故事的世界观，请和我一起完善设定。"),
            ConversationStarter("打磨人物", "动机、性格与关系", Icons.Rounded.PeopleOutline, "我想讨论一个人物的性格、动机和人物关系，暂时不要开始正文。"),
            ConversationStarter("寻找文风", "确定叙述的声音", Icons.Rounded.EditNote, "先和我讨论故事的叙述视角、文风和节奏，暂时不要写正文。"),
            ConversationStarter("梳理走向", "讨论下一幕的可能", Icons.Rounded.Explore, "我想讨论接下来的剧情走向，先比较几种可能，不要把讨论当作已发生的剧情。")
        ) else listOf(
            ConversationStarter("写下开场", "从一个场景切入", Icons.Rounded.AutoStories, "请依据已确认的设定写故事开场。我希望这一幕这样展开："),
            ConversationStarter("推进剧情", "给故事一个方向", Icons.Rounded.NorthEast, "接下来的剧情方向是："),
            ConversationStarter("人物对话", "让角色开口说话", Icons.Rounded.Forum, "我想创作一段人物对话，参与的人物和情境是："),
            ConversationStarter("描写场景", "光线、声音与氛围", Icons.Rounded.Landscape, "请描写这个场景，地点、氛围和关键细节是：")
        ), onSelect = onSelect, modifier = modifier
    )
}

@Composable
private fun StoryEmptyState(onOpenDrawer: () -> Unit, onCreateStory: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            AsterIconButton(Icons.Rounded.Menu, "打开侧栏", onOpenDrawer)
            Text("故事", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        }
        ConversationWelcome(
            title = "写一个会记得的故事。", subtitle = "从设定开始，陪人物走下去。", label = "故事创作",
            starters = emptyList(), onSelect = {}, modifier = Modifier.weight(1f),
            footer = {
                Spacer(Modifier.height(28.dp))
                Button(onClick = onCreateStory, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("新建故事")
                }
            }
        )
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
    onChooseRoute: () -> Unit,
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
                            modifier = Modifier.fillMaxWidth().clickable { onChooseRoute() }
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
