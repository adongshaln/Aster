@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.adong.adchat.ui.rp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.TavernPreset
import com.adong.adchat.data.TavernPresetSummary
import com.adong.adchat.data.rp.RpCardExtensions
import com.adong.adchat.data.rp.RpConfig
import com.adong.adchat.data.rp.RpPresentation
import com.adong.adchat.data.rp.RpSession
import com.adong.adchat.data.rp.RpSource
import com.adong.adchat.data.story.StoryCharacterCard
import com.adong.adchat.ui.components.AdConfirmDialog
import com.adong.adchat.ui.components.AsterEmptyState
import com.adong.adchat.ui.components.AsterIconButton
import com.adong.adchat.ui.components.AsterOptionsSheet
import com.adong.adchat.ui.components.AsterPillButton
import com.adong.adchat.ui.components.AsterPrimaryButton
import com.adong.adchat.ui.components.AsterSegmentedControl
import com.adong.adchat.ui.components.ComposerToolToggle
import com.adong.adchat.ui.components.ConversationComposer
import com.adong.adchat.ui.components.ConversationCopyAction
import com.adong.adchat.ui.components.ConversationMessageAction
import com.adong.adchat.ui.components.ConversationSheetAction
import com.adong.adchat.ui.components.OfflineArtifactView
import com.adong.adchat.ui.components.StoryProseContent
import com.adong.adchat.ui.components.TavernHelperAssets
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.ui.graphics.graphicsLayer
import com.adong.adchat.data.TavernRegexScript
import com.adong.adchat.data.rp.RpAuthorsNote
import com.adong.adchat.data.rp.RpGenerationType
import com.adong.adchat.data.rp.RpHelperScript
import com.adong.adchat.data.rp.RpMessage
import com.adong.adchat.data.rp.RpNotePosition
import com.adong.adchat.data.rp.RpPersona
import com.adong.adchat.data.rp.RpPersonaPosition
import com.adong.adchat.data.rp.helper.RpTavernHelper
import com.adong.adchat.data.rp.prompt.RpMacroContext
import com.adong.adchat.ui.components.HtmlPreviewDocument
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VisibilityOff
import com.adong.adchat.ui.components.AdActionOption
import com.adong.adchat.ui.components.AdActionSheet
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onSizeChanged
import com.adong.adchat.ui.components.isCloseToBottom
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.adong.adchat.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OPENING_HTML = Regex("<(?:div|span|style|html|body|details|section|article|table|img|iframe)\\b", RegexOption.IGNORE_CASE)

/**
 * Unified RP surface: one session = a config panel (card import or plain-text setup) plus a chat.
 * Reuses the main chat composer and structured renderer; nothing here runs at app startup.
 */
