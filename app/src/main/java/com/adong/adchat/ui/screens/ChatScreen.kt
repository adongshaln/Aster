package com.adong.adchat.ui.screens

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.data.usesResponses
import com.adong.adchat.data.ChatFileAttachment
import com.adong.adchat.data.ChatMessage
import com.adong.adchat.data.ChatCitation
import com.adong.adchat.data.ChatToolActivity
import com.adong.adchat.data.TOOL_STATUS_COMPLETED
import com.adong.adchat.data.TOOL_STATUS_FAILED
import com.adong.adchat.data.TOOL_STATUS_RUNNING
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.chat.questionNavigationTargets
import com.adong.adchat.ui.components.*
import com.adong.adchat.ui.components.AdChoiceOption
import com.adong.adchat.ui.components.AdSelectionSheet
import com.adong.adchat.ui.components.QuickModelSwitcher
import com.adong.adchat.ui.components.RouteKind
import com.adong.adchat.ui.markdown.MarkdownTable
import com.adong.adchat.ui.markdown.MarkdownTableAlignment
import com.adong.adchat.ui.markdown.containsMarkdownTable
import com.adong.adchat.ui.markdown.markdownTableToTsv
import com.adong.adchat.ui.markdown.parseMarkdownTableAt
import com.adong.adchat.ui.theme.*
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
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
    val focusManager = LocalFocusManager.current
    val imeInsets = WindowInsets.ime
    val imeAnimationTarget = WindowInsets.imeAnimationTarget
    val hazeState = rememberHazeState()
    val userDragging by listState.interactionSource.collectIsDraggedAsState()
    val streamScrollSignals = remember {
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }
    var showSwitcher by remember { mutableStateOf(false) }
    var autoFollow by remember { mutableStateOf(true) }
    var composerFocused by remember { mutableStateOf(false) }
    var imeTransitioning by remember { mutableStateOf(false) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var pendingFileExport by remember { mutableStateOf<ChatFileAttachment?>(null) }
    val fileExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = pendingFileExport
        pendingFileExport = null
        if (result.resultCode == Activity.RESULT_OK && file != null) {
            result.data?.data?.let { target -> vm.exportGeneratedFile(file, target) }
        }
    }
    val chatImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) vm.attachChatImages(uris)
    }
    val showJumpToBottom by remember { derivedStateOf { vm.messages.isNotEmpty() && listState.canScrollForward } }
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
    val composerHeight = with(density) { composerHeightPx.toDp() }.coerceAtLeast(72.dp)
    val composerClearance = composerHeight + 18.dp
    // Keep the bottom reading veil visually stable; the live Haze path rendered differently\n    // between idle and touch/scroll states on some Android GPUs.\n    val useLiveHaze = false

    LaunchedEffect(vm.activeConversationId) {
        autoFollow = true
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.size)
    }

    LaunchedEffect(userDragging, listState.canScrollForward) {
        if (userDragging) autoFollow = !listState.canScrollForward
    }

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty() && autoFollow && !userDragging) {
            listState.animateScrollToItem(vm.messages.size)
        }
    }

    LaunchedEffect(streamScrollSignals, vm.activeConversationId) {
        streamScrollSignals.collect {
            if (vm.messages.isNotEmpty() && autoFollow && !userDragging && !composerFocused) {
                listState.animateScrollToItem(vm.messages.size)
            }
        }
    }

    LaunchedEffect(imeInsets, imeAnimationTarget) {
        snapshotFlow {
            imeInsets.getBottom(density) to imeAnimationTarget.getBottom(density)
        }
            .distinctUntilChanged()
            .collect { (currentBottom, targetBottom) ->
                val transitioning = currentBottom != targetBottom
                if (imeTransitioning != transitioning) imeTransitioning = transitioning
            }
    }

    LaunchedEffect(composerFocused, vm.activeConversationId) {
        if (!composerFocused) return@LaunchedEffect
        autoFollow = true
        var imeWasVisible = imeInsets.getBottom(density) > 0
        snapshotFlow {
            imeInsets.getBottom(density) to imeAnimationTarget.getBottom(density)
        }
            .distinctUntilChanged()
            .collect { (imeBottom, imeTargetBottom) ->
                if (vm.messages.isNotEmpty()) {
                    // Preserve the accepted IME behaviour: the conversation follows every inset
                    // update so keyboard and content move together rather than serially.
                    listState.scrollToItem(vm.messages.size)
                }

                if (imeBottom > 0) imeWasVisible = true

                if (imeWasVisible && imeTargetBottom == 0) {
                    focusManager.clearFocus()
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
                Modifier.fillMaxSize().then(
                    if (useLiveHaze) Modifier.hazeSource(hazeState) else Modifier
                )
            ) {
                if (vm.messages.isEmpty()) {
                    EmptyChat(
                        model = vm.chatProfile.chatModel,
                        onSuggestion = { draft ->
                            vm.updateChatInput(draft)
                            composerFocusRequester.requestFocus()
                            keyboard?.show()
                        },
                        onConfigure = onOpenSettings,
                        modifier = Modifier.fillMaxSize().padding(bottom = composerClearance)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
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
                            ChatMessageItem(
                                message = message,
                                canRegenerate = message.id == regeneratableMessageId,
                                onRetry = { vm.retryMessage(message.id) },
                                onRegenerate = { vm.regenerateMessage(message.id) },
                                onSaveFile = { file ->
                                    pendingFileExport = file
                                    fileExportLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = file.mimeType
                                        putExtra(Intent.EXTRA_TITLE, file.name)
                                    })
                                },
                                onStreamingTextAdvanced = { streamScrollSignals.tryEmit(Unit) },
                                modifier = if (message.isStreaming) Modifier else Modifier.animateItem()
                            )
                        }
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                }
            }
            val hazeLayerModifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(composerHeight)
            if (useLiveHaze) {
                Box(
                    hazeLayerModifier.hazeEffect(state = hazeState) {
                        backgroundColor = Canvas
                        blurRadius = 22.dp
                        inputScale = HazeInputScale.Auto
                        noiseFactor = 0f
                        tints = listOf(HazeTint(color = Canvas.copy(alpha = .34f)))
                        progressive = HazeProgressive.verticalGradient(
                            startIntensity = 0f,
                            endIntensity = 1f,
                            preferPerformance = true
                        )
                        mask = Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black
                        )
                    }
                )
            } else {
                Box(
                    hazeLayerModifier.background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            .46f to Canvas.copy(alpha = .38f),
                            1f to Canvas.copy(alpha = .96f)
                        )
                    )
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = showJumpToBottom,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = composerClearance),
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(100))
            ) {
                Surface(
                    onClick = {
                        autoFollow = true
                        scope.launch { listState.animateScrollToItem(vm.messages.size) }
                    },
                    color = Surface,
                    contentColor = Accent,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Hairline),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (vm.isChatLoading) "跟随生成" else "回到底部",
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { composerHeightPx = it.height }
                    .navigationBarsPadding()
            ) {
                ChatComposer(
                    focusRequester = composerFocusRequester,
                    value = vm.chatInput,
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
                    onValueChange = vm::updateChatInput,
                    onPickImages = {
                        chatImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRemoveImage = vm::removeChatImage,
                    onSend = { autoFollow = true; vm.sendMessage() },
                    onStop = vm::stopGeneration,
                    onFocusChange = { composerFocused = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
    if (showSwitcher) {
        QuickModelSwitcher(kind = RouteKind.Chat, vm = vm, onDismiss = { showSwitcher = false }, onManageApis = onOpenSettings)
    }
}

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
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AsterIconButton(Icons.Rounded.Menu, "打开侧栏", onOpenDrawer)
        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onSwitchModel)
            .padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(if (vm.messages.isEmpty()) "Aster" else vm.conversations.firstOrNull { it.id == vm.activeConversationId }?.title ?: "对话",
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.chatProfile.chatModel.ifBlank { "选择对话模型" },
                    modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
                    color = MutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, "切换模型", Modifier.size(15.dp), tint = MutedInk)
            }
        }
        if (showNavigation) {
            QuestionNavigator(previousEnabled, nextEnabled, onPrevious, onNext)
        }
        AsterIconButton(Icons.Rounded.AddComment, "新建对话", { vm.newConversation() }, enabled = !vm.isChatLoading)
    }
}

