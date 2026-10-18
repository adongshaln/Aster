package com.adong.adchat.ui.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import com.adong.adchat.ui.markdown.linkAt
import com.adong.adchat.ui.clawd.ClawdHost
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.data.chatImageHeaders
import com.adong.adchat.data.downloadChatImage
import com.adong.adchat.data.fetchPixivPreviewUrl
import com.adong.adchat.data.ReasoningPolicy
import com.adong.adchat.data.usesResponses
import com.adong.adchat.data.ChatFileAttachment
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.ChatCitation
import com.adong.adchat.data.ChatToolActivity
import com.adong.adchat.data.TOOL_STATUS_COMPLETED
import com.adong.adchat.data.TOOL_STATUS_FAILED
import com.adong.adchat.data.TOOL_STATUS_RUNNING
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.fittedImageDp
import com.adong.adchat.ui.modelWorkingCaption
import com.adong.adchat.ui.showModelWorkingFoot
import com.adong.adchat.ui.waitingOnUser
import com.adong.adchat.ui.ContextUsage
import com.adong.adchat.ui.chat.questionNavigationTargets
import com.adong.adchat.ui.components.*
import com.adong.adchat.ui.components.AdChoiceOption
import com.adong.adchat.ui.components.AdSelectionSheet
import com.adong.adchat.ui.components.QuickModelSwitcher
import com.adong.adchat.ui.components.RouteKind
import com.adong.adchat.data.ASK_USER_CUSTOM_OPTION
import com.adong.adchat.data.ASK_USER_TOOL
import com.adong.adchat.data.AskUserPrompt
import com.adong.adchat.ui.markdown.MarkdownTable
import com.adong.adchat.ui.markdown.MarkdownTableAlignment
import com.adong.adchat.ui.markdown.containsMarkdownTable
import com.adong.adchat.ui.markdown.markdownTableToTsv
import com.adong.adchat.ui.markdown.emphasisSpanAllowed
import com.adong.adchat.ui.markdown.isChapterHeading
import com.adong.adchat.ui.markdown.isSceneBreak
import com.adong.adchat.ui.markdown.isStarredProseLine
import com.adong.adchat.ui.markdown.joinProseLine
import com.adong.adchat.ui.markdown.parseMarkdownTableAt
import com.adong.adchat.ui.markdown.readingSpans
import com.adong.adchat.ui.markdown.splitInlineImages
import com.adong.adchat.ui.markdown.nextPlainEnd
import com.adong.adchat.ui.markdown.InlinePiece
import com.adong.adchat.ui.markdown.ReadingSpan
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(vm: MainViewModel, onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit) {
    val composerFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeAnimationTarget = WindowInsets.imeAnimationTarget
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    // 滚动信号：给 Clawd 的 peek（原版监听滚动）。仅在实际的拖拽/惯性滚动且位置真正变化时计数，
    // 排除首次组合、程序化定位与回弹无位移的干扰。
    var clawdScrollTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState, vm.activeConversationId) {
        var manualScroll = false
        var previousPosition = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                userDragging,
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            )
        }.collect { (inProgress, dragging, position) ->
            if (dragging) manualScroll = true
            if (!inProgress && !dragging) manualScroll = false
            if (inProgress && manualScroll && position != previousPosition) {
                clawdScrollTick++
            }
            previousPosition = position
        }
    }
    val streamScrollSignals = remember {
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    var showSwitcher by remember { mutableStateOf(false) }
    var showContextDialog by remember { mutableStateOf(false) }
    var pendingEditResend by remember { mutableStateOf<com.adong.adchat.data.ChatMessage?>(null) }
    val hostContext = LocalContext.current
    val contextModel = vm.chatProfile.chatModel
    val contextLimits = vm.chatProfile.modelContexts[contextModel.trim()]
    // 估算要走整段历史，放在后台算，避免每次重组都卡一帧；初值不为空，点进度圈立刻有反应；
    // 改完上下文长度也要立刻重算。
    val contextUsage by produceState(
        initialValue = ContextUsage(
            model = contextModel, limits = contextLimits, systemTokens = 0, historyTokens = 0,
            attachmentTokens = 0, overheadTokens = 0, omittedTurns = 0,
            lastRequestInputTokens = 0, lastRequestOutputTokens = 0, lastRequestReasoningTokens = 0
        ),
        vm.messages.size,
        vm.messages.lastOrNull()?.content?.length ?: 0,
        contextModel,
        vm.chatInput,
        vm.chatAttachments.size,
        vm.appConfig.systemPrompt,
        contextLimits
    ) {
        value = withContext(Dispatchers.Default) {
            vm.contextUsage(vm.chatInput, vm.chatAttachments.toList())
        }
    }
    var autoFollow by remember { mutableStateOf(true) }
    val imageFrames = remember { mutableMapOf<String, Pair<Int, Int>>() }
    var composerFocused by remember { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var formAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var chatAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var pendingFileExport by remember { mutableStateOf<ChatFileAttachment?>(null) }
    val fileExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = pendingFileExport
        pendingFileExport = null
        if (result.resultCode == Activity.RESULT_OK && file != null) {
            result.data?.data?.let { target -> vm.exportGeneratedFile(file, target) }
        }
    }
    val chatDocumentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::attachChatDocument)
    }
    val chatImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) vm.attachChatImages(uris)
    }
    val jumpToBottomVisible = rememberTowardBottomScrollVisible(listState)
    val questionIndices by remember {
        derivedStateOf { vm.messages.mapIndexedNotNull { index, message -> index.takeIf { message.role == "user" } } }
    }
    val questionTargets by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)
            val anchorY = layoutInfo.viewportStartOffset + (viewportHeight * .7f).toInt()
            val anchorItemIndex = visibleItems.firstOrNull { item ->
                anchorY >= item.offset && anchorY < item.offset + item.size
            }?.index ?: visibleItems.minByOrNull { item ->
                kotlin.math.abs((item.offset + item.size / 2) - anchorY)
            }?.index ?: listState.firstVisibleItemIndex
            questionNavigationTargets(
                questionIndices = questionIndices,
                anchorItemIndex = anchorItemIndex,
                visibleQuestionIndices = visibleItems.asSequence()
                    .map { it.index }
                    .filter { it in questionIndices }
                    .toSet()
            )
        }
    }
    val regeneratableMessageId by remember {
        derivedStateOf {
            vm.messages.lastOrNull()?.takeIf {
                it.role == "assistant" && !it.isStreaming && !it.isInterrupted && !it.isStopped && it.content.isNotBlank()
            }?.id
        }
    }
    // 重试与继续生成只对最后一条消息开放：这会把该问答追加到列表末尾，作用在中间消息上
    // 等于把这一轮挪到别的提问后面，对话顺序和发给模型的历史会一起错乱。要重做更早的一轮，
    // 用用户消息下方的「编辑重发」，它按原位截断而不是追加。
    val retryableMessageId by remember {
        derivedStateOf {
            vm.messages.lastOrNull()?.takeIf {
                it.role == "assistant" && !it.isStreaming && (it.isError || it.isInterrupted || it.isStopped)
            }?.id
        }
    }
    val composerHeight = with(density) { composerHeightPx.toDp() }.coerceAtLeast(72.dp)
    val composerClearance = composerHeight + 18.dp
    LaunchedEffect(vm.activeConversationId) {
        autoFollow = true
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.size)
    }

    LaunchedEffect(userDragging) {
        if (userDragging) autoFollow = false
        else if (!listState.canScrollForward) autoFollow = true
    }

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty() && autoFollow && !userDragging) {
            listState.animateScrollToItem(vm.messages.size)
        }
    }

    LaunchedEffect(streamScrollSignals, vm.activeConversationId) {
        streamScrollSignals.conflate().collect {
            if (vm.messages.isNotEmpty() && autoFollow && !userDragging && !composerFocused) {
                try {
                    withFrameNanos { }
                    listState.scrollToItem(vm.messages.size)
                } catch (cancelled: CancellationException) {
                    // A gesture or detail reveal cancels this scroll, not the signal collector.
                    // Still propagate cancellation when the conversation itself leaves composition.
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }

    LaunchedEffect(composerFocused, vm.activeConversationId) {
        if (!composerFocused) return@LaunchedEffect
        // 只在用户本来就在看底部时才跟随键盘：回看历史时一碰输入框就被拽回末尾，
        // 阅读位置丢了，还得自己滚回来。
        autoFollow = listState.isCloseToBottom()

        snapshotFlow {
            imeInsets.getBottom(density) to imeAnimationTarget.getBottom(density)
        }
            .distinctUntilChanged()
            .collect { (imeBottom, imeTargetBottom) ->
                if (vm.messages.isNotEmpty() && autoFollow && !userDragging) {
                    // Preserve the accepted IME behaviour: the conversation follows every inset
                    // update so keyboard and content move together rather than serially.
                    listState.scrollToItem(vm.messages.size)
                }
            }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ChatHeader(
            vm, onOpenDrawer, onSwitchModel = { showSwitcher = true },
            showNavigation = questionIndices.size > 1,
            previousEnabled = questionTargets.previous != null,
            nextEnabled = questionTargets.next != null,
            onPrevious = {
                questionTargets.previous?.let { target ->
                    autoFollow = false
                    scope.launch { listState.animateScrollToItem(target) }
                }
            },
            onNext = {
                questionTargets.next?.let { target ->
                    autoFollow = false
                    scope.launch { listState.animateScrollToItem(target) }
                }
            }
        )
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            Box(
                Modifier.fillMaxSize()
            ) {
                if (vm.messages.isEmpty()) {
                    EmptyChat(
                        model = vm.chatProfile.chatModel,
                        userName = vm.appConfig.userName,
                        onConfigure = onOpenSettings,
                        modifier = Modifier.fillMaxSize().padding(bottom = composerClearance)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().onGloballyPositioned { chatAnchor = it.boundsInWindow() },
                        contentPadding = PaddingValues(
                            start = 22.dp,
                            end = 22.dp,
                            top = 24.dp,
                            bottom = composerClearance + 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(30.dp)
                    ) {
                        items(
                            items = vm.messages,
                            key = { it.id },
                            contentType = { it.role }
                        ) { message ->
                        CompositionLocalProvider(LocalImageFrames provides imageFrames) {
                        ChatMessageItem(
                            message = message,
                            canRegenerate = message.id == regeneratableMessageId,
                            canRetry = message.id == retryableMessageId,
                            onRetry = { vm.retryMessage(message.id) },
                            onRegenerate = { vm.regenerateMessage(message.id) },
                            onEditResend = {
                                val index = vm.messages.indexOfFirst { it.id == message.id }
                                val removedAfter = (vm.messages.size - index - 1).coerceAtLeast(0)
                                if (removedAfter > 0) pendingEditResend = message
                                else {
                                    vm.editUserMessage(message.id)
                                    composerFocusRequester.requestFocus()
                                }
                            },
                            onConfigureContext = { showContextDialog = true },
                            onSaveFile = { file ->
                                pendingFileExport = file
                                fileExportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = file.mimeType
                                    putExtra(Intent.EXTRA_TITLE, file.name)
                                })
                            },
                            onStreamingTextAdvanced = { streamScrollSignals.tryEmit(Unit) },
                            onDetailsExpanded = {
                                autoFollow = false
                                val index = vm.messages.indexOfFirst { it.id == message.id }
                                if (index >= 0) scope.launch {
                                    listState.revealConversationDetails(
                                        index,
                                        with(density) { (composerHeight + 12.dp).toPx() }
                                    )
                                }
                            }
                        )
                        }
                        }
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                }
            }
            ConversationReadingVeil(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(composerHeight))
            ConversationJumpToBottom(
                visible = jumpToBottomVisible, loading = vm.isChatLoading,
                onClick = { autoFollow = true; scope.launch { listState.animateScrollToItem(vm.messages.size) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = composerHeight + 8.dp),
                fadeMillisIn = 220,
                fadeMillisOut = 220
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { composerHeightPx = it.height }
                    .navigationBarsPadding()
            ) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    vm.pendingQuestion?.let { prompt ->
                        AskUserCard(
                            prompt = prompt,
                            draft = vm.questionDraft,
                            onDraft = vm::updateQuestionDraft,
                            onChoose = vm::chooseQuestionOption,
                            onSubmitDraft = vm::submitQuestionDraft
                        )
                    }
                    ChatComposer(
                    skillScope = "adchat-${vm.activeConversationId ?: "new"}",
                    focusRequester = composerFocusRequester,
                    value = vm.chatInput,
                    suggestion = vm.inputSuggestion,
                    onSuggestionClick = { vm.acceptSuggestion() },
                    attachments = vm.chatAttachments,
                    profileName = vm.chatProfile.name,
                    model = vm.chatProfile.chatModel,
                    loading = vm.isChatLoading,
                    attachmentLoading = vm.isChatAttachmentLoading,
                    reasoningEffort = vm.chatProfile.reasoningEffort,
                    apiMode = if (vm.chatProfile.usesResponses()) "responses" else "chat",
                    webSearchEnabled = vm.chatProfile.webSearchEnabled,
                    fileCreationEnabled = vm.chatProfile.fileCreationEnabled,
                    onModelClick = { showSwitcher = true },
                    onReasoningEffortChange = vm::setChatReasoningEffort,
                    onWebSearchToggle = vm::setChatWebSearchEnabled,
                    onFileCreationToggle = vm::setChatFileCreationEnabled,
                    contextUsage = contextUsage,
                    onSelectContextWindow = { window ->
                        vm.setModelContextWindow(vm.chatProfile.id, contextModel, window)
                    },
                    onCustomizeContext = { showContextDialog = true },
                    onResetContext = {
                        vm.setModelContextLimits(vm.chatProfile.id, contextModel, null)
                    },
                    onValueChange = vm::updateChatInput,
                    onPickImages = {
                        chatImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPickDocument = { chatDocumentPicker.launch(com.adong.adchat.data.DocumentImport.mimeTypes) },
                    onRemoveImage = vm::removeChatImage,
                    onSend = { autoFollow = true; vm.sendMessage() },
                    onStop = vm::stopGeneration,
                    onFocusChange = { composerFocused = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { formAnchor = it.boundsInWindow() }
                )
                }
            }
            // 三只小 Clawd + 大 Clawd：原生 Compose（像素引擎 + Kotlin 状态机），
            // 与原版行为逐段对照（tools/clawd/host-template.html 同源）。
            // 爪带真实占位 56dp 钉在输入框上沿（底边 = 地面 = composer 顶边），
            // 与原版 .clawd-pile{bottom:100%} 一致；空白处触摸穿透聊天页。
            ClawdHost(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = composerHeight),
                inputText = vm.chatInput,
                inputFocused = composerFocused,
                generating = vm.isChatLoading,
                lastText = vm.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.content ?: "",
                formAnchor = formAnchor,
                chatAnchor = chatAnchor,
                scrollTick = clawdScrollTick,
            )
        }
    }
    pendingEditResend?.let { candidate ->
        val index = vm.messages.indexOfFirst { it.id == candidate.id }
        val removedAfter = (vm.messages.size - index - 1).coerceAtLeast(0)
        // 三个后果都要摆出来：后续消息没了、图片会一起载回、输入框当前草稿被替换。
        val images = candidate.attachments.size
        AdConfirmDialog(
            title = "编辑重发？",
            message = buildString {
                append("这条消息之后的 $removedAfter 条对话会被移除（本地记录不保留）。")
                if (images > 0) append("其中的 $images 张图片会和正文一起载回输入框。")
                append("输入框当前的草稿会被这条消息的原文替换。")
            },
            confirmLabel = "编辑重发",
            dismissLabel = "取消",
            icon = Icons.Rounded.Edit,
            onConfirm = {
                pendingEditResend = null
                vm.editUserMessage(candidate.id)
                composerFocusRequester.requestFocus()
            },
            onDismiss = { pendingEditResend = null }
        )
    }
    if (showSwitcher) {
        QuickModelSwitcher(kind = RouteKind.Chat, vm = vm, onDismiss = { showSwitcher = false }, onManageApis = onOpenSettings)
    }
    if (showContextDialog) {
        val limits = vm.chatProfile.modelContexts[contextModel.trim()]
        ModelContextDialog(
            model = contextModel,
            initial = limits,
            onDismiss = { showContextDialog = false },
            onApply = { applied ->
                showContextDialog = false
                vm.setModelContextLimits(vm.chatProfile.id, contextModel, applied)
            }
        )
    }
}

/** 输入框上的 Clawd 层由 ui/clawd/ClawdHost.kt 接管：原生 Compose 宿主。 */

@Composable
private fun ChatHeader(
    vm: MainViewModel,
    onOpenDrawer: () -> Unit,
    onSwitchModel: () -> Unit,
    showNavigation: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    ConversationHeader(
        title = if (vm.messages.isEmpty()) "Aster" else vm.conversations.firstOrNull { it.id == vm.activeConversationId }?.title ?: "对话",
        model = vm.chatProfile.chatModel.ifBlank { "选择对话模型" },
        onOpenDrawer = onOpenDrawer, onModelClick = onSwitchModel
    ) {
        if (showNavigation) AsterIconButton(Icons.Rounded.MoreHoriz, "对话导航", { showActions = true })
        AsterIconButton(Icons.Rounded.AddComment, "新建对话", { vm.newConversation() }, enabled = !vm.isChatLoading)
    }
    if (showActions) AdActionSheet(
        title = "对话导航", subtitle = "按提问定位，继续阅读",
        actions = listOf(
            AdActionOption("previous", "回到上一个提问", icon = Icons.Rounded.KeyboardArrowUp, enabled = previousEnabled),
            AdActionOption("next", "前往下一个提问", icon = Icons.Rounded.KeyboardArrowDown, enabled = nextEnabled)
        ),
        onAction = { showActions = false; if (it.id == "previous") onPrevious() else onNext() },
        onDismiss = { showActions = false }
    )
}

@Composable
internal fun EmptyChat(model: String, userName: String, onConfigure: () -> Unit, modifier: Modifier = Modifier) {
    // 极简空态：星标 + 一句时段问候，仅保留未配置模型时的引导动作。
    // 问候按时段随机轮换：进入空态抽一句，跨时段再抽一次；每分钟只刷新时钟，重组不闪句。
    fun nowHour() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    var hourOfDay by remember { mutableIntStateOf(nowHour()) }
    var band by remember { mutableIntStateOf(greetingBand(hourOfDay)) }
    var roll by remember(band) { mutableIntStateOf((0..Int.MAX_VALUE).random()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            hourOfDay = nowHour().also { band = greetingBand(it) }
        }
    }

    // 星标是可点的实体：每点一次角速度加一档，惯性旋转并摩擦减速，越点越快。
    // 旋转通过 graphicsLayer 的延迟读取应用在绘制阶段，不触发每帧重组。
    val haptics = LocalHapticFeedback.current
    var angle by remember { mutableFloatStateOf(0f) }
    var spinSpeed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000.0).toFloat()
            last = now
            if (spinSpeed > 0f) {
                angle = (angle + spinSpeed * dt) % 360f
                spinSpeed = (spinSpeed - 80f * dt).coerceAtLeast(0f)
            }
        }
    }
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.padding(top = 88.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(88.dp)
                    .claudeBreathingFloat(amplitudeDp = 2.dp, durationMillis = 3200)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        spinSpeed = (spinSpeed + 120f).coerceAtMost(540f)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                contentAlignment = Alignment.Center
            ) {
                AsterMark(
                    Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = emptyChatGreeting(hourOfDay, userName, roll),
                style = MaterialTheme.typography.headlineSmall,
                color = Ink,
                textAlign = TextAlign.Center
            )
            if (model.isBlank()) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onConfigure, shape = RoundedCornerShape(16.dp)) { Text("连接第一个模型") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    canRegenerate: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onRegenerate: () -> Unit,
    onSaveFile: (ChatFileAttachment) -> Unit,
    onStreamingTextAdvanced: () -> Unit,
    onDetailsExpanded: () -> Unit,
    onEditResend: () -> Unit = {},
    onConfigureContext: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val user = message.role == "user"
    if (message.isContinuation) {
        // 「继续生成」的指令是应用代发的，用户从没打过这行字：渲染成居中状态条，
        // 不冒充用户气泡，也不占阅读流的位置。
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(color = SurfaceInset, shape = RoundedCornerShape(12.dp)) {
                Text(
                    "已请求从上一处继续生成",
                    color = MutedInk,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }
        return
    }
    val waitingForFirstToken = !user && message.content.isBlank() && message.isStreaming
    val context = LocalContext.current
    var showDetails by remember(message.id) { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        val messageWidth = if (user) {
            Modifier.widthIn(max = 520.dp)
        } else {
            Modifier.weight(1f).widthIn(max = 680.dp)
        }
        Column(
            messageWidth,
            horizontalAlignment = if (user) Alignment.End else Alignment.Start
        ) {
            if (user) {
                Surface(
                    // 右下的 8dp 小角是 iMessage「气泡尖角指向发送者」的语言；
                    // AI 消息不用气泡时，用户气泡不需要方向性，对称圆角更一致。
                    color = SurfaceInset, contentColor = Ink, shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, Hairline.copy(alpha = .55f))
                ) {
                    Column(Modifier.padding(7.dp)) {
                        if (message.attachments.isNotEmpty()) {
                            ConversationImages(message.attachments)
                        }
                        if (message.content.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    message.content,
                                    Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End) {
                    if (message.content.isNotBlank()) ConversationCopyAction(message.content)
                    ConversationMessageAction(Icons.Rounded.Edit, "编辑重发", onEditResend)
                }
            } else {
                if (!waitingForFirstToken) {
                    ConversationAuthor(error = message.isError)
                }
                ConversationReasoningPanel(
                    reasoning = message.reasoning,
                    streaming = message.isStreaming,
                    bodyStarted = message.content.isNotBlank()
                )
                if (message.toolActivities.isNotEmpty()) {
                    ToolActivitySummary(message.toolActivities)
                }
                if (waitingForFirstToken && message.reasoning.isBlank()) {
                    ConversationThinkingIndicator()
                } else {
                    RichMessageText(
                        content = message.content,
                        streaming = message.isStreaming,
                        error = message.isError,
                        onStreamingTextAdvanced = onStreamingTextAdvanced
                    )
                    if (showModelWorkingFoot(
                            message.isStreaming,
                            message.content.isBlank(),
                            waitingOnUser(message.toolActivities)
                        )
                    ) {
                        ModelWorkingFoot(modelWorkingCaption(message.toolActivities, message.isRecovering))
                    }
                }
                if (message.generatedFiles.isNotEmpty()) {
                    GeneratedFilesPanel(message.generatedFiles, onSaveFile)
                }
                if (message.citations.isNotEmpty()) {
                    CitationPanel(message.citations)
                }
                AnimatedVisibility(message.streamRecoveryCount > 0) {
                    StreamRecoveryStatus(
                        recovering = message.isRecovering,
                        streaming = message.isStreaming,
                        interrupted = message.isInterrupted,
                        stopped = message.isStopped,
                        count = message.streamRecoveryCount
                    )
                }
                if (message.isInterrupted || message.isStopped) {
                    Surface(
                        color = if (message.isStopped) SurfaceInset else AmberSoft,
                        contentColor = Ink,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (message.isStopped) Icons.Rounded.StopCircle else Icons.Rounded.CloudOff,
                                null,
                                Modifier.size(15.dp),
                                tint = if (message.isStopped) MutedInk else Accent
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (message.isStopped) "已停止生成，内容已保留" else "连接中断，已保留已生成内容",
                                color = MutedInk,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                AnimatedVisibility(!message.isStreaming && message.content.isNotBlank()) {
                    Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConversationCopyAction(message.content)
                            if (message.isError) {
                                if (canRetry) {
                                    ConversationMessageAction(Icons.Rounded.Refresh, "重试", onRetry)
                                }
                                if (message.content.contains("上下文预算")) {
                                    ConversationMessageAction(Icons.Rounded.Tune, "调整上下文", onConfigureContext, accent = true)
                                }
                            } else if (message.isInterrupted || message.isStopped) {
                                if (canRetry) {
                                    ConversationMessageAction(Icons.Rounded.PlayArrow, "继续生成", onRetry)
                                }
                            } else if (canRegenerate) {
                                ConversationMessageAction(Icons.Rounded.Refresh, "重新生成", onRegenerate, accent = true)
                            }
                            if (message.profileName.isNotBlank() || message.usage != null) {
                                ConversationMessageAction(
                                    Icons.Rounded.MoreHoriz,
                                    if (showDetails) "收起" else "详情",
                                    { showDetails = !showDetails; if (showDetails) onDetailsExpanded() }
                                )
                            }
                        }
                        if (showDetails) {
                            Column(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                                if (message.profileName.isNotBlank()) {
                                    Text(
                                        "${message.profileName} · ${message.model}",
                                        color = MutedInk,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (message.previousContent.isNotBlank()) {
                                    var showPrevious by remember(message.id) { mutableStateOf(false) }
                                    ConversationMessageAction(
                                        Icons.Rounded.History, if (showPrevious) "收起上一版" else "查看上一版",
                                        { showPrevious = !showPrevious }, accent = showPrevious
                                    )
                                    AnimatedVisibility(showPrevious) {
                                        Surface(
                                            color = SurfaceInset.copy(alpha = .7f), contentColor = Ink,
                                            shape = RoundedCornerShape(13.dp),
                                            modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                                        ) {
                                            Text(
                                                message.previousContent,
                                                Modifier.padding(11.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MutedInk
                                            )
                                        }
                                    }
                                }
                                message.usage?.takeIf { it.inputTokens > 0 || it.outputTokens > 0 || it.cachedTokens > 0 }?.let { usage ->
                                    TokenUsagePanel(usage = usage)
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
private fun StreamRecoveryStatus(
    recovering: Boolean,
    streaming: Boolean,
    interrupted: Boolean,
    stopped: Boolean,
    count: Int
) {
    val label = when {
        recovering -> "连接波动，正在安全续传（$count/1）"
        interrupted || stopped -> "已尝试安全续传 $count 次"
        streaming -> "连接已恢复，继续生成中"
        else -> "已自动续传 $count 次"
    }
    Surface(
        color = if (recovering) AccentSoft else SurfaceInset,
        contentColor = if (recovering) Accent else MutedInk,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (recovering) {
                AsterLoadingIndicator(size = 14.dp)
            } else {
                Icon(Icons.Rounded.Sync, null, Modifier.size(15.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ToolActivitySummary(activities: List<ChatToolActivity>) {
    var expanded by remember(activities.size) { mutableStateOf(false) }
    val latest = activities.last()
    val waiting = latest.name == ASK_USER_TOOL && latest.status == TOOL_STATUS_RUNNING
    val running = !waiting && activities.any { it.status == TOOL_STATUS_RUNNING }
    val failed = !waiting && activities.any { it.status == TOOL_STATUS_FAILED }
    val tint = when {
        waiting -> Sage
        failed -> Danger
        running -> Accent
        else -> Sage
    }
    val label = when {
        waiting -> "请从下面的选项里选一个"
        running -> latest.label
        failed -> latest.label
        activities.size == 1 -> latest.label
        else -> "已完成 ${activities.size} 项工具操作"
    }
    // 状态线性化：进行中是唯一的底色（过程态），完成态退成描边线，失败仍是危险底色。
    // 以前完成态也发一整个色块，和过程态、异常态视觉权重一样，分不清轻重。
    Surface(
        onClick = { if (activities.size > 1) expanded = !expanded },
        color = when {
            waiting -> SageSoft
            failed -> DangerSoft
            running -> AccentSoft.copy(alpha = .72f)
            else -> Color.Transparent
        },
        contentColor = Ink,
        shape = RoundedCornerShape(13.dp),
        border = if (waiting || running || failed) null else BorderStroke(1.dp, Hairline.copy(alpha = .8f)),
        // 高度动画只留 AnimatedVisibility 一层；工具状态在流式期间会反复刷新，
        // 再叠 animateContentSize 会让卡片高度持续微动画，和正文滚动叠加成噪声。
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    waiting -> Icon(Icons.Rounded.HelpOutline, null, Modifier.size(18.dp), tint = Sage)
                    running -> AsterLoadingIndicator(size = 17.dp, tint = tint)
                    failed -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = tint)
                    else -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = tint)
                }
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (running) MutedInk else tint, modifier = Modifier.weight(1f))
                if (activities.size > 1) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null,
                        Modifier.size(18.dp),
                        tint = MutedInk
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(150)) + expandVertically(tween(200, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(160))
            ) {
                Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    activities.forEach { activity ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (activity.name == "web_search") Icons.Rounded.TravelExplore else Icons.Rounded.Description,
                                null,
                                Modifier.size(15.dp),
                                tint = MutedInk
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(activity.label, style = MaterialTheme.typography.labelMedium, color = MutedInk, modifier = Modifier.weight(1f))
                            if (activity.status == TOOL_STATUS_COMPLETED) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = Sage)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneratedFilesPanel(files: List<ChatFileAttachment>, onSaveFile: (ChatFileAttachment) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            if (file.mimeType.substringBefore(';') == "text/html") {
                HtmlArtifactCard(code = file.content, filename = file.name)
                return@forEach
            }
            if (file.mimeType.substringBefore(';') == "image/svg+xml") {
                // SVG 不是完整 HTML：包一层可自适应缩放的壳再交给 HtmlArtifactCard。
                HtmlArtifactCard(
                    code = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body{margin:0;padding:8px;background:#fff;display:flex;justify-content:center}svg{max-width:100%;height:auto}</style></head><body>" +
                        file.content + "</body></html>",
                    filename = file.name
                )
                return@forEach
            }
            Surface(
                color = Surface,
                shape = RoundedCornerShape(15.dp),
                border = BorderStroke(1.dp, Hairline.copy(alpha = .8f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(start = 12.dp, end = 7.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Description, null, Modifier.size(18.dp), tint = Accent)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.labelLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatFileSize(file.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MutedInk)
                    }
                    TextButton(onClick = { onSaveFile(file) }) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("下载")
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationPanel(citations: List<ChatCitation>) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("来源", style = MaterialTheme.typography.labelMedium, color = MutedInk)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            citations.forEachIndexed { index, citation ->
                Surface(
                    onClick = { runCatching { uriHandler.openUri(citation.url) } },
                    color = SurfaceInset,
                    contentColor = Ink,
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Row(Modifier.widthIn(max = 260.dp).padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = Accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(7.dp))
                        Text(citation.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(15.dp), tint = MutedInk)
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
}

@Composable
private fun TokenUsagePanel(usage: com.adong.adchat.data.TokenUsage) {
    var expanded by remember { mutableStateOf(false) }
    val hitPercent = (usage.cacheHitRate * 100).toInt().coerceIn(0, 100)
    val hasCache = usage.cachedTokens > 0
    val hasCacheWrite = usage.cacheWriteTokens > 0
    val waitingForReuse = usage.cacheRequested && !hasCache && !hasCacheWrite
    val accentColor = when {
        hasCache -> Sage
        hasCacheWrite || (waitingForReuse && usage.cacheEligible) -> Accent
        else -> MutedInk
    }
    val title = when {
        hasCache -> "缓存命中 $hitPercent%"
        hasCacheWrite -> "已写入缓存 ${formatTokens(usage.cacheWriteTokens)}"
        waitingForReuse && !usage.cacheEligible -> "上下文较短，暂不使用缓存"
        waitingForReuse -> "已提交，下次请求可复用"
        else -> "本次未启用缓存"
    }
    val summary = if (hasCache) {
        "${formatTokens(usage.cachedTokens)} / ${formatTokens(usage.inputTokens)}"
    } else {
        "输入 ${formatTokens(usage.inputTokens)}"
    }
    // 结果态退到描边线：命中与否靠图标和文字的颜色区分，不再发一整块色块
    // 和过程态、异常态争视觉权重。
    Surface(
        onClick = { expanded = !expanded },
        color = Color.Transparent,
        contentColor = Ink,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, Hairline.copy(alpha = .8f)),
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        hasCache -> Icons.Rounded.Bolt
                        hasCacheWrite -> Icons.Rounded.Save
                        waitingForReuse -> Icons.Rounded.HourglassTop
                        else -> Icons.Rounded.DataUsage
                    },
                    null,
                    tint = accentColor,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(title, color = accentColor, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(summary, color = MutedInk, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MutedInk, modifier = Modifier.size(17.dp))
            }
            if (hasCache || hasCacheWrite) {
                Spacer(Modifier.height(7.dp))
                LinearProgressIndicator(
                    progress = { (if (hasCache) usage.cacheHitRate else usage.cacheWriteTokens.toFloat() / usage.inputTokens.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = if (hasCache) Sage else Accent,
                    trackColor = if (hasCache) SageSoft else AccentSoft
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UsageLine("输入", formatTokens(usage.inputTokens), "未缓存 ${formatTokens(usage.uncachedInputTokens)}")
                    UsageLine("缓存", formatTokens(usage.cachedTokens), if (hitPercent > 0) "命中 $hitPercent%" else "尚未命中")
                    if (usage.cacheWriteTokens > 0) {
                        UsageLine("写入", formatTokens(usage.cacheWriteTokens), "来自服务端")
                    } else if (waitingForReuse) {
                        UsageLine("状态", if (usage.cacheEligible) "等待复用" else "暂不使用缓存", null)
                    }
                    UsageLine("输出", formatTokens(usage.outputTokens), usage.reasoningTokens.takeIf { it > 0 }?.let { "推理 ${formatTokens(it)}" })
                    if (usage.timeToFirstTokenMs != null || usage.durationMs != null) {
                        UsageLine(
                            "速度",
                            usage.timeToFirstTokenMs?.let { "首字 ${formatDuration(it)}" } ?: "首字 -",
                            usage.durationMs?.let { "总用时 ${formatDuration(it)}" } ?: "总用时 -"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageLine(label: String, value: String, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MutedInk, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        detail?.let { Spacer(Modifier.width(8.dp)); Text(it, color = MutedInk, style = MaterialTheme.typography.labelSmall) }
    }
}

private fun formatTokens(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", value / 1_000_000f)
    value >= 1_000 -> String.format(Locale.ROOT, "%.1fK", value / 1_000f)
    else -> value.toString()
}

private fun formatDuration(ms: Long): String = if (ms < 1_000) "${ms}ms" else String.format(Locale.ROOT, "%.2fs", ms / 1_000f)

private data class BufferedStreamingText(
    val text: String,
    val animating: Boolean
)

@Composable
private fun rememberBufferedStreamingText(
    content: String,
    streaming: Boolean,
    onAdvanced: () -> Unit
): BufferedStreamingText {
    val latestContent by rememberUpdatedState(content)
    val latestOnAdvanced by rememberUpdatedState(onAdvanced)
    var displayed by remember { mutableStateOf(if (streaming) "" else content) }
    var animating by remember { mutableStateOf(streaming) }

    LaunchedEffect(streaming) {
        if (streaming) {
            animating = true
            while (true) {
                val target = latestContent
                if (!target.startsWith(displayed)) displayed = ""
                val backlog = target.length - displayed.length
                if (backlog <= 0) {
                    delay(22)
                    continue
                }
                val step = revealStep(backlog)
                val end = advanceCodePoints(target, displayed.length, step)
                displayed = target.substring(0, end)
                latestOnAdvanced()
                delay(revealDelay(displayed.lastOrNull(), backlog))
            }
        } else {
            animating = displayed != latestContent
            while (displayed != latestContent) {
                val target = latestContent
                if (!target.startsWith(displayed)) displayed = ""
                val backlog = target.length - displayed.length
                if (backlog <= 0) break
                val end = advanceCodePoints(target, displayed.length, revealStep(backlog).coerceAtLeast(8))
                displayed = target.substring(0, end)
                latestOnAdvanced()
                delay(16)
            }
            displayed = latestContent
            animating = false
            latestOnAdvanced()
        }
    }

    return BufferedStreamingText(
        text = if (!streaming && !animating) content else displayed,
        animating = streaming || animating
    )
}

private fun revealStep(backlog: Int): Int = when {
    backlog > 300 -> 22
    backlog > 140 -> 12
    backlog > 60 -> 6
    backlog > 24 -> 3
    else -> 2
}

private fun revealDelay(lastChar: Char?, backlog: Int): Long = when {
    backlog > 140 -> 16L
    backlog > 60 -> 22L
    lastChar == '\n' -> 58L
    lastChar in listOf('。', '！', '？', '!', '?') -> 48L
    lastChar in listOf('，', '、', '；', '：') -> 34L
    else -> 38L
}

private fun advanceCodePoints(text: String, start: Int, count: Int): Int {
    var cursor = start.coerceIn(0, text.length)
    repeat(count) {
        if (cursor >= text.length) return cursor
        cursor += Character.charCount(Character.codePointAt(text, cursor))
    }
    return cursor.coerceAtMost(text.length)
}

@Composable
private fun RichMessageText(
    content: String,
    streaming: Boolean,
    error: Boolean,
    onStreamingTextAdvanced: () -> Unit
) {
    val buffered = rememberBufferedStreamingText(content, streaming, onStreamingTextAdvanced)
    val visibleContent = buffered.text
    val writing = buffered.animating

    if (writing && containsMarkdownTable(visibleContent)) {
        StructuredMessageText(content = visibleContent, streaming = true, error = error)
        return
    }
    if (writing && visibleContent.contains("```")) {
        StructuredMessageText(content = visibleContent, streaming = true, error = error)
        return
    }
    if (writing) {
        StreamingProseText(content = visibleContent, error = error)
        return
    }
    StructuredMessageText(content = visibleContent, streaming = false, error = error)
}

@Composable
private fun StreamingProseText(content: String, error: Boolean) {
    LinkConfirm {
    streamingProseText(content, error)
    }
}

@Composable
private fun streamingProseText(content: String, error: Boolean) {
    val normalized = content.replace("\r\n", "\n")
    // 尾段单独渲染，不把整段重新分词：生成期间每个 token 都重排会让长回答明显掉帧，
    // key(index, paragraph) 还会让尾段视图反复销毁重建。
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = READING_BODY_FONT_SP.sp,
        lineHeight = READING_BODY_LINE_SP.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    )
    val splitAt = normalized.lastIndexOf("\n\n")
    val head = if (splitAt >= 0) normalized.substring(0, splitAt) else ""
    val tail = if (splitAt >= 0) normalized.substring(splitAt + 2) else normalized
    Column(verticalArrangement = Arrangement.spacedBy(READING_BLOCK_GAP_DP.dp)) {
        if (head.isBlank() && tail.isBlank()) {
            AsterWritingCursorLine(error)
            return@Column
        }
        if (head.isNotBlank()) {
            MarkdownTextBlock(raw = head, showCursor = false, error = error)
        }
        if (tail.isNotEmpty()) {
            // 尾段还在长：用轻量行内渲染 + 光标，不跑完整块解析，也不需要 key() 重建。
            ReadableText(
                inlineMarkdown(tail),
                bodyStyle,
                if (error) Danger else Ink,
                selectable = true,
                showWritingCursor = true
            )
        } else {
            AsterWritingCursorLine(error)
        }
    }
}

@Composable
internal fun StructuredMessageText(
    content: String,
    streaming: Boolean,
    error: Boolean,
    htmlScriptsAllowed: Boolean = true
) {
    LinkConfirm {
    val parts = remember(content) { content.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        parts.forEachIndexed { index, raw ->
            if (raw.isBlank() && index != parts.lastIndex) return@forEachIndexed
            if (index % 2 == 1) {
                val lines = raw.trim('\n').lines()
                val language = lines.firstOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_+.#-]{1,20}")) }
                val code = if (language != null) lines.drop(1).joinToString("\n") else raw.trim('\n')
                if (language?.lowercase() in setOf("html", "htm")) {
                    HtmlArtifactCard(
                        code = code,
                        ready = !streaming && index < parts.lastIndex,
                        allowScripts = htmlScriptsAllowed
                    )
                } else if (language?.lowercase() == "mermaid") {
                    MermaidArtifactCard(
                        code = code,
                        ready = !streaming && index < parts.lastIndex
                    )
                } else CodeBlock(
                    language = language,
                    code = if (streaming && index == parts.lastIndex) "$code  ▍" else code,
                    selectable = !streaming
                )
            } else {
                MarkdownTextBlock(
                    raw = raw,
                    showCursor = streaming && index == parts.lastIndex,
                    error = error
                )
            }
        }
    }
    }
}

@Composable
private fun CodeBlock(language: String?, code: String, selectable: Boolean) {
    val context = LocalContext.current
    Surface(color = Night, contentColor = Color(0xFFF4F0EA), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 5.dp, top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language?.uppercase() ?: "CODE", color = Color(0xFFAAA49D), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("code", code)) },
                    modifier = Modifier.size(48.dp)
                ) { Icon(Icons.Outlined.ContentCopy, "复制代码", Modifier.size(16.dp), tint = Color(0xFFCCC6BE)) }
            }
            val contentBlock: @Composable () -> Unit = {
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 13.dp, end = 13.dp, bottom = 13.dp, top = 3.dp))
            }
            if (selectable) SelectionContainer { contentBlock() } else contentBlock()
        }
    }
}

private data class MarkdownBlock(
    val kind: Int,
    val text: String,
    val level: Int = 0,
    val marker: String = "",
    val table: MarkdownTable? = null
)

private val MARKDOWN_QUOTE_PREFIX = Regex("""^\s*>\s?""")

private fun normalizeQuoteHeavyMarkdown(text: String): String {
    val lines = text.lines()
    val meaningful = lines.filter { it.isNotBlank() }
    if (meaningful.isEmpty()) return text
    val quoteCount = meaningful.count { MARKDOWN_QUOTE_PREFIX.containsMatchIn(it) }
    val quoteHeavy = quoteCount >= 3 && quoteCount * 10 >= meaningful.size * 6
    if (!quoteHeavy) return text
    return lines.joinToString("\n") { line ->
        if (MARKDOWN_QUOTE_PREFIX.containsMatchIn(line)) line.replaceFirst(MARKDOWN_QUOTE_PREFIX, "") else line
    }
}

private fun isSceneBreakLine(line: String): Boolean = isSceneBreak(line)

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val result = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    val quote = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotBlank()) result += MarkdownBlock(0, paragraph.toString().trim())
        paragraph.clear()
    }
    fun flushQuote() {
        if (quote.isNotBlank()) result += MarkdownBlock(3, quote.toString().trim())
        quote.clear()
    }
    val lines = normalizeQuoteHeavyMarkdown(text).lines()
    var lineIndex = 0
    while (lineIndex < lines.size) {
        val tableMatch = parseMarkdownTableAt(lines, lineIndex)
        if (tableMatch != null) {
            flushParagraph()
            flushQuote()
            result += MarkdownBlock(kind = 4, text = "", table = tableMatch.table)
            lineIndex = tableMatch.nextLineIndex
            continue
        }

        val sourceLine = lines[lineIndex]
        val line = sourceLine.trimEnd()
        when {
            line.isBlank() -> {
                flushParagraph()
                flushQuote()
            }
            isSceneBreakLine(line) -> {
                flushParagraph()
                flushQuote()
                result += MarkdownBlock(5, "")
            }
            isChapterHeading(line.trim()) -> {
                flushParagraph()
                flushQuote()
                result += MarkdownBlock(1, line.trim(), level = 2)
            }
            line.matches(Regex("""^#{1,6}\s+.*""")) -> {
                flushParagraph()
                flushQuote()
                val level = line.takeWhile { it == '#' }.length
                result += MarkdownBlock(1, line.drop(level).trimStart(), level = level)
            }
            line.startsWith("- ") || (line.startsWith("* ") && !isStarredProseLine(line)) -> {
                flushParagraph()
                flushQuote()
                result += MarkdownBlock(2, line.drop(2).trim(), marker = "•")
            }
            line.matches(Regex("""^\d+[.)]\s+.*""")) -> {
                flushParagraph()
                flushQuote()
                result += MarkdownBlock(2, line.substringAfter(' ').trim(), marker = line.substringBefore(' ').trim())
            }
            MARKDOWN_QUOTE_PREFIX.containsMatchIn(line) -> {
                flushParagraph()
                joinProseLine(quote, line.replaceFirst(MARKDOWN_QUOTE_PREFIX, "").trimEnd())
            }
            else -> {
                flushQuote()
                joinProseLine(paragraph, line.trim())
            }
        }
        lineIndex++
    }
    flushParagraph()
    flushQuote()
    return result
}

@Composable
private fun MarkdownTextBlock(raw: String, showCursor: Boolean, error: Boolean) {
    val pieces = remember(raw) { splitInlineImages(raw) }
    Column(verticalArrangement = Arrangement.spacedBy(READING_BLOCK_GAP_DP.dp)) {
        pieces.forEachIndexed { pieceIndex, piece ->
            when (piece) {
                is InlinePiece.RemoteImage -> ChatRemoteImage(piece.url, piece.alt)
                is InlinePiece.PixivArtwork -> PixivArtworkPreview(piece.id, piece.pageUrl)
                is InlinePiece.Prose -> MarkdownProseBlock(
                    raw = piece.text,
                    showCursor = showCursor && pieceIndex == pieces.lastIndex,
                    error = error
                )
            }
        }
    }
}

@Composable
private fun PixivArtworkPreview(id: String, pageUrl: String) {
    var preview by remember(id) { mutableStateOf<String?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        val found = withContext(Dispatchers.IO) { runCatching { fetchPixivPreviewUrl(id) }.getOrNull() }
        if (found != null) preview = found else failed = true
    }
    val confirm = LocalConfirmLink.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(280.dp).height(210.dp).clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            when {
                preview != null -> ChatRemoteImage(preview!!, "Pixiv $id", lockSlot = true)
                !failed -> Text("正在读取 Pixiv 预览…", color = MutedInk, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            pageUrl,
            color = NameInk,
            textDecoration = TextDecoration.Underline,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clickable { confirm(pageUrl) }
        )
    }
}

private val LocalImageFrames = staticCompositionLocalOf<MutableMap<String, Pair<Int, Int>>> { mutableMapOf() }

@Composable
private fun ChatRemoteImage(url: String, alt: String, lockSlot: Boolean = false) {
    val context = LocalContext.current
    val frames = LocalImageFrames.current
    val known = remember(url) { frames[url] }
    var bytes by remember(url) { mutableStateOf<ByteArray?>(null) }
    var directFailed by remember(url) { mutableStateOf(false) }
    var downloadFailed by remember(url) { mutableStateOf(false) }
    var zoomed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, directFailed) {
        if (!directFailed || bytes != null || downloadFailed) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { runCatching { downloadChatImage(url) }.getOrNull() }
        if (loaded != null) bytes = loaded else downloadFailed = true
    }
    val shown = bytes ?: url
    val request = ImageRequest.Builder(context).data(shown).crossfade(false)
    if (bytes == null) chatImageHeaders(url).forEach { (name, value) -> request.addHeader(name, value) }
    val model = request.build()
    if (zoomed) ZoomedChatImage(model, alt.ifBlank { "图片" }) { zoomed = false }
    val fitted = known?.let { fittedImageDp(it.first, it.second, maxWidth = 440f, maxHeight = 480f) }
    val frame = when {
        lockSlot -> Modifier.fillMaxSize()
        fitted != null -> Modifier.size(fitted.first.dp, fitted.second.dp)
        else -> Modifier.width(280.dp).height(210.dp)
    }
    Box(frame.clip(RoundedCornerShape(14.dp))) {
        when {
            bytes == null && directFailed && downloadFailed -> {
                val confirm = LocalConfirmLink.current
                Text(
                    url,
                    color = NameInk,
                    textDecoration = TextDecoration.Underline,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp).clickable { confirm(url) }
                )
            }
            bytes == null && directFailed -> Text(
                "正在下载图片…",
                color = MutedInk,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.Center)
            )
            else -> AsyncImage(
                model = model,
                contentDescription = alt.ifBlank { "图片" },
                modifier = Modifier.fillMaxSize().clickable { zoomed = true },
                contentScale = ContentScale.Fit,
                onSuccess = { state ->
                    if (url !in frames) {
                        val width = state.painter.intrinsicSize.width
                        val height = state.painter.intrinsicSize.height
                        if (width.isFinite() && height.isFinite() && width > 0f && height > 0f) {
                            frames[url] = width.toInt() to height.toInt()
                        }
                    }
                },
                onError = { if (bytes == null) directFailed = true }
            )
        }
    }
}

@Composable
private fun ZoomedChatImage(model: ImageRequest, description: String, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .94f)), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = model,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }.transformable(transform)
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text("关闭", color = Color.White)
            }
        }
    }
}

@Composable
private fun MarkdownProseBlock(raw: String, showCursor: Boolean, error: Boolean) {
    val blocks = remember(raw) { parseMarkdownBlocks(raw) }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = READING_BODY_FONT_SP.sp,
        lineHeight = READING_BODY_LINE_SP.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    )
    Column(verticalArrangement = Arrangement.spacedBy(READING_BLOCK_GAP_DP.dp)) {
        if (blocks.isEmpty() && showCursor) {
            AsterWritingCursorLine(error)
        }
        blocks.forEachIndexed { index, block ->
            val hasCursor = showCursor && index == blocks.lastIndex
            when (block.kind) {
                1 -> ReadableText(
                    text = inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(
                            fontSize = 23.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        2 -> MaterialTheme.typography.titleMedium.copy(
                            fontSize = 20.sp,
                            lineHeight = 29.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        3 -> bodyStyle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                        else -> bodyStyle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                    },
                    color = if (error) Danger else Ink,
                    selectable = !showCursor,
                    showWritingCursor = hasCursor,
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else 16.dp, bottom = 4.dp)
                )
                2 -> MarkdownListRow(block.marker, block.text, error, selectable = !showCursor, showCursor = hasCursor)
                3 -> Row(
                    // 引用是引用，不是正文：整片 AccentSoft 底色把它弄得比正文更重，
                    // 只留左侧竖线 + 稍淡的文字，层级才对。
                    Modifier.fillMaxWidth().padding(start = 14.dp).height(IntrinsicSize.Min)
                ) {
                    Box(
                        Modifier.width(3.dp).fillMaxHeight().clip(CircleShape)
                            .background(if (error) Danger.copy(alpha = .5f) else Accent.copy(alpha = .5f))
                    )
                    Spacer(Modifier.width(13.dp))
                    ReadableText(
                        inlineMarkdown(block.text),
                        bodyStyle,
                        if (error) Danger else Ink.copy(alpha = .78f),
                        selectable = !showCursor,
                        showWritingCursor = hasCursor,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                    )
                }
                4 -> block.table?.let {
                    MarkdownTableBlock(table = it, selectable = !showCursor, error = error)
                    if (hasCursor) AsterWritingCursorLine(error)
                }
                5 -> {
                    SceneBreak()
                    if (hasCursor) AsterWritingCursorLine(error)
                }
                else -> ReadableText(
                    inlineMarkdown(block.text),
                    bodyStyle,
                    if (error) Danger else Ink,
                    selectable = !showCursor,
                    showWritingCursor = hasCursor
                )
            }
        }
    }
}

@Composable
private fun SceneBreak() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "•   •   •",
            // 场景分隔符是装饰，不承载语义，可以弱；但别弱到看不见。
            color = MutedInk.copy(alpha = .72f),
            style = MaterialTheme.typography.labelMedium,
            letterSpacing = 2.sp
        )
    }
}

@Composable
private fun MarkdownTableBlock(table: MarkdownTable, selectable: Boolean, error: Boolean) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val naturalWidths = remember(table) { estimateTableColumnWidths(table) }
        val dividerWidth = (table.columnCount - 1).coerceAtLeast(0).dp
        val naturalTotal = naturalWidths.fold(dividerWidth) { total, width -> total + width }
        val widths = if (naturalTotal < maxWidth) {
            val extraPerColumn = (maxWidth - naturalTotal) / table.columnCount.coerceAtLeast(1)
            naturalWidths.map { it + extraPerColumn }
        } else {
            naturalWidths
        }
        val totalWidth = widths.fold(dividerWidth) { total, width -> total + width }
        val horizontallyScrollable = totalWidth > maxWidth

        Surface(
            color = Surface,
            contentColor = if (error) Danger else Ink,
            shape = RoundedCornerShape(17.dp),
            border = BorderStroke(1.dp, if (error) Danger.copy(alpha = .24f) else Hairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 5.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (error) DangerSoft else AccentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.TableChart,
                            contentDescription = null,
                            tint = if (error) Danger else Accent,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("数据表格", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            buildString {
                                append("${table.columnCount} 列 · ${table.rows.size} 行")
                                if (horizontallyScrollable) append(" · 左右滑动查看")
                            },
                            color = MutedInk,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (horizontallyScrollable) {
                        Icon(
                            Icons.Rounded.SwapHoriz,
                            contentDescription = "左右滑动查看完整表格",
                            tint = Accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    IconButton(
                        onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(
                                    android.content.ClipData.newPlainText("Aster 表格", markdownTableToTsv(table))
                                )
                            Toast.makeText(context, "表格已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, "复制表格", Modifier.size(17.dp), tint = MutedInk)
                    }
                }
                HorizontalDivider(color = Hairline)
                Column(
                    Modifier.horizontalScroll(scrollState).width(totalWidth)
                ) {
                    MarkdownTableRow(
                        cells = table.header,
                        widths = widths,
                        alignments = table.alignments,
                        header = true,
                        striped = false,
                        selectable = selectable,
                        error = error
                    )
                    if (table.rows.isNotEmpty()) HorizontalDivider(color = Hairline)
                    table.rows.forEachIndexed { rowIndex, row ->
                        MarkdownTableRow(
                            cells = row,
                            widths = widths,
                            alignments = table.alignments,
                            header = false,
                            striped = rowIndex % 2 == 1,
                            selectable = selectable,
                            error = error
                        )
                        if (rowIndex != table.rows.lastIndex) HorizontalDivider(color = Hairline.copy(alpha = .78f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableRow(
    cells: List<String>,
    widths: List<Dp>,
    alignments: List<MarkdownTableAlignment>,
    header: Boolean,
    striped: Boolean,
    selectable: Boolean,
    error: Boolean
) {
    Row(
        Modifier.background(
            when {
                header && error -> DangerSoft
                header -> AccentSoft.copy(alpha = .58f)
                striped -> SurfaceInset
                else -> Surface
            }
        ).height(IntrinsicSize.Min)
    ) {
        widths.indices.forEach { columnIndex ->
            val value = cells.getOrElse(columnIndex) { "" }
            val display = value.ifBlank { "—" }
            val textAlign = when (alignments.getOrElse(columnIndex) { MarkdownTableAlignment.Start }) {
                MarkdownTableAlignment.Start -> TextAlign.Start
                MarkdownTableAlignment.Center -> TextAlign.Center
                MarkdownTableAlignment.End -> TextAlign.End
            }
            Box(
                Modifier.width(widths[columnIndex])
                    .defaultMinSize(minHeight = if (header) 46.dp else 44.dp)
                    .padding(horizontal = 11.dp, vertical = if (header) 11.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                val cellText: @Composable () -> Unit = {
                    Text(
                        text = inlineMarkdown(display),
                        modifier = Modifier.fillMaxWidth(),
                        color = when {
                            error -> Danger
                            value.isBlank() -> MutedInk.copy(alpha = .72f)
                            else -> Ink
                        },
                        style = if (header) {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                        } else {
                            MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp)
                        },
                        textAlign = textAlign
                    )
                }
                if (selectable) SelectionContainer { cellText() } else cellText()
            }
            if (columnIndex != widths.lastIndex) {
                VerticalDivider(Modifier.fillMaxHeight(), color = Hairline.copy(alpha = .9f))
            }
        }
    }
}

private fun estimateTableColumnWidths(table: MarkdownTable): List<Dp> = table.header.indices.map { columnIndex ->
    val longestUnits = sequence {
        yield(table.header.getOrElse(columnIndex) { "" })
        table.rows.forEach { yield(it.getOrElse(columnIndex) { "" }) }
    }.maxOfOrNull(::tableTextVisualUnits) ?: 0f
    (36f + longestUnits.coerceAtMost(30f) * 7.1f).coerceIn(104f, 244f).dp
}

private fun tableTextVisualUnits(value: String): Float {
    val plain = value
        .replace(Regex("[*_~`]"), "")
        .lineSequence()
        .maxByOrNull { it.length }
        .orEmpty()
    var units = 0f
    plain.forEach { char ->
        units += when {
            char.code >= 0x2E80 -> 1.78f
            char.isUpperCase() -> 1.08f
            char.isWhitespace() -> .56f
            else -> .92f
        }
    }
    return units
}

@Composable
private fun MarkdownListRow(
    marker: String,
    text: String,
    error: Boolean,
    selectable: Boolean,
    showCursor: Boolean
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = READING_BODY_FONT_SP.sp,
        lineHeight = READING_BODY_LINE_SP.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            marker,
            color = if (error) Danger else MutedInk,
            style = bodyStyle,
            modifier = Modifier.widthIn(min = 24.dp)
        )
        ReadableText(
            inlineMarkdown(text),
            bodyStyle,
            if (error) Danger else Ink,
            selectable,
            Modifier.weight(1f),
            showWritingCursor = showCursor
        )
    }
}

private const val ASTER_CURSOR_TAG = "aster-writing-cursor"

@Composable
private fun ReadableText(
    text: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    selectable: Boolean,
    modifier: Modifier = Modifier,
    showWritingCursor: Boolean = false
) {
    val rendered = remember(text, showWritingCursor) {
        if (!showWritingCursor) text else buildAnnotatedString {
            append(text)
            append(" ")
            appendInlineContent(ASTER_CURSOR_TAG, "✦")
        }
    }
    val inlineContent = if (showWritingCursor) {
        mapOf(
            ASTER_CURSOR_TAG to InlineTextContent(
                Placeholder(
                    width = 14.sp,
                    height = 14.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                AsterMark(Modifier.fillMaxSize().graphicsLayer { alpha = 0.72f })
            }
        )
    } else {
        emptyMap()
    }
    val contentBlock: @Composable () -> Unit = {
        Text(
            text = rendered,
            style = style,
            color = color,
            modifier = modifier,
            inlineContent = inlineContent
        )
    }
    if (selectable) SelectionContainer { contentBlock() } else contentBlock()
}

@Composable
private fun AsterWritingCursorLine(error: Boolean) {
    ReadableText(
        text = AnnotatedString(""),
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = READING_BODY_FONT_SP.sp,
            lineHeight = READING_BODY_LINE_SP.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.sp
        ),
        color = if (error) Danger else Ink,
        selectable = false,
        showWritingCursor = true
    )
}

@Composable
private fun ModelWorkingFoot(caption: String) {
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "model-working")
    val beat by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(720, easing = LinearEasing), RepeatMode.Restart),
        label = "model-working-beat"
    )
    val lift = with(density) { 4.dp.toPx() } * sin(PI * beat).toFloat()
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AsterMark(
            Modifier.size(18.dp).graphicsLayer { translationY = -lift }
        )
        Spacer(Modifier.width(8.dp))
        Text(caption, color = MutedInk, style = MaterialTheme.typography.labelLarge)
    }
}

private val LocalConfirmLink = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
private fun LinkConfirm(content: @Composable () -> Unit) {
    var pending by remember { mutableStateOf<String?>(null) }
    val handler = LocalUriHandler.current
    CompositionLocalProvider(LocalConfirmLink provides { pending = it }) { content() }
    val url = pending ?: return
    AlertDialog(
        onDismissRequest = { pending = null },
        title = { Text("打开外部链接") },
        text = { Text("即将离开 Aster，在浏览器中打开：\n$url") },
        confirmButton = {
            TextButton(onClick = {
                pending = null
                runCatching { handler.openUri(url) }
            }) { Text("打开") }
        },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
    )
}

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val base = buildAnnotatedString {
        readingSpans(text).forEach { span ->
            when (span) {
                is ReadingSpan.Name -> {
                    pushStyle(SpanStyle(color = NameInk, fontWeight = FontWeight.Medium))
                    append(span.value)
                    pop()
                }
                is ReadingSpan.Text -> append(basicInlineMarkdown(span.value))
            }
        }
    }
    val source = base.text
    return buildAnnotatedString {
        append(base)
        var cursor = 0
        while (cursor < source.length) {
            val start = source.indexOf('『', cursor)
            if (start < 0) break
            val end = source.indexOf('』', start + 1)
            if (end < 0) break
            addStyle(
                SpanStyle(color = Accent),
                start = start,
                end = end + 1
            )
            cursor = end + 1
        }
    }
}

@Composable
private fun basicInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    val confirmLink = LocalConfirmLink.current
    val linkStyle = TextLinkStyles(SpanStyle(color = NameInk, textDecoration = TextDecoration.Underline))
    val tokens = listOf("**", "__", "~~", "`", "*", "_")
    while (index < text.length) {
        val link = linkAt(text, index)
        if (link != null) {
            pushLink(LinkAnnotation.Clickable(link.url, linkStyle) { _ -> confirmLink(link.url) })
            append(link.label)
            pop()
            index = link.end
            continue
        }
        val token = tokens.firstOrNull { text.startsWith(it, index) }
        if (token != null) {
            val end = text.indexOf(token, index + token.length)
            val inner = if (end > index + token.length) text.substring(index + token.length, end) else null
            val allowed = inner != null && (token !in setOf("*", "_") || emphasisSpanAllowed(inner))
            if (inner != null && allowed) {
                val style = when (token) {
                    "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "`" -> SpanStyle(fontFamily = FontFamily.Monospace, color = Accent)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                pushStyle(style)
                append(basicInlineMarkdown(text.substring(index + token.length, end)))
                pop()
                index = end + token.length
            } else {
                append(token); index += token.length
            }
        } else {
            val target = nextPlainEnd(text, index, tokens)
            append(text.substring(index, target))
            index = target
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskUserCard(
    prompt: AskUserPrompt,
    draft: String,
    onDraft: (String) -> Unit,
    onChoose: (String) -> Unit,
    onSubmitDraft: () -> Unit
) {
    var writing by remember(prompt.question, prompt.options) { mutableStateOf(false) }
    Surface(
        color = Surface,
        contentColor = Ink,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(prompt.question, style = MaterialTheme.typography.titleSmall)
            prompt.options.forEach { option ->
                val custom = option == ASK_USER_CUSTOM_OPTION
                if (custom && writing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraft,
                        placeholder = { Text(option) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = onSubmitDraft, enabled = draft.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
                        Text("发送")
                    }
                } else {
                    OutlinedButton(
                        onClick = { if (custom) writing = true else onChoose(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(option) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatComposer(
    skillScope: String,
    focusRequester: FocusRequester,
    value: String,
    suggestion: String?,
    onSuggestionClick: () -> Unit,
    attachments: List<ChatImageAttachment>,
    profileName: String,
    model: String,
    loading: Boolean,
    attachmentLoading: Boolean,
    reasoningEffort: String,
    apiMode: String,
    webSearchEnabled: Boolean,
    fileCreationEnabled: Boolean,
    onModelClick: () -> Unit,
    onReasoningEffortChange: (String) -> Unit,
    onWebSearchToggle: (Boolean) -> Unit,
    onFileCreationToggle: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
    onPickImages: () -> Unit,
    onPickDocument: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    contextUsage: ContextUsage,
    onSelectContextWindow: (Int) -> Unit,
    onCustomizeContext: () -> Unit,
    onResetContext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = LocalFocusManager.current
    var showEffortSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }
    val effortChoices = remember(model) { ReasoningPolicy.choices(model) }
    val currentEffort = remember(model, reasoningEffort) { ReasoningPolicy.choiceOf(model, reasoningEffort) }
    ConversationComposer(
        value = value, attachments = attachments, loading = loading, attachmentLoading = attachmentLoading,
        onValueChange = onValueChange, onOptionsClick = { showToolsSheet = true }, onRemoveImage = onRemoveImage,
        onSend = onSend, onStop = onStop, onFocusChange = onFocusChange,
        suggestion = suggestion, onSuggestionClick = onSuggestionClick,
        focusRequester = focusRequester, modifier = modifier,
        contextAction = {
            ComposerContextRing(
                percent = contextUsage.percent,
                configured = contextUsage.configured,
                overflow = contextUsage.overflow,
                onClick = {
                    focus.clearFocus()
                    showContextSheet = true
                }
            )
        },
        reasoningAction = if (effortChoices.isEmpty()) null else {
            {
                ComposerReasoningButton(
                    label = compactEffortLabel(currentEffort?.label ?: "默认"),
                    detailed = currentEffort?.id != ReasoningPolicy.DEFAULT,
                    onClick = {
                        focus.clearFocus()
                        showEffortSheet = true
                    }
                )
            }
        },
    )
    if (showToolsSheet) {
        ChatToolsSheet(
            skillScope = skillScope,
            profileName = profileName,
            model = model,
            apiMode = apiMode,
            webSearchEnabled = webSearchEnabled,
            fileCreationEnabled = fileCreationEnabled,
            canPickImages = !loading && !attachmentLoading && attachments.size < 4,
            canPickDocuments = !loading && !attachmentLoading,
            onPickImages = { showToolsSheet = false; onPickImages() },
            onPickDocument = { showToolsSheet = false; onPickDocument() },
            onModelClick = { showToolsSheet = false; onModelClick() },
            onReasoningClick = { showToolsSheet = false; showEffortSheet = true },
            onWebSearchToggle = onWebSearchToggle,
            onFileCreationToggle = onFileCreationToggle,
            reasoningSupported = effortChoices.isNotEmpty(),
            onDismiss = { showToolsSheet = false }
        )
    }
    if (showContextSheet) {
        ComposerContextSheet(
            model = contextUsage.model,
            limits = contextUsage.limits,
            totalTokens = contextUsage.totalTokens,
            systemTokens = contextUsage.systemTokens,
            historyTokens = contextUsage.historyTokens,
            attachmentTokens = contextUsage.attachmentTokens,
            overheadTokens = contextUsage.overheadTokens,
            omittedTurns = contextUsage.omittedTurns,
            lastRequestInputTokens = contextUsage.lastRequestInputTokens,
            lastRequestOutputTokens = contextUsage.lastRequestOutputTokens,
            lastRequestReasoningTokens = contextUsage.lastRequestReasoningTokens,
            onSelectWindow = { window ->
                // 立刻收起面板：用户回到输入框就能看到进度圈变化，同时提示条也能露出来。
                focus.clearFocus()
                showContextSheet = false
                onSelectContextWindow(window)
            },
            onCustomize = { showContextSheet = false; onCustomizeContext() },
            onReset = {
                focus.clearFocus()
                showContextSheet = false
                onResetContext()
            },
            onDismiss = { showContextSheet = false }
        )
    }
    if (showEffortSheet) {
        val responsesApi = apiMode == "responses"
        val contract = ReasoningPolicy.contract(model, responsesApi)
        AdSelectionSheet(
            title = "思考强度",
            subtitle = if (effortChoices.isEmpty()) "当前模型不支持调节思考强度" else "$model · ${contract.field}",
            options = effortChoices.map { choice ->
                AdChoiceOption(
                    id = choice.id,
                    title = choice.label,
                    subtitle = choice.hint,
                    icon = when (choice.id) {
                        "off", "none" -> Icons.Rounded.Bolt
                        "on" -> Icons.Rounded.Psychology
                        "low" -> Icons.Rounded.Bolt
                        "medium" -> Icons.Rounded.Balance
                        "high" -> Icons.Rounded.Psychology
                        "xhigh" -> Icons.Rounded.AccountTree
                        "max" -> Icons.Rounded.AutoAwesome
                        else -> Icons.Rounded.AutoAwesome
                    },
                    badge = when (choice.id) {
                        "low" -> "更快"
                        "medium" -> "推荐"
                        "high" -> "复杂任务"
                        "xhigh" -> "深度推理"
                        "max" -> "最大预算"
                        else -> null
                    }
                )
            },
            selectedId = currentEffort?.id,
            onSelect = { onReasoningEffortChange(it.id); showEffortSheet = false },
            onDismiss = { showEffortSheet = false },
            searchEnabled = false,
            headerIcon = Icons.Rounded.Psychology
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatToolsSheet(
    skillScope: String,
    profileName: String,
    model: String,
    apiMode: String,
    webSearchEnabled: Boolean,
    fileCreationEnabled: Boolean,
    canPickImages: Boolean,
    canPickDocuments: Boolean,
    onPickImages: () -> Unit,
    onPickDocument: () -> Unit,
    onModelClick: () -> Unit,
    onReasoningClick: () -> Unit,
    onWebSearchToggle: (Boolean) -> Unit,
    onFileCreationToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    reasoningSupported: Boolean = true
) {
    AsterOptionsSheet(
        title = "输入选项",
        subtitle = "$profileName · ${model.ifBlank { "未选择模型" }}",
        onDismiss = onDismiss
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConversationSheetAction(Icons.Rounded.AddPhotoAlternate, "图片", canPickImages, onPickImages, Modifier.weight(1f), "最多 4 张")
            ConversationSheetAction(Icons.Rounded.AttachFile, "文件", canPickDocuments, onPickDocument, Modifier.weight(1f), "文本 / DOCX / PDF")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConversationSheetAction(Icons.Rounded.Hub, "模型", true, onModelClick, Modifier.weight(1f), "切换本轮使用的模型")
            ConversationSheetAction(
                icon = Icons.Rounded.Psychology,
                label = "思考",
                enabled = reasoningSupported,
                onClick = onReasoningClick,
                modifier = Modifier.weight(1f),
                detail = if (reasoningSupported) "调整回答的思考强度" else "当前模型不支持"
            )
        }
        SkillPickerEntry(skillScope, enabled = canPickDocuments)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("工具", style = MaterialTheme.typography.labelLarge, color = MutedInk)
            ComposerToolToggle(Icons.Rounded.TravelExplore, "联网搜索", "回答时参考网页信息",
                webSearchEnabled, onWebSearchToggle)
            ComposerToolToggle(Icons.Rounded.NoteAdd, "创建文件", "将内容整理为可下载的文档",
                fileCreationEnabled, onFileCreationToggle)
            if (apiMode != "responses") Text("当前 Chat 服务的联网搜索不能与创建文件或技能同时使用。",
                style = MaterialTheme.typography.labelSmall, color = MutedInk)
        }
    }
}

@Composable
private fun ComposerToolToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(color = Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable { onCheckedChange(!checked) }.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(if (checked) AccentSoft else SurfaceInset), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(19.dp), tint = if (checked) Accent else MutedInk)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = Ink, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MutedInk, lineHeight = 16.sp)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** 输入框上的短标签，两个汉字放得下。 */
private fun compactEffortLabel(label: String): String = when (label) {
    "模型默认" -> "默认"
    "极简" -> "极简"
    "快速" -> "快速"
    "均衡" -> "均衡"
    "深入" -> "深入"
    "深度" -> "深度"
    "极致" -> "极致"
    "关闭" -> "关闭"
    "开启" -> "开启"
    else -> label.take(2)
}