@Composable
fun RpScreen(
    vm: RpViewModel,
    mainVm: com.adong.adchat.ui.MainViewModel,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val session = vm.activeSession
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var configOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    var presetPickerOpen by remember { mutableStateOf(false) }
    var personaOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var worldOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    var editResendCandidate by remember { mutableStateOf<RpMessage?>(null) }
    var renameTarget by remember { mutableStateOf<RpSession?>(null) }
    var draftConfig by remember { mutableStateOf<RpConfig?>(null) }
    var deleteCandidate by remember { mutableStateOf<RpSession?>(null) }
    var editTarget by remember { mutableStateOf<RpMessage?>(null) }
    var menuTarget by remember { mutableStateOf<RpMessage?>(null) }
    var deleteMessageCandidate by remember { mutableStateOf<RpMessage?>(null) }
    var consentDismissed by remember(session?.id, session?.cardKey) { mutableStateOf(false) }
    val userName = vm.cachedUserName
    val helper = session?.let { s -> remember(s.id) { vm.helperFor(s) } }
    val listState = rememberLazyListState()

    val cardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("无法读取所选文件")
                draftConfig = vm.parseCardAsync(bytes)
            }.onFailure {
                android.widget.Toast.makeText(context, "角色卡导入失败：${it.message ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val presetFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "preset.json"
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("无法读取所选文件")
                val preset = vm.importPreset(bytes, name)
                draftConfig = draftConfig?.copy(presetId = preset.id)
                android.widget.Toast.makeText(context, "已导入并选用预设「${preset.name}」", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "预设导入失败：${it.message ?: "不是有效的酒馆预设"}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openConfig() {
        draftConfig = vm.activeSession?.config
        configOpen = true
    }

    fun newSession() {
        vm.requestNewSession()
    }

    LaunchedEffect(vm.setupRequest) { if (vm.setupRequest > 0) openConfig() }
    // Scrolling follows the chat page: follow while the reader is at the bottom, pause on drag,
    // resume at the end; the keyboard only drags the list along when already at the bottom.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    var autoFollow by remember { mutableStateOf(true) }
    var composerFocused by remember { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val composerFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val composerHeight = with(density) { composerHeightPx.toDp() }.coerceAtLeast(72.dp)
    val composerClearance = composerHeight + 18.dp
    val hasReplyFooter = if (vm.generating) {
        vm.generationType !in setOf(RpGenerationType.Swipe, RpGenerationType.Continue, RpGenerationType.Impersonate)
    } else session?.messages?.lastOrNull()?.isUser == true
    val tailIndex = (session?.messages?.size ?: 0) + if (hasReplyFooter) 1 else 0
    val latestTailIndex by rememberUpdatedState(tailIndex)
    val hasMessages by rememberUpdatedState(session?.messages?.isNotEmpty() == true)
    val listScrolling = remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(session?.id) {
        autoFollow = true
        if (hasMessages) listState.scrollToItem(latestTailIndex)
    }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(userDragging, listState.isScrollInProgress, listState.canScrollForward) }
            .collect { (dragging, scrolling, canScrollForward) ->
                if (dragging) autoFollow = false
                else if (!scrolling && !canScrollForward) autoFollow = true
            }
    }
    LaunchedEffect(session?.messages?.size, vm.generating) {
        if (hasMessages && autoFollow && !userDragging) listState.animateScrollToItem(latestTailIndex)
    }
    // Observe tokens in the coroutine, not as an effect key: only the live reply should recompose.
    // Coalesce bursts at frame boundaries instead of jumping once every 120 characters.
    LaunchedEffect(vm, listState, session?.id) {
        snapshotFlow {
            vm.streamingContent.length to vm.streamingReasoning.length
        }.conflate().collect {
            if (vm.generating && hasMessages && autoFollow && !userDragging && !composerFocused) {
                try {
                    withFrameNanos { }
                    if (autoFollow && !userDragging) listState.scrollToItem(latestTailIndex)
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }
    val imeInsets = WindowInsets.ime
    LaunchedEffect(composerFocused, session?.id) {
        if (!composerFocused) return@LaunchedEffect
        autoFollow = listState.isCloseToBottom()
        snapshotFlow { imeInsets.getBottom(density) }.distinctUntilChanged().collect {
            if (hasMessages && autoFollow && !userDragging) listState.scrollToItem(latestTailIndex)
        }
    }
    LaunchedEffect(vm.error) {
        // Errors without an inline floor (swipe/continue/import…) surface as a toast, not a banner.
        vm.error?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            vm.clearError()
        }
    }
    LaunchedEffect(vm.draftFocusRequest) {
        if (vm.draftFocusRequest > 0) runCatching { composerFocusRequester.requestFocus() }
    }

    Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            com.adong.adchat.ui.components.ConversationHeader(
                title = session?.title?.ifBlank { session.config.card.name.ifBlank { "剧情" } } ?: "剧情",
                model = session?.model?.ifBlank { "选择剧情模型" } ?: "选择剧情模型",
                onOpenDrawer = onOpenDrawer,
                onModelClick = { if (session != null) modelPickerOpen = true else newSession() }
            ) {
                if (session != null) AsterIconButton(Icons.Rounded.MoreHoriz, "剧情操作", { actionsOpen = true })
                AsterIconButton(Icons.Rounded.AddComment, "新建剧情", { newSession() }, enabled = !vm.generating)
            }

            if (session == null) {
                RpEmpty(onNew = { newSession() }, modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                val scripts = remember(session.config.card, session.cardScriptsAllowed) { vm.helperScripts(session) }
                if (helper != null && scripts.any { it.enabled }) RpHelperEngine(helper, scripts)
                val regexScripts = remember(session.config.card, session.config.presetId, session.cardRegexAllowed, vm.tavernRegexEnabled) {
                    vm.regexScripts(session)
                }
                val allowed = vm.scriptsAllowed(session)
                Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                    if (session.messages.isEmpty() && !vm.generating) {
                        RpEmpty(
                            title = session.config.card.name.ifBlank { "新剧情" },
                            subtitle = "直接写下你的第一句行动或对话，${session.config.card.macroName.ifBlank { "角色" }}会回应你。",
                            onNew = null,
                            modifier = Modifier.fillMaxSize().padding(bottom = composerClearance)
                        )
                    } else androidx.compose.runtime.CompositionLocalProvider(
                        com.adong.adchat.ui.components.LocalHtmlPreviewDeferral provides listScrolling
                    ) { RpConversation(
                        session = session,
                        vm = vm,
                        regexScripts = regexScripts,
                        userName = userName,
                        helper = helper.takeIf { allowed },
                        scriptsAllowed = allowed,
                        listState = listState,
                        bottomPadding = composerClearance + 24.dp,
                        onPanelAction = { if (!allowed) vm.sendMessage(it) },
                        onLongPress = { menuTarget = it },
                        onEditResend = { message ->
                            if (session.messages.lastOrNull()?.id == message.id) {
                                vm.loadDraftForEdit(message.content)
                                vm.truncateFromMessage(message.id)
                            } else editResendCandidate = message
                        },
                        modifier = Modifier.fillMaxSize()
                    ) }
                    com.adong.adchat.ui.components.ConversationReadingVeil(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(composerHeight)
                    )
                    RpJumpToBottom(
                        listState = listState, loading = vm.generating,
                        onClick = { autoFollow = true; scope.launch { listState.animateScrollToItem(latestTailIndex) } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + 8.dp)
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .onSizeChanged { composerHeightPx = it.height }
                            .navigationBarsPadding()
                    ) {
                        if (helper != null && allowed && helper.buttons.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                helper.buttons.forEach { button ->
                                    AsterPillButton(button.name, compact = true, onClick = { helper.clickButton(button) })
                                }
                            }
                        }
                        ConversationComposer(
                            value = vm.draft,
                            attachments = emptyList(),
                            loading = vm.generating,
                            attachmentLoading = false,
                            onValueChange = { vm.draft = it },
                            onOptionsClick = { toolsOpen = true },
                            onRemoveImage = {},
                            onSend = {
                                autoFollow = true
                                val text = vm.draft
                                vm.draft = ""
                                if (!vm.sendMessage(text)) vm.draft = text
                            },
                            onStop = { vm.stopGeneration() },
                            onFocusChange = { composerFocused = it },
                            modifier = Modifier.fillMaxWidth(),
                            focusRequester = composerFocusRequester,
                            reasoningAction = if (com.adong.adchat.data.ReasoningPolicy.choices(session.model).isEmpty()) null else {
                                {
                                    com.adong.adchat.ui.components.ComposerReasoningControl(
                                        model = session.model,
                                        effort = vm.reasoningEffortFor(session),
                                        expanded = reasoningExpanded,
                                        onExpandedChange = { reasoningExpanded = it },
                                        onModelClick = { modelPickerOpen = true },
                                        onEffortChange = vm::setReasoningEffort,
                                        enabled = !vm.generating
                                    )
                                }
                            },
                            configureRequired = session.model.isBlank(),
                            testTag = "rp",
                            placeholder = if (session.model.isBlank()) "先选择剧情模型" else "以 $userName 的身份行动或对话…"
                        )
                    }
                }
            }
        }
    }

    val needsConsent = session?.let { s ->
        remember(s.id, s.config.card, s.cardRegexAllowed, s.cardScriptsAllowed) { vm.needsCardConsent(s) }
    } ?: false
    if (session != null && !consentDismissed && needsConsent) {
        val regexCount = RpCardExtensions.scripts(session.config.card).size
        val scriptCount = RpCardExtensions.helperScripts(session.config.card, null).count { it.content.isNotBlank() }
        AdConfirmDialog(
            title = "允许角色卡的脚本？",
            message = buildString {
                append("「${session.config.card.name}」自带")
                if (regexCount > 0) append(" $regexCount 条正则")
                if (regexCount > 0 && scriptCount > 0) append(" 和")
                if (scriptCount > 0) append(" $scriptCount 个前端脚本")
                append("，用于状态栏、按钮等界面。脚本在离线沙箱中运行，不能联网，但可以读写本段剧情的消息与变量。")
            },
            confirmLabel = "允许",
            dismissLabel = "不允许",
            icon = Icons.Rounded.Code,
            onConfirm = { vm.setCardPermissions(regex = true, scripts = true); consentDismissed = true },
            onDismiss = { vm.setCardPermissions(regex = false, scripts = false); consentDismissed = true }
        )
    }

    if (actionsOpen && session != null) {
        val canContinue = session.messages.lastOrNull()?.let { !it.isUser && !it.isError } == true
        AdActionSheet(
            title = "剧情操作",
            subtitle = session.title.ifBlank { "当前剧情" },
            actions = listOf(
                AdActionOption("sessions", "剧情存档", "在侧栏切换、置顶、收藏或归档剧情", Icons.Rounded.Forum),
                AdActionOption("config", "角色与设定", "角色卡、开场白、你的身份", Icons.Rounded.Tune),
                AdActionOption("preset", "预设", vm.presetFor(session.config)?.name ?: "未选择 · 可导入酒馆预设", Icons.Rounded.AutoStories),
                AdActionOption("world", "世界书", worldSummary(vm, session), Icons.Rounded.Public),
                AdActionOption("persona", "你的身份", vm.userName(), Icons.Rounded.Person),
                AdActionOption("note", "作者注释", session.authorsNote.text.take(30).ifBlank { "按深度插入的固定提示" }, Icons.Rounded.StickyNote2),
                AdActionOption("continue", "继续写", "接着上一条回复写下去", Icons.Rounded.FastForward, enabled = canContinue && !vm.generating),
                AdActionOption("impersonate", "代写", "让模型替你写一句，填进输入框", Icons.Rounded.EditNote, enabled = !vm.generating)
            ),
            onAction = { action ->
                actionsOpen = false
                when (action.id) {
                    "sessions" -> onOpenDrawer()
                    "config" -> openConfig()
                    "preset" -> presetPickerOpen = true
                    "world" -> worldOpen = true
                    "persona" -> personaOpen = true
                    "note" -> noteOpen = true
                    "continue" -> vm.continueLast()
                    "impersonate" -> vm.impersonate()
                }
            },
            onDismiss = { actionsOpen = false },
            headerIcon = Icons.Rounded.TheaterComedy
        )
    }

    if (toolsOpen && session != null) {
        RpToolsSheet(
            vm = vm, session = session,
            onModel = { toolsOpen = false; modelPickerOpen = true },
            onPreset = { toolsOpen = false; presetPickerOpen = true },
            onWorld = { toolsOpen = false; worldOpen = true },
            onPersona = { toolsOpen = false; personaOpen = true },
            onNote = { toolsOpen = false; noteOpen = true },
            onConfig = { toolsOpen = false; openConfig() },
            onDismiss = { toolsOpen = false }
        )
    }

    if (worldOpen && session != null) {
        RpWorldBookSheet(vm = vm, session = session, onDismiss = { worldOpen = false })
    }

    if (configOpen && session != null && draftConfig != null) {
        RpConfigSheet(
            config = draftConfig!!,
            session = session,
            vm = vm,
            regexEnabled = vm.tavernRegexEnabled,
            helper = helper,
            onConfigChange = { draftConfig = it },
            onImportCard = { cardPicker.launch(arrayOf("application/json", "image/png", "application/zip", "application/octet-stream")) },
            onApply = {
                draftConfig?.let { vm.updateActiveConfig(it) }
                configOpen = false
            },
            onDismiss = { configOpen = false }
        )
    }

    if (personaOpen && session != null) {
        RpPersonaSheet(vm = vm, session = session, onDismiss = { personaOpen = false })
    }

    if (noteOpen && session != null) {
        RpAuthorsNoteSheet(note = session.authorsNote, onSave = { vm.setAuthorsNote(it); noteOpen = false }, onDismiss = { noteOpen = false })
    }

    if (modelPickerOpen && session != null) {
        // Same switcher as the chat page, scoped to this session's route (like Story mode).
        com.adong.adchat.ui.components.QuickModelSwitcher(
            kind = com.adong.adchat.ui.components.RouteKind.Chat, vm = mainVm,
            routeProfileId = session.profileId.ifBlank { null }, routeModel = session.model.ifBlank { null },
            onSelectChatModel = { profileId, model -> vm.setActiveProfileModel(profileId, model) },
            modelSelectionEnabled = !vm.generating,
            onDismiss = { modelPickerOpen = false },
            onManageApis = { modelPickerOpen = false; onOpenSettings() }
        )
    }


    if (presetPickerOpen && session != null) {
        RpPresetPickerSheet(
            presets = vm.tavernPresets,
            selectedId = session.config.presetId,
            onSelect = { id ->
                vm.setPreset(id)
                draftConfig = draftConfig?.copy(presetId = id)
                presetPickerOpen = false
            },
            onImport = { presetFilePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            onEdit = if (session.config.presetId != null) ({ presetPickerOpen = false; vm.openPresetEditor() }) else null,
            onDismiss = { presetPickerOpen = false }
        )
    }

    vm.presetConfiguration?.let { configuration ->
        com.adong.adchat.ui.screens.TavernPresetConfigurationSheet(
            configuration = configuration,
            regexEnabled = vm.tavernRegexEnabled,
            busy = vm.presetBusy || vm.generating,
            error = vm.presetError,
            onPromptEnabled = vm::setPresetPromptEnabled,
            onPromptContent = vm::setPresetPromptContent,
            onRegexScriptEnabled = vm::setPresetRegexScriptEnabled,
            onRegexEnabled = vm::setRegexEnabled,
            onReset = vm::resetPresetConfiguration,
            onBack = { vm.closePresetEditor(); presetPickerOpen = true },
            onDismiss = vm::closePresetEditor
        )
    }


    renameTarget?.let { target ->
        RpTextSheet("重命名剧情", "修改这段剧情在列表中的名称", target.title, singleLine = true,
            onSave = { vm.renameSession(target.id, it); renameTarget = null }, onDismiss = { renameTarget = null })
    }

    editResendCandidate?.let { target ->
        val removed = session?.let { it.messages.size - it.indexOf(target.id) - 1 } ?: 0
        AdConfirmDialog(
            title = "编辑重发？",
            message = "这条消息之后的 $removed 条剧情会被移除（本地记录不保留），输入框当前的草稿会被这条消息的原文替换。只想改字并保留后续剧情，请用「更多 → 编辑」。",
            confirmLabel = "编辑重发",
            dismissLabel = "取消",
            icon = Icons.Rounded.Edit,
            onConfirm = {
                vm.loadDraftForEdit(target.content)
                vm.truncateFromMessage(target.id)
                editResendCandidate = null
            },
            onDismiss = { editResendCandidate = null }
        )
    }

    menuTarget?.let { target ->
        RpMessageMenu(
            message = target,
            isLast = session?.messages?.lastOrNull()?.id == target.id,
            onEdit = { menuTarget = null; editTarget = target },
            onRewrite = {
                menuTarget = null
                if (target.isUser) vm.draft = target.content
                vm.truncateFromMessage(target.id)
            },
            onHide = { menuTarget = null; vm.setHidden(target.id, !target.hidden) },
            onBranch = { menuTarget = null; vm.branchFrom(target.id) },
            onDeleteSwipe = { menuTarget = null; vm.deleteSwipe() },
            onDelete = { menuTarget = null; deleteMessageCandidate = target },
            onDismiss = { menuTarget = null }
        )
    }

    editTarget?.let { target ->
        RpEditSheet(
            initial = target.content,
            onSave = { vm.editMessage(target.id, it); editTarget = null },
            onDismiss = { editTarget = null }
        )
    }

    deleteCandidate?.let { target ->
        AdConfirmDialog(
            title = "删除这段剧情？",
            message = "「${target.title.ifBlank { "未命名剧情" }}」的设定与对话记录会被永久删除。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            icon = Icons.Rounded.DeleteOutline,
            destructive = true,
            onConfirm = { vm.deleteSession(target.id); deleteCandidate = null },
            onDismiss = { deleteCandidate = null }
        )
    }

    deleteMessageCandidate?.let { target ->
        AdConfirmDialog(
            title = "删除这条消息？",
            message = "删除后无法恢复；之后的消息保持不变。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            icon = Icons.Rounded.DeleteOutline,
            destructive = true,
            onConfirm = { vm.deleteMessage(target.id); deleteMessageCandidate = null },
            onDismiss = { deleteMessageCandidate = null }
        )
    }
}