@Composable
private fun EmptyChat(model: String, onSuggestion: (String) -> Unit, onConfigure: () -> Unit, modifier: Modifier = Modifier) {
    val suggestions = listOf(
        Triple(Icons.Rounded.Lightbulb, "理清思路", "帮我把一个复杂问题拆成清晰的行动步骤。"),
        Triple(Icons.Rounded.EditNote, "一起创作", "我想写一点东西，帮我一起打磨想法。"),
        Triple(Icons.Rounded.Explore, "发现新知", "我有一个想弄懂的概念，请用直观的例子解释。")
    )
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center) {
        AsterMark(Modifier.size(78.dp).offset(x = (-14).dp))
        Spacer(Modifier.height(12.dp))
        Text("让想法，\n慢慢成形。", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text("从一个问题、一点灵感，\n或任何想聊的事开始。", color = MutedInk, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(30.dp))
        if (model.isBlank()) {
            AsterModelRow("准备开始", "连接你的第一个模型", onConfigure, icon = Icons.Rounded.AddLink)
        } else {
            suggestions.forEachIndexed { index, (icon, title, prompt) ->
                Surface(onClick = { onSuggestion(prompt) }, color = Color.Transparent,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(vertical = 16.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(21.dp), tint = Accent)
                        Text(title, Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium)
                        Icon(Icons.Rounded.NorthWest, null, Modifier.size(17.dp), tint = MutedInk)
                    }
                }
                if (index < suggestions.lastIndex) HorizontalDivider(color = Hairline)
            }
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    canRegenerate: Boolean,
    onRetry: () -> Unit,
    onRegenerate: () -> Unit,
    onSaveFile: (ChatFileAttachment) -> Unit,
    onStreamingTextAdvanced: () -> Unit,
    modifier: Modifier = Modifier
) {
    val user = message.role == "user"
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
                Surface(color = SurfaceInset, contentColor = Ink, shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)) {
                    Column(Modifier.padding(7.dp)) {
                        if (message.attachments.isNotEmpty()) {
                            ChatImageRow(message.attachments)
                        }
                        if (message.content.isNotBlank()) {
                            SelectionContainer {
                                Text(
                                    message.content,
                                    Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            } else {
                if (!waitingForFirstToken) {
                    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsterMark(Modifier.size(26.dp), tint = if (message.isError) Danger else Accent)
                        Spacer(Modifier.width(5.dp))
                        Text("Aster", color = MutedInk, style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium)
                    }
                }
                if (message.toolActivities.isNotEmpty()) {
                    ToolActivitySummary(message.toolActivities)
                }
                if (waitingForFirstToken) {
                    ThinkingIndicator()
                } else {
                    RichMessageText(
                        content = message.content,
                        streaming = message.isStreaming,
                        error = message.isError,
                        onStreamingTextAdvanced = onStreamingTextAdvanced
                    )
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
                        color = if (message.isStopped) Color(0xFFF0EDE8) else Color(0xFFFFF1D8),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChatActionButton(
                                icon = Icons.Outlined.ContentCopy,
                                label = "复制",
                                onClick = { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Aster", message.content)) },
                            )
                            if (message.isError) {
                                ChatActionButton(Icons.Rounded.Refresh, "重试", onRetry)
                            } else if (message.isInterrupted || message.isStopped) {
                                ChatActionButton(Icons.Rounded.PlayArrow, "继续生成", onRetry)
                            } else if (canRegenerate) {
                                ChatActionButton(Icons.Rounded.Refresh, "重新生成", onRegenerate, accent = true)
                            }
                            if (message.profileName.isNotBlank() || message.usage != null) {
                                ChatActionButton(
                                    Icons.Rounded.MoreHoriz,
                                    if (showDetails) "收起" else "详情",
                                    { showDetails = !showDetails }
                                )
                            }
                        }
                        AnimatedVisibility(showDetails) {
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
private fun QuestionNavigator(
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onPrevious()
            },
            enabled = previousEnabled,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Rounded.KeyboardArrowUp, "回到上一个提问", Modifier.size(22.dp),
                tint = if (previousEnabled) Accent else MutedInk.copy(alpha = .28f))
        }
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onNext()
            },
            enabled = nextEnabled,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, "前往下一个提问", Modifier.size(22.dp),
                tint = if (nextEnabled) Accent else MutedInk.copy(alpha = .28f))
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
        color = if (recovering) AccentSoft else Color(0xFFF0EDE8),
        contentColor = if (recovering) Accent else MutedInk,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (recovering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = Accent,
                    trackColor = Accent.copy(alpha = .18f),
                    strokeWidth = 1.8.dp
                )
            } else {
                Icon(Icons.Rounded.Sync, null, Modifier.size(15.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "aster-thinking")
    val motion by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(3520, easing = LinearEasing)),
        label = "aster-thinking-motion"
    )
    val subtitleAlpha by transition.animateFloat(
        initialValue = .58f,
        targetValue = .86f,
        animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
        label = "aster-thinking-subtitle"
    )
    val step = motion.toInt().coerceIn(0, 3)
    val local = (motion - step).coerceIn(0f, 1f)
    val hopPortion = .62f
    val hopProgress = (local / hopPortion).coerceIn(0f, 1f)
    val eased = hopProgress * hopProgress * (3f - 2f * hopProgress)
    val jumpPx = if (local < hopPortion) {
        with(density) { (-6.dp).toPx() } * sin(PI * hopProgress).toFloat()
    } else {
        0f
    }
    val rotation = step * 90f + if (local < hopPortion) eased * 90f else 90f

    Row(
        Modifier.heightIn(min = 46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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

@Composable
private fun ToolActivitySummary(activities: List<ChatToolActivity>) {
    var expanded by remember(activities.size) { mutableStateOf(false) }
    val running = activities.any { it.status == TOOL_STATUS_RUNNING }
    val failed = activities.any { it.status == TOOL_STATUS_FAILED }
    val latest = activities.last()
    val tint = when {
        failed -> Danger
        running -> Accent
        else -> Sage
    }
    val label = when {
        running -> latest.label
        failed -> latest.label
        activities.size == 1 -> latest.label
        else -> "已完成 ${activities.size} 项工具操作"
    }
    Surface(
        onClick = { if (activities.size > 1) expanded = !expanded },
        color = when {
            failed -> DangerSoft
            running -> AccentSoft.copy(alpha = .72f)
            else -> SageSoft.copy(alpha = .72f)
        },
        contentColor = Ink,
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).animateContentSize(tween(180))
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    running -> CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        color = tint,
                        trackColor = tint.copy(alpha = .16f),
                        strokeWidth = 2.dp
                    )
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
            AnimatedVisibility(expanded) {
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
                    color = Color(0xFFF0EDE8),
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
        waitingForReuse && !usage.cacheEligible -> "上下文较短，尚未进入缓存"
        waitingForReuse -> "已提交缓存，等待复用"
        else -> "本次未启用缓存"
    }
    val summary = if (hasCache) {
        "${formatTokens(usage.cachedTokens)} / ${formatTokens(usage.inputTokens)}"
    } else {
        "输入 ${formatTokens(usage.inputTokens)}"
    }
    Surface(
        onClick = { expanded = !expanded },
        color = when {
            hasCache -> SageSoft
            hasCacheWrite || (waitingForReuse && usage.cacheEligible) -> AccentSoft
            else -> Color(0xFFF0EDE8)
        },
        contentColor = Ink,
        shape = RoundedCornerShape(13.dp),
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
                    trackColor = if (hasCache) Color(0xFFCFE4D7) else Color(0xFFF3CDC3)
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    UsageLine("输入", formatTokens(usage.inputTokens), "未缓存 ${formatTokens(usage.uncachedInputTokens)}")
                    UsageLine("缓存", formatTokens(usage.cachedTokens), if (hitPercent > 0) "命中 $hitPercent%" else "尚未命中")
                    if (usage.cacheWriteTokens > 0) {
                        UsageLine("写入", formatTokens(usage.cacheWriteTokens), "服务端报告")
                    } else if (waitingForReuse) {
                        UsageLine("状态", if (usage.cacheEligible) "等待复用" else "未达 1024 Token", null)
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
    val normalized = content.replace("\r\n", "\n")
    val parts = remember(normalized) { normalized.split("\n\n") }
    Column(verticalArrangement = Arrangement.spacedBy(READING_BLOCK_GAP_DP.dp)) {
        if (parts.isEmpty() || (parts.size == 1 && parts.first().isEmpty())) {
            AsterWritingCursorLine(error)
            return@Column
        }
        parts.forEachIndexed { index, paragraph ->
            val tail = index == parts.lastIndex
            if (paragraph.isNotEmpty()) {
                key(index, paragraph) {
                    MarkdownTextBlock(
                        raw = paragraph,
                        showCursor = tail,
                        error = error
                    )
                }
            } else if (tail) {
                AsterWritingCursorLine(error)
            }
        }
    }
}

@Composable
private fun StructuredMessageText(content: String, streaming: Boolean, error: Boolean) {
    val parts = remember(content) { content.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        parts.forEachIndexed { index, raw ->
            if (raw.isBlank() && index != parts.lastIndex) return@forEachIndexed
            if (index % 2 == 1) {
                val lines = raw.trim('\n').lines()
                val language = lines.firstOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_+.#-]{1,20}")) }
                val code = if (language != null) lines.drop(1).joinToString("\n") else raw.trim('\n')
                CodeBlock(
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

@Composable
private fun CodeBlock(language: String?, code: String, selectable: Boolean) {
    val context = LocalContext.current
    Surface(color = Night, contentColor = Color(0xFFF4F0EA), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 5.dp, top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language?.uppercase() ?: "CODE", color = Color(0xFFAAA49D), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("code", code)) },
                    modifier = Modifier.size(32.dp)
                ) { Icon(Icons.Outlined.ContentCopy, "复制代码", Modifier.size(15.dp), tint = Color(0xFFCCC6BE)) }
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

private fun isSceneBreakLine(line: String): Boolean {
    val value = line.filterNot(Char::isWhitespace)
    if (value in setOf("***", "——", "……", "...", "◇", "◆")) return true
    if (value.length >= 3 && value.all { it == '-' }) return true
    return value.length >= 3 && value.all { it == '*' }
}

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
            line.matches(Regex("""^#{1,6}\s+.*""")) -> {
                flushParagraph()
                flushQuote()
                val level = line.takeWhile { it == '#' }.length
                result += MarkdownBlock(1, line.drop(level).trimStart(), level = level)
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
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
                if (quote.isNotEmpty()) quote.append('\n')
                quote.append(line.replaceFirst(MARKDOWN_QUOTE_PREFIX, "").trimEnd())
            }
            else -> {
                flushQuote()
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trim())
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
                            fontSize = 20.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        2 -> MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.5.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        3 -> bodyStyle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                        else -> bodyStyle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                    },
                    color = if (error) Danger else Ink,
                    selectable = !showCursor,
                    showWritingCursor = hasCursor,
                    modifier = Modifier.padding(top = if (block.level == 1) 8.dp else 3.dp)
                )
                2 -> MarkdownListRow(block.marker, block.text, error, selectable = !showCursor, showCursor = hasCursor)
                3 -> Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp)
                ) {
                    Box(
                        Modifier.width(2.dp).fillMaxHeight().clip(CircleShape)
                            .background(if (error) Danger.copy(alpha = .30f) else Accent.copy(alpha = .28f))
                    )
                    Spacer(Modifier.width(12.dp))
                    ReadableText(
                        inlineMarkdown(block.text),
                        bodyStyle,
                        if (error) Danger else Color(0xFF4E4A45),
                        selectable = !showCursor,
                        showWritingCursor = hasCursor,
                        modifier = Modifier.weight(1f)
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
            color = MutedInk.copy(alpha = .48f),
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
            color = Color(0xFFFCFBF9),
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
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, "复制表格", Modifier.size(16.dp), tint = MutedInk)
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
                striped -> Color(0xFFF7F4F0)
                else -> Color.White
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
                AsterMark(Modifier.fillMaxSize(), tint = Accent.copy(alpha = .72f))
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

private fun inlineMarkdown(text: String): AnnotatedString {
    val base = basicInlineMarkdown(text)
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
private fun ChatActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = if (accent) Accent else Ink,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.heightIn(min = 36.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ChatImageRow(attachments: List<ChatImageAttachment>) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { attachment ->
            Surface(
                color = Color.White.copy(alpha = .12f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
            ) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(116.dp).clip(RoundedCornerShape(13.dp))
                )
            }
        }
    }
}

private fun basicInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    val tokens = listOf("**", "__", "~~", "`", "*", "_")
    while (index < text.length) {
        val token = tokens.firstOrNull { text.startsWith(it, index) }
        if (token != null) {
            val end = text.indexOf(token, index + token.length)
            if (end > index + token.length) {
                val style = when (token) {
                    "**", "__" -> SpanStyle(fontWeight = FontWeight.Bold)
                    "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    "`" -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFF0EDE8), color = Ink)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                pushStyle(style)
                append(text.substring(index + token.length, end))
                pop()
                index = end + token.length
            } else {
                append(token); index += token.length
            }
        } else {
            val next = tokens.map { text.indexOf(it, index) }.filter { it >= 0 }.minOrNull() ?: text.length
            val target = next.coerceAtLeast(index + 1)
            append(text.substring(index, target)); index = target
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatComposer(
    focusRequester: FocusRequester,
    value: String,
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
    onRemoveImage: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    var isFocused by remember { mutableStateOf(false) }
    var showEffortSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    val enabledToSend = value.isNotBlank() || attachments.isNotEmpty()
    val capsuleShape = RoundedCornerShape(31.dp)
    val focusProgress by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isFocused) 230 else 180,
            easing = FastOutSlowInEasing
        ),
        label = "composer-focus-progress"
    )
    val minimumHeight = 58.dp + 52.dp * focusProgress
    val fieldStart = 58.dp - 40.dp * focusProgress
    val fieldEnd = 58.dp - 40.dp * focusProgress
    val fieldTop = 17.dp - 2.dp * focusProgress
    val fieldBottom = 15.dp + 42.dp * focusProgress

    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 8.dp, bottom = 8.dp)
    ) {
        if (attachments.isNotEmpty()) {
            Surface(
                color = Surface.copy(alpha = .94f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Hairline.copy(alpha = .72f)),
                shadowElevation = 5.dp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
            ) {
                ChatAttachmentComposerPreview(
                    attachments = attachments,
                    loading = attachmentLoading,
                    onRemove = onRemoveImage
                )
            }
        }
        Surface(
            color = Surface.copy(alpha = .97f),
            shape = capsuleShape,
            border = BorderStroke(1.dp, if (isFocused) Accent.copy(alpha = .35f) else Hairline),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().testTag("chat-composer")
                .shadow(
                    elevation = 4.dp,
                    shape = capsuleShape,
                    ambientColor = Color.Black.copy(alpha = .07f),
                    spotColor = Color.Black.copy(alpha = .10f)
                )
        ) {
            Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = minimumHeight)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().testTag("chat-input").focusRequester(focusRequester)
                        .padding(start = fieldStart, end = fieldEnd, top = fieldTop, bottom = fieldBottom)
                        .heightIn(min = 24.dp, max = 132.dp)
                        .onFocusChanged { state ->
                            if (isFocused != state.isFocused) {
                                isFocused = state.isFocused
                                onFocusChange(state.isFocused)
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Accent),
                    maxLines = if (isFocused) 5 else 1,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        if (enabledToSend && !loading && !attachmentLoading) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            focus.clearFocus()
                            onSend()
                        }
                    }),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (value.isEmpty()) {
                                Text("说说你的想法…", color = MutedInk, style = MaterialTheme.typography.bodyLarge)
                            }
                            innerTextField()
                        }
                    }
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(58.dp).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            focus.clearFocus()
                            showToolsSheet = true
                        },
                        enabled = !loading,
                        modifier = Modifier.size(46.dp)
                    ) {
                        if (attachmentLoading) {
                            CircularProgressIndicator(Modifier.size(19.dp), color = Accent, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Add, "输入选项", Modifier.size(29.dp), tint = Ink)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    AnimatedVisibility(
                        visible = isFocused,
                        enter = fadeIn(tween(150)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = .92f),
                        exit = fadeOut(tween(90))
                    ) {
                        Surface(
                            onClick = {
                                focus.clearFocus()
                                onModelClick()
                            },
                            color = Color.Transparent,
                            contentColor = Ink,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                Modifier.widthIn(max = 174.dp).padding(horizontal = 9.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    compactModelLabel(model),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(3.dp))
                                Icon(Icons.Rounded.ExpandMore, "更换模型", Modifier.size(16.dp), tint = MutedInk)
                            }
                        }
                    }
                    FilledIconButton(
                        onClick = {
                            haptics.performHapticFeedback(if (loading) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                            if (loading) onStop() else {
                                focus.clearFocus()
                                onSend()
                            }
                        },
                        enabled = loading || (enabledToSend && !attachmentLoading),
                        modifier = Modifier.size(46.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Night,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFE6E1DB),
                            disabledContentColor = Color(0xFFA9A39C)
                        )
                    ) {
                        AnimatedContent(
                            targetState = loading,
                            transitionSpec = {
                                (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = .72f)) togetherWith
                                    (fadeOut(tween(100)) + scaleOut(tween(120), targetScale = .72f))
                            },
                            label = "send-stop"
                        ) { isLoading ->
                            if (isLoading) Icon(Icons.Rounded.Stop, "停止生成", Modifier.size(21.dp))
                            else Icon(Icons.Rounded.ArrowUpward, "发送", Modifier.size(23.dp))
                        }
                    }
                }
            }
        }
    }
    if (showToolsSheet) {
        ChatToolsSheet(
            profileName = profileName,
            model = model,
            apiMode = apiMode,
            webSearchEnabled = webSearchEnabled,
            fileCreationEnabled = fileCreationEnabled,
            canPickImages = !loading && !attachmentLoading && attachments.size < 4,
            onPickImages = { showToolsSheet = false; onPickImages() },
            onModelClick = { showToolsSheet = false; onModelClick() },
            onReasoningClick = { showToolsSheet = false; showEffortSheet = true },
            onWebSearchToggle = onWebSearchToggle,
            onFileCreationToggle = onFileCreationToggle,
            onDismiss = { showToolsSheet = false }
        )
    }
    if (showEffortSheet) {
        AdSelectionSheet(
            title = "选择思考强度",
            subtitle = "当前模型的推理预算会立即更新",
            options = REASONING_OPTIONS.map { (id, label) ->
                AdChoiceOption(
                    id = id,
                    title = label,
                    subtitle = reasoningEffortDescription(id),
                    icon = when (id) {
                        "low" -> Icons.Rounded.Bolt
                        "medium" -> Icons.Rounded.Balance
                        "high" -> Icons.Rounded.Psychology
                        "xhigh" -> Icons.Rounded.AccountTree
                        else -> Icons.Rounded.AutoAwesome
                    },
                    badge = when (id) {
                        "low" -> "更快"
                        "medium" -> "推荐"
                        "high" -> "复杂任务"
                        "xhigh" -> "深度推理"
                        else -> "最大预算"
                    }
                )
            },
            selectedId = reasoningEffort,
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
    profileName: String,
    model: String,
    apiMode: String,
    webSearchEnabled: Boolean,
    fileCreationEnabled: Boolean,
    canPickImages: Boolean,
    onPickImages: () -> Unit,
    onModelClick: () -> Unit,
    onReasoningClick: () -> Unit,
    onWebSearchToggle: (Boolean) -> Unit,
    onFileCreationToggle: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Canvas) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("输入选项", style = MaterialTheme.typography.titleLarge, color = Ink)
                    Text("$profileName · ${model.ifBlank { "未选择模型" }}", style = MaterialTheme.typography.labelMedium, color = MutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = AccentSoft, contentColor = Accent, shape = CircleShape) {
                    Text(if (apiMode == "responses") "Responses" else "Chat", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ComposerSheetAction(Icons.Rounded.AddPhotoAlternate, "图片", canPickImages, onPickImages, Modifier.weight(1f))
                ComposerSheetAction(Icons.Rounded.Hub, "模型", true, onModelClick, Modifier.weight(1f))
                ComposerSheetAction(Icons.Rounded.Psychology, "思考", true, onReasoningClick, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Text("工具", style = MaterialTheme.typography.labelLarge, color = MutedInk)
            Spacer(Modifier.height(7.dp))
            ComposerToolToggle(
                icon = Icons.Rounded.TravelExplore,
                title = "联网搜索",
                subtitle = if (apiMode == "responses") "使用 Responses 原生网页搜索" else "使用 Chat 搜索参数；启用时文件工具会关闭",
                checked = webSearchEnabled,
                onCheckedChange = onWebSearchToggle
            )
            Spacer(Modifier.height(8.dp))
            ComposerToolToggle(
                icon = Icons.Rounded.NoteAdd,
                title = "创建文件",
                subtitle = if (apiMode == "responses") "可创建 Markdown、文本、JSON 与 CSV 文件" else "使用函数工具创建文件；启用时联网搜索会关闭",
                checked = fileCreationEnabled,
                onCheckedChange = onFileCreationToggle
            )
        }
    }
}

@Composable
private fun ComposerSheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
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
        Column(Modifier.padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) Accent else MutedInk.copy(alpha = .4f))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
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
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(if (checked) AccentSoft else Color(0xFFF0EDE8)), contentAlignment = Alignment.Center) {
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

@Composable
private fun ChatAttachmentComposerPreview(
    attachments: List<ChatImageAttachment>,
    loading: Boolean,
    onRemove: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            Box(Modifier.size(76.dp)) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp))
                )
                Surface(
                    onClick = { onRemove(attachment.id) },
                    enabled = !loading,
                    color = Night.copy(alpha = .82f),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                ) {
                    Icon(Icons.Rounded.Close, "移除图片", Modifier.padding(3.dp).size(14.dp))
                }
            }
        }
    }
}

private val REASONING_OPTIONS = listOf(
    "low" to "快速",
    "medium" to "均衡",
    "high" to "深入",
    "xhigh" to "深度",
    "max" to "极致"
)

private fun compactModelLabel(value: String): String = value.ifBlank { "选择模型" }

private fun reasoningEffortDescription(value: String): String = when (value) {
    "low" -> "优先响应速度"
    "medium" -> "速度与质量平衡"
    "high" -> "复杂问题更稳定"
    "xhigh" -> "更长的推理过程"
    "max" -> "最大推理预算"
    else -> "速度与质量平衡"
}