@Composable
private fun RpConversation(
    session: RpSession,
    vm: RpViewModel,
    regexScripts: List<TavernRegexScript>,
    userName: String,
    helper: RpTavernHelper?,
    scriptsAllowed: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onPanelAction: (String) -> Unit,
    onLongPress: (RpMessage) -> Unit,
    onEditResend: (RpMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    val characterName = session.config.card.macroName.ifBlank { "角色" }
    val macros = remember(session.id, session.config.card, userName, session.messages, session.model) { vm.macroContext(session) }
    val generating = vm.generating
    val swipingLast = generating && vm.generationType == RpGenerationType.Swipe
    val continuing = generating && vm.generationType == RpGenerationType.Continue
    LazyColumn(
        state = listState,
        modifier = modifier,
        // The tail owns the final 31dp (item spacing + 1dp); keep the composer clearance unchanged.
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = (bottomPadding - 31.dp).coerceAtLeast(0.dp)),
        verticalArrangement = Arrangement.spacedBy(30.dp)
    ) {
        itemsIndexed(session.messages, key = { _, message -> message.id },
            contentType = { _, message -> if (message.isUser) "user" else "assistant" }) { index, message ->
            val isLast = index == session.messages.lastIndex
            if (isLast && (swipingLast || continuing)) {
                val separator = vm.continueSeparator(session)
                val base = message.content.replace(com.adong.adchat.data.rp.helper.RpMvu.PLACEHOLDER, "").trimEnd()
                val content = when {
                    !continuing -> vm.streamingContent
                    base.isEmpty() || vm.streamingContent.isEmpty() -> base + vm.streamingContent
                    else -> base + separator + vm.streamingContent
                }
                RpStreamingItem(content, vm.streamingReasoning, characterName, regexScripts, macros)
                return@itemsIndexed
            }
            RpMessageItem(
                message = message,
                index = index,
                characterName = characterName,
                regexScripts = regexScripts,
                macros = macros,
                depth = session.messages.lastIndex - index,
                isLast = isLast,
                generating = generating,
                swipeable = isLast && !message.isUser && !message.isError && (message.swipeCount > 1 || index > 0),
                onSwipe = vm::swipe,
                onPanelAction = onPanelAction,
                onLongPress = { onLongPress(message) },
                onEditResend = { onEditResend(message) },
                onRegenerate = vm::regenerateLast,
                onContinue = vm::continueLast,
                onRetry = vm::retryLast,
                helper = helper,
                scriptsAllowed = scriptsAllowed,
                revision = helper?.revision(message.id) ?: 0,
                templates = vm.templatesEnabled,
                buildPresentation = { content, role ->
                    vm.presentation(session, index, content, role, session.messages.lastIndex - index, regexScripts, macros, helper?.revision(message.id) ?: 0)
                }
            )
        }
        if (generating && !swipingLast && !continuing && vm.generationType != RpGenerationType.Impersonate) {
            item(key = "rp-streaming") {
                RpStreamingItem(vm.streamingContent, vm.streamingReasoning, characterName, regexScripts, macros)
            }
        }
        if (!generating && session.messages.lastOrNull()?.isUser == true) {
            item(key = "rp-resume") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Surface(onClick = vm::resumeGeneration, color = SurfaceInset, shape = RoundedCornerShape(12.dp)) {
                        Text("轮到 $characterName 了 · 点按让 TA 回应", color = MutedInk, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
                    }
                }
            }
        }
        item(key = "rp-tail", contentType = "tail") { Spacer(Modifier.height(1.dp)) }
    }
}

@Composable
private fun RpHelperEngine(helper: RpTavernHelper, scripts: List<RpHelperScript>) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val document = remember(scripts) { HtmlPreviewDocument.engine(scripts, TavernHelperAssets.bundle(context)) }
    AndroidView(
        factory = { OfflineArtifactView(it) },
        modifier = Modifier.size(0.dp),
        update = { it.render(document, true, HtmlPreviewDocument.ORIGIN + "/", null, null, helper, lifecycle) {} },
        onRelease = OfflineArtifactView::release
    )
}

@Composable
private fun RpSwiper(index: Int, count: Int, canGenerate: Boolean, onSwipe: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (count > 1) ConversationMessageAction(Icons.Rounded.ChevronLeft, "上一个回复", { onSwipe(-1) })
        Text("${index + 1}/$count", color = MutedInk, style = MaterialTheme.typography.labelSmall)
        if (canGenerate || count > 1) ConversationMessageAction(
            Icons.Rounded.ChevronRight,
            if (canGenerate && index == count - 1) "生成新回复" else "下一个回复",
            { onSwipe(1) }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RpMessageItem(
    message: RpMessage,
    index: Int,
    characterName: String,
    regexScripts: List<TavernRegexScript>,
    macros: RpMacroContext,
    depth: Int,
    isLast: Boolean,
    generating: Boolean,
    swipeable: Boolean,
    onSwipe: (Int) -> Unit,
    onPanelAction: (String) -> Unit,
    onLongPress: () -> Unit,
    onEditResend: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    helper: RpTavernHelper?,
    scriptsAllowed: Boolean,
    revision: Int,
    templates: Boolean,
    buildPresentation: (String, String) -> com.adong.adchat.data.story.StoryProsePresentation
) {
    val user = message.isUser
    var showDetails by remember(message.id) { mutableStateOf(false) }
    val dim = if (message.hidden) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = dim },
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            if (user) Modifier.widthIn(max = 520.dp) else Modifier.weight(1f).widthIn(max = 680.dp),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start
        ) {
            when {
                message.isError -> {
                    com.adong.adchat.ui.components.ConversationAuthor(error = true, name = message.name.ifBlank { characterName })
                    Text(message.errorText, color = Ink, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.padding(top = 6.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(14.dp), tint = Danger)
                        Spacer(Modifier.width(6.dp))
                        Text("未收到有效回复", color = Danger, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    }
                    if (!generating) Row(Modifier.padding(top = 6.dp)) {
                        ConversationMessageAction(Icons.Rounded.Refresh, "重试", onRetry, accent = true)
                        ConversationMessageAction(Icons.Rounded.MoreHoriz, "更多", onLongPress)
                    }
                }
                user -> {
                    val displayText = remember(message.content, regexScripts, macros, depth, templates) {
                        buildPresentation(message.content, "user").blocks.joinToString("\n\n") { it.text.ifBlank { it.html.orEmpty() } }
                    }
                    // Same bubble language as the chat page: accent tint, 18dp radius, no border.
                    Surface(
                        color = Accent.copy(alpha = 0.08f),
                        contentColor = Ink,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
                    ) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                            com.adong.adchat.ui.components.ExpandableUserMessage(message.id, displayText)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.End) {
                        ConversationCopyAction(message.content)
                        if (!generating) {
                            ConversationMessageAction(Icons.Rounded.Edit, "编辑重发", onEditResend)
                            ConversationMessageAction(Icons.Rounded.MoreHoriz, "更多", onLongPress)
                        }
                    }
                }
                else -> {
                    com.adong.adchat.ui.components.ConversationAuthor(
                        name = message.name.ifBlank { characterName }, badge = if (message.hidden) "已隐藏" else null
                    )
                    com.adong.adchat.ui.components.ConversationReasoningPanel(
                        reasoning = message.current.reasoning, streaming = false, bodyStarted = true
                    )
                    val presentation = remember(message.content, regexScripts, macros, depth, revision, templates) {
                        buildPresentation(message.content, "assistant")
                    }
                    Box(Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)) {
                        StoryProseContent(
                            presentation, streaming = false, onPanelAction = onPanelAction, helper = helper,
                            messageId = index, revision = revision, heightKey = "${message.id}:${message.currentIndex}",
                            allowScripts = scriptsAllowed, remoteMedia = true
                        )
                    }
                    if (message.current.interrupted) {
                        Row(Modifier.padding(top = 6.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.StopCircle, null, Modifier.size(14.dp), tint = MutedInk)
                            Spacer(Modifier.width(6.dp))
                            Text("生成已中断，内容已保留", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    FlowRow(
                        Modifier.padding(top = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        ConversationCopyAction(message.content)
                        if (swipeable && !generating) RpSwiper(message.currentIndex, message.swipeCount, canGenerate = index > 0, onSwipe = onSwipe)
                        if (isLast && index > 0 && !generating) {
                            if (message.current.interrupted) ConversationMessageAction(Icons.Rounded.PlayArrow, "继续生成", onContinue)
                            ConversationMessageAction(Icons.Rounded.Refresh, "重新生成", onRegenerate, accent = !message.current.interrupted)
                        }
                        if (message.current.model.isNotBlank() || message.current.usage != null) {
                            ConversationMessageAction(Icons.Rounded.Info, if (showDetails) "收起" else "详情", { showDetails = !showDetails }, accent = showDetails)
                        }
                        ConversationMessageAction(Icons.Rounded.MoreHoriz, "更多", onLongPress)
                    }
                    androidx.compose.animation.AnimatedVisibility(showDetails) {
                        Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                            com.adong.adchat.ui.screens.RequestTransparencyPanel(message.toChatMessage())
                            message.current.usage?.let { usage -> com.adong.adchat.ui.screens.TokenUsagePanel(usage, message.current.model) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RpStreamingItem(
    content: String,
    reasoning: String,
    characterName: String,
    regexScripts: List<TavernRegexScript>,
    macros: RpMacroContext
) {
    Column(Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
        if (content.isNotBlank() || reasoning.isNotBlank()) com.adong.adchat.ui.components.ConversationAuthor(name = characterName)
        com.adong.adchat.ui.components.ConversationReasoningPanel(
            reasoning = reasoning, streaming = true, bodyStarted = content.isNotBlank()
        )
        if (content.isBlank()) {
            if (reasoning.isBlank()) com.adong.adchat.ui.components.ConversationThinkingIndicator()
        } else {
            val presentation = remember(content, regexScripts, macros) {
                RpPresentation.build(content, "assistant", 0, regexScripts, macros)
            }
            StoryProseContent(presentation, streaming = true, allowScripts = false, remoteMedia = true)
            com.adong.adchat.ui.screens.ModelWorkingFoot("正在书写…")
        }
    }
}

@Composable
private fun RpMessageMenu(
    message: RpMessage,
    isLast: Boolean,
    onEdit: () -> Unit,
    onRewrite: () -> Unit,
    onHide: () -> Unit,
    onBranch: () -> Unit,
    onDeleteSwipe: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AdActionSheet(
        title = if (message.isUser) "你的消息" else message.name.ifBlank { "角色回复" },
        subtitle = message.content.take(40).replace('\n', ' ').ifBlank { "空消息" },
        actions = buildList {
            if (!message.isError) add(AdActionOption("edit", "编辑", "直接修改这条消息，之后的剧情保留", Icons.Rounded.Edit))
            add(AdActionOption("hide", if (message.hidden) "取消隐藏" else "隐藏", "隐藏的消息仍显示，但不再发给模型", Icons.Rounded.VisibilityOff))
            add(AdActionOption("branch", "从这里分支", "复制到此为止的剧情，开一段新的", Icons.Rounded.CallSplit))
            if (!message.isUser) add(AdActionOption("rewrite", "回到这条之前", "删除这条及之后的消息", Icons.Rounded.Undo))
            if (isLast && message.swipeCount > 1) add(AdActionOption("swipe", "删除当前版本", "只删除这一个回复版本", Icons.Rounded.DeleteSweep))
            add(AdActionOption("delete", "删除这条", "只删除这一条，之后的剧情保留", Icons.Rounded.DeleteOutline, destructive = true))
        },
        onAction = {
            when (it.id) {
                "edit" -> onEdit()
                "hide" -> onHide()
                "branch" -> onBranch()
                "rewrite" -> onRewrite()
                "swipe" -> onDeleteSwipe()
                "delete" -> onDelete()
            }
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun RpTextSheet(
    title: String,
    subtitle: String,
    initial: String,
    singleLine: Boolean = false,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AsterOptionsSheet(title = title, subtitle = subtitle, onDismiss = onDismiss, headerIcon = Icons.Rounded.Edit) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = singleLine, minLines = if (singleLine) 1 else 4,
            shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
        )
        AsterPrimaryButton("保存", onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth())
    }
}

private fun worldSummary(vm: RpViewModel, session: RpSession): String {
    val chat = session.chatLorebookIds.size
    val global = vm.globalLorebookIds.size
    val card = if (session.config.card.worldBook.entries.isNotEmpty() && session.cardBookEnabled) "角色卡 · " else ""
    return "${card}聊天 $chat 本 · 全局 $global 本"
}

@Composable
private fun RpEditSheet(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AsterOptionsSheet(title = "编辑消息", subtitle = "保存后直接替换当前版本", onDismiss = onDismiss, headerIcon = Icons.Rounded.Edit) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, minLines = 4,
            shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
        )
        AsterPrimaryButton("保存", onClick = { onSave(text) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RpAuthorsNoteSheet(note: RpAuthorsNote, onSave: (RpAuthorsNote) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(note.text) }
    var depth by remember { mutableStateOf(note.depth.toString()) }
    var interval by remember { mutableStateOf(note.interval.toString()) }
    var position by remember { mutableStateOf(note.position) }
    AsterOptionsSheet(title = "作者注释", subtitle = "每隔几轮插入一次的固定提示，对应酒馆 Author's Note", onDismiss = onDismiss, headerIcon = Icons.Rounded.StickyNote2) {
        RpField("内容", text, { text = it }, "例如：保持悬疑氛围，{{char}} 不会轻易透露秘密", minLines = 3)
        AsterSegmentedControl(
            labels = listOf("聊天中", "主提示后", "主提示前"),
            selectedIndex = when (position) { RpNotePosition.InChat -> 0; RpNotePosition.AfterMain -> 1; RpNotePosition.BeforeMain -> 2 },
            onSelect = { position = listOf(RpNotePosition.InChat, RpNotePosition.AfterMain, RpNotePosition.BeforeMain)[it] }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { RpField("插入深度", depth, { depth = it.filter(Char::isDigit) }, "4", singleLine = true) }
            Box(Modifier.weight(1f)) { RpField("间隔轮数", interval, { interval = it.filter(Char::isDigit) }, "1（0 为关闭）", singleLine = true) }
        }
        AsterPrimaryButton("保存", onClick = {
            onSave(note.copy(text = text, depth = depth.toIntOrNull() ?: 4, interval = interval.toIntOrNull() ?: 1, position = position))
        }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RpPersonaSheet(vm: RpViewModel, session: RpSession, onDismiss: () -> Unit) {
    val resolved = vm.resolvedPersona(session)
    var editing by remember { mutableStateOf<RpPersona?>(null) }
    AsterOptionsSheet(title = "你的身份", subtitle = "{{user}} 的名字与人设；可锁定到本段剧情或绑定到这张角色卡", onDismiss = onDismiss, headerIcon = Icons.Rounded.Person) {
        val current = editing
        if (current != null) {
            var name by remember(current.id) { mutableStateOf(current.name) }
            var description by remember(current.id) { mutableStateOf(current.description) }
            var position by remember(current.id) { mutableStateOf(current.position) }
            var depth by remember(current.id) { mutableStateOf(current.depth.toString()) }
            RpField("名字", name, { name = it }, "你在剧情中的名字", singleLine = true)
            RpField("人设", description, { description = it }, "外貌、身份、与角色的关系…", minLines = 3)
            Text("插入位置", color = Ink, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    RpPersonaPosition.InPrompt to "提示词中", RpPersonaPosition.AtDepth to "按深度",
                    RpPersonaPosition.TopAuthorsNote to "作者注释前", RpPersonaPosition.BottomAuthorsNote to "作者注释后",
                    RpPersonaPosition.None to "不插入"
                ).forEach { (value, label) -> AsterPillButton(label, compact = true, selected = position == value, onClick = { position = value }) }
            }
            if (position == RpPersonaPosition.AtDepth) RpField("深度", depth, { depth = it.filter(Char::isDigit) }, "2", singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AsterPillButton("取消", onClick = { editing = null }, modifier = Modifier.weight(1f))
                AsterPrimaryButton("保存", enabled = name.isNotBlank(), onClick = {
                    vm.savePersona(current.copy(name = name.trim(), description = description, position = position, depth = depth.toIntOrNull() ?: 2))
                    editing = null
                }, modifier = Modifier.weight(1f))
            }
        } else {
            val library = vm.personas
            val defaultId = vm.personaStore.library().defaultId
            library.forEach { persona ->
                val active = resolved?.persona?.id == persona.id
                val tags = listOfNotNull(
                    "默认".takeIf { persona.id == defaultId },
                    "已锁定本剧情".takeIf { session.personaId == persona.id },
                    "已绑定此角色卡".takeIf { session.cardKey in persona.connections }
                ).joinToString(" · ")
                RpPresetOption(persona.name, listOf(tags, persona.description.take(40)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "无人设" }, active) {
                    vm.lockPersona(persona.id)
                }
                if (active) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsterPillButton("编辑", compact = true, onClick = { editing = persona })
                    if (persona.id != defaultId) AsterPillButton("设为默认", compact = true, onClick = { vm.setDefaultPersona(persona.id) })
                    AsterPillButton(
                        if (session.cardKey in persona.connections) "解除绑定" else "绑定此角色卡", compact = true,
                        onClick = { vm.connectPersonaToCard(persona.id, session.cardKey !in persona.connections) }
                    )
                    if (session.personaId == persona.id) AsterPillButton("解除锁定", compact = true, onClick = { vm.lockPersona(null) })
                    AsterPillButton("删除", compact = true, destructive = true, onClick = { vm.deletePersona(persona.id) })
                }
            }
            if (library.isEmpty()) Text("当前使用：${resolved?.persona?.name ?: RpPersona.DEFAULT_NAME}（来自设置中的用户名）", color = MutedInk, style = MaterialTheme.typography.bodySmall)
            AsterPrimaryButton("新建身份", icon = Icons.Rounded.Add, onClick = { editing = RpPersona(name = vm.userName()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RpConfigSheet(
    config: RpConfig,
    session: RpSession,
    vm: RpViewModel,
    regexEnabled: Boolean,
    helper: RpTavernHelper?,
    onConfigChange: (RpConfig) -> Unit,
    onImportCard: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val card = config.card
    AsterOptionsSheet(
        title = "角色与设定",
        subtitle = "角色卡或文字设定；模型、预设、世界书、身份在剧情工具里调整",
        onDismiss = onDismiss,
        headerIcon = Icons.Rounded.Tune
    ) {
        AsterSegmentedControl(
            labels = listOf("角色卡", "纯文本"),
            selectedIndex = if (config.source == RpSource.CharacterCard) 0 else 1,
            onSelect = { onConfigChange(config.copy(source = if (it == 0) RpSource.CharacterCard else RpSource.Text)) }
        )
        if (config.source == RpSource.CharacterCard) {
            AsterPillButton(
                text = if (card.imported) "重新导入角色卡" else "导入角色卡",
                icon = Icons.Rounded.FileUpload,
                onClick = onImportCard,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "支持酒馆 JSON / PNG / charx 角色卡，导入后仍可编辑。",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                "用文字描述世界观、人物与开场，无需角色卡即可开始 RP。",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
        }

        RpField("角色 / 故事名称", card.name, { onConfigChange(config.copy(card = card.copy(name = it))) }, "例如：艾莉娅 / 迷雾之城", singleLine = true)
        RpField("描述 / 世界观", card.description, { onConfigChange(config.copy(card = card.copy(description = it))) }, "背景设定、世界规则、人物外貌与关系…", minLines = 3)
        RpField("性格", card.personality, { onConfigChange(config.copy(card = card.copy(personality = it))) }, "语气、性格、行为倾向…", minLines = 2)
        RpField("场景", card.scenario, { onConfigChange(config.copy(card = card.copy(scenario = it))) }, "当前情境与开场环境…", minLines = 2)
        RpField("开场白", config.opening, { onConfigChange(config.copy(opening = it)) }, "第一段由角色发出的开场（可选）", minLines = 2, maxHeight = 180.dp)
        if (config.opening.isNotBlank() && OPENING_HTML.containsMatchIn(config.opening)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("开场白预览", color = Ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                val preview = remember(config, regexEnabled, session.cardRegexAllowed) {
                    val previewSession = session.copy(config = config)
                    RpPresentation.build(config.opening, "assistant", 0, vm.regexScripts(previewSession), vm.macroContext(previewSession))
                }
                val allowed = vm.scriptsAllowed(session)
                StoryProseContent(preview, streaming = false, helper = helper.takeIf { allowed }, messageId = 0, allowScripts = allowed, remoteMedia = true)
            }
        }
        RpCardAdvanced(config, onConfigChange)

        if (config.source == RpSource.CharacterCard && card.imported) {
            RpCardMetadata(card, session)
        }

        AsterPrimaryButton(
            text = "应用设定",
            onClick = onApply,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RpCardMetadata(card: StoryCharacterCard, session: RpSession) {
    val helpers = RpCardExtensions.helperScripts(card, null).count { it.content.isNotBlank() }
    val regex = RpCardExtensions.scripts(card).size
    val byline = listOf(card.creator, card.characterVersion).filter { it.isNotBlank() }.joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (card.creatorNotes.isNotBlank()) {
            Text("作者备注", color = Ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Text(card.creatorNotes, color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
        if (card.tags.isNotEmpty()) {
            Text("标签：${card.tags.joinToString("、")}", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
        if (byline.isNotBlank()) {
            Text(byline, color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
        if (helpers > 0 || regex > 0) {
            val state = when {
                session.cardScriptsAllowed == true || session.cardRegexAllowed == true -> "已允许，在离线沙箱中运行"
                session.cardScriptsAllowed == false -> "未允许，界面只显示静态内容"
                else -> "首次使用时会询问是否允许"
            }
            Text("角色卡自带 $regex 条正则、$helpers 个前端脚本：$state。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
        if (card.worldBook.entries.isNotEmpty()) {
            Text("世界书 ${card.worldBook.entries.count { it.enabled }}/${card.worldBook.entries.size} 条启用", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RpCardAdvanced(config: RpConfig, onConfigChange: (RpConfig) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val card = config.card
    AsterPillButton(if (open) "收起高级设定" else "高级设定：示例对话、系统提示", icon = if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        compact = true, onClick = { open = !open }, modifier = Modifier.fillMaxWidth())
    if (!open) return
    RpField("示例对话", card.mesExample, { onConfigChange(config.copy(card = card.copy(mesExample = it))) }, "<START>\n{{user}}: …\n{{char}}: …", minLines = 3, maxHeight = 220.dp)
    RpField("角色系统提示", card.systemPrompt, { onConfigChange(config.copy(card = card.copy(systemPrompt = it))) }, "覆盖预设主提示；用 {{original}} 保留原文", minLines = 2, maxHeight = 180.dp)
    RpField("历史后指令", card.postHistory, { onConfigChange(config.copy(card = card.copy(postHistory = it))) }, "放在聊天记录之后的指令（Post-History Instructions）", minLines = 2, maxHeight = 180.dp)
}

@Composable
private fun RpField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxHeight: androidx.compose.ui.unit.Dp? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Ink, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = MutedInk) },
            singleLine = singleLine,
            minLines = minLines,
            shape = RoundedCornerShape(15.dp),
            modifier = Modifier.fillMaxWidth().then(
                if (maxHeight != null) Modifier.heightIn(max = maxHeight) else Modifier
            )
        )
    }
}

@Composable
private fun RpPresetPickerSheet(
    presets: List<TavernPresetSummary>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onImport: () -> Unit,
    onEdit: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    AsterOptionsSheet(
        title = "选择预设",
        subtitle = "复用酒馆预设的提示词与正则",
        onDismiss = onDismiss,
        headerIcon = Icons.Rounded.AutoStories
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsterPillButton("导入预设", icon = Icons.Rounded.FileUpload, onClick = onImport, modifier = Modifier.weight(1f))
            if (onEdit != null) AsterPillButton("编辑当前预设", icon = Icons.Rounded.Edit, primary = true, onClick = onEdit, modifier = Modifier.weight(1f))
        }
        RpPresetOption("无预设", "仅使用角色设定与基础指令", selectedId == null) { onSelect(null) }
        presets.forEach { preset ->
            RpPresetOption(
                title = preset.name,
                subtitle = "${preset.enabledPromptCount}/${preset.promptCount} 提示词 · ${preset.enabledRegexCount} 正则",
                selected = preset.id == selectedId,
                onClick = { onSelect(preset.id) }
            )
        }
    }
}

@Composable
private fun RpPresetOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) AccentSoft else Surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = 0.48f) else Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (selected) OnAccentSoft else Ink, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = if (selected) OnAccentSoft.copy(alpha = 0.8f) else MutedInk, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Rounded.Check, "已选择", Modifier.size(18.dp), tint = OnAccentSoft)
            }
        }
    }
}

@Composable
private fun RpWorldBookSheet(vm: RpViewModel, session: RpSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expandedId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<com.adong.adchat.data.rp.RpLorebook?>(null) }
    var deleteTarget by remember { mutableStateOf<com.adong.adchat.data.rp.RpLorebook?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "lorebook.json"
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("无法读取所选文件")
                vm.importLorebook(bytes, name, bindToChat = true)
            }.onSuccess {
                android.widget.Toast.makeText(context, "已导入「$it」并绑定到本剧情", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "世界书导入失败：${it.message ?: "格式不支持"}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val cardKey = session.cardKey
    val cardBookId = vm.lorebooks.firstOrNull { it.source == com.adong.adchat.data.rp.RpLorebookSource.Card && it.cardKey == cardKey }?.id
    AsterOptionsSheet(
        title = "世界书",
        subtitle = "全局书对所有剧情生效；聊天书只对本段剧情生效",
        onDismiss = onDismiss,
        headerIcon = Icons.Rounded.Public
    ) {
        if (session.config.card.worldBook.entries.isNotEmpty()) {
            ComposerToolToggle(
                Icons.Rounded.AutoStories, "角色卡自带世界书",
                "${session.config.card.worldBook.entries.size} 条 · 已保存到世界书库，可在其他剧情复用",
                session.cardBookEnabled, vm::setCardBookEnabled
            )
        }
        AsterPillButton("导入世界书（JSON / 角色卡）", icon = Icons.Rounded.FileUpload,
            onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth())
        if (vm.lorebooks.isEmpty()) {
            Text("世界书库为空。导入酒馆世界书文件，或导入带世界书的角色卡后会自动加入。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
        vm.lorebooks.forEach { book ->
            val isCardBook = book.id == cardBookId
            val chatBound = book.id in session.chatLorebookIds
            val global = book.id in vm.globalLorebookIds
            Surface(
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (chatBound || global) Accent.copy(alpha = 0.4f) else Hairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(book.name, color = Ink, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val source = when (book.source) {
                                com.adong.adchat.data.rp.RpLorebookSource.Card -> if (isCardBook) "本角色卡" else "来自角色卡"
                                com.adong.adchat.data.rp.RpLorebookSource.Imported -> "导入"
                                com.adong.adchat.data.rp.RpLorebookSource.Created -> "自建"
                            }
                            Text("$source · ${book.book.entries.count { it.enabled }}/${book.book.entries.size} 条启用",
                                color = MutedInk, style = MaterialTheme.typography.labelSmall)
                        }
                        AsterIconButton(if (expandedId == book.id) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "条目",
                            { expandedId = if (expandedId == book.id) null else book.id }, tint = MutedInk)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!isCardBook) AsterPillButton(if (chatBound) "已绑定本剧情" else "绑定本剧情", compact = true, selected = chatBound,
                            onClick = { vm.setChatLorebook(book.id, !chatBound) })
                        AsterPillButton(if (global) "全局启用中" else "全局启用", compact = true, selected = global,
                            onClick = { vm.setGlobalLorebook(book.id, !global) })
                        AsterPillButton("重命名", compact = true, onClick = { renameTarget = book })
                        AsterPillButton("删除", compact = true, destructive = true, onClick = { deleteTarget = book })
                    }
                    if (expandedId == book.id) {
                        book.book.entries.forEachIndexed { index, entry ->
                            val title = entry.comment.ifBlank { entry.entryName }.ifBlank { entry.keys.take(3).joinToString("、") }.ifBlank { "条目 ${index + 1}" }
                            ComposerToolToggle(
                                Icons.Rounded.AutoStories, title,
                                (if (entry.constant) "常驻 · " else "关键词：${entry.keys.take(4).joinToString("、")} · ") + entry.content.take(30).replace('\n', ' '),
                                entry.enabled
                            ) { vm.setLorebookEntryEnabled(book.id, index, it) }
                        }
                    }
                }
            }
        }
    }
    renameTarget?.let { target ->
        RpTextSheet("重命名世界书", "修改世界书在库中的名称", target.name, singleLine = true,
            onSave = { vm.renameLorebook(target.id, it); renameTarget = null }, onDismiss = { renameTarget = null })
    }
    deleteTarget?.let { target ->
        AdConfirmDialog(
            title = "删除这本世界书？",
            message = "「${target.name}」会从世界书库中删除，并从所有剧情的绑定中移除。角色卡自带的原始世界书不受影响。",
            confirmLabel = "删除", dismissLabel = "取消", icon = Icons.Rounded.DeleteOutline, destructive = true,
            onConfirm = { vm.deleteLorebook(target.id); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}

/** Empty state in the chat page's language: mark, one line, one primary pill. */
@Composable
private fun RpEmpty(
    modifier: Modifier = Modifier,
    title: String = "开始一段剧情",
    subtitle: String = "导入酒馆角色卡，或用文字描述世界观与人物。",
    onNew: (() -> Unit)?
) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(top = 88.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            com.adong.adchat.ui.components.AsterMark(Modifier.size(72.dp))
            Spacer(Modifier.height(22.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MutedInk, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (onNew != null) {
                Spacer(Modifier.height(20.dp))
                AsterPillButton(text = "新建剧情", icon = Icons.Rounded.Add, primary = true, onClick = onNew)
            }
        }
    }
}

/** Composer "+" sheet, like the chat tools sheet: per-turn controls for this RP. */
@Composable
private fun RpToolsSheet(
    vm: RpViewModel,
    session: RpSession,
    onModel: () -> Unit,
    onPreset: () -> Unit,
    onWorld: () -> Unit,
    onPersona: () -> Unit,
    onNote: () -> Unit,
    onConfig: () -> Unit,
    onDismiss: () -> Unit
) {
    val canContinue = session.messages.lastOrNull()?.let { !it.isUser && !it.isError } == true
    AsterOptionsSheet(title = "剧情工具", subtitle = session.title.ifBlank { "当前剧情" }, onDismiss = onDismiss, headerIcon = Icons.Rounded.TheaterComedy) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConversationSheetAction(Icons.Rounded.Hub, "模型", true, onModel, Modifier.weight(1f), session.model.ifBlank { "未选择" })
            ConversationSheetAction(Icons.Rounded.AutoStories, "预设", true, onPreset, Modifier.weight(1f), vm.presetFor(session.config)?.name ?: "未选择")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConversationSheetAction(Icons.Rounded.Public, "世界书", true, onWorld, Modifier.weight(1f), worldSummary(vm, session))
            ConversationSheetAction(Icons.Rounded.Person, "你的身份", true, onPersona, Modifier.weight(1f), vm.userName())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConversationSheetAction(Icons.Rounded.FastForward, "继续写", canContinue && !vm.generating, { onDismiss(); vm.continueLast() }, Modifier.weight(1f), "接着上一条写")
            ConversationSheetAction(Icons.Rounded.EditNote, "代写", !vm.generating, { onDismiss(); vm.impersonate() }, Modifier.weight(1f), "替你写一句")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConversationSheetAction(Icons.Rounded.StickyNote2, "作者注释", true, onNote, Modifier.weight(1f), session.authorsNote.text.take(14).ifBlank { "未设置" })
            ConversationSheetAction(Icons.Rounded.Tune, "角色与设定", true, onConfig, Modifier.weight(1f), session.config.card.name.ifBlank { "未设置" })
        }
        Text("生成选项", color = MutedInk, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
        ComposerToolToggle(Icons.Rounded.AutoStories, "自动记忆", "整理分层摘要，让长剧情记得前文", session.memoryEnabled, vm::setMemoryEnabled)
        RpMemoryPanel(vm, session)
        ComposerToolToggle(Icons.Rounded.FindReplace, "正则脚本", "应用预设与角色卡中的酒馆正则替换", vm.tavernRegexEnabled, vm::setRegexEnabled)
        ComposerToolToggle(Icons.Rounded.Code, "提示词模板", "执行 <% %> 模板与 [GENERATE]/[RENDER] 世界书条目", vm.templatesEnabled, vm::updateTemplatesEnabled)
        ComposerToolToggle(Icons.Rounded.AutoAwesome, "注入预设", "复用主聊天的提示词注入预设", session.injectionEnabled, vm::setInjectionEnabled)
        if (vm.lastWorldEntries.isNotEmpty()) {
            Text("上次请求触发的世界书条目：${vm.lastWorldEntries.joinToString("、")}", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Hosts the "back to bottom" button's scroll tracking. That tracker updates state on every frame of a
 * downward scroll; reading it here keeps those updates from recomposing the whole RP screen.
 */
@Composable
private fun RpJumpToBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val visible = com.adong.adchat.ui.components.rememberTowardBottomScrollVisible(listState)
    com.adong.adchat.ui.components.ConversationJumpToBottom(
        visible = visible, loading = loading, onClick = onClick, modifier = modifier,
        fadeMillisIn = 220, fadeMillisOut = 220
    )
}

/** Memory status, summary preview and clear — lives with the memory switch in the tools sheet. */
@Composable
private fun RpMemoryPanel(vm: RpViewModel, session: RpSession) {
    if (!session.memoryEnabled) return
    var showSummary by remember { mutableStateOf(false) }
    val summary = vm.memorySummaryFor(session.id)
    if (vm.memoryStatus.isNotBlank()) Text(vm.memoryStatus, color = MutedInk, style = MaterialTheme.typography.bodySmall)
    if (summary.isNullOrBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AsterPillButton(if (showSummary) "收起摘要" else "查看摘要 · ${summary.length} 字", compact = true, selected = showSummary,
            onClick = { showSummary = !showSummary })
        AsterPillButton("清空", compact = true, destructive = true, onClick = { showSummary = false; vm.clearMemory(session.id) })
    }
    if (showSummary) Surface(
        color = SurfaceInset.copy(alpha = 0.6f), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Hairline.copy(alpha = 0.6f)), modifier = Modifier.fillMaxWidth()
    ) { Text(summary, Modifier.padding(12.dp), color = Ink, style = MaterialTheme.typography.bodySmall) }
}
