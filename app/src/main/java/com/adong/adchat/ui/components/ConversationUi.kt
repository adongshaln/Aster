package com.adong.adchat.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.ui.fittedImageDp
import com.adong.adchat.ui.theme.*
import kotlin.math.PI
import kotlin.math.sin

/** Shared input interaction; modes supply data, actions and optional model controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConversationComposer(
    value: String,
    attachments: List<ChatImageAttachment>,
    loading: Boolean,
    attachmentLoading: Boolean,
    onValueChange: (String) -> Unit,
    onOptionsClick: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    placeholder: String = "说说你的想法…",
    testTag: String = "chat",
    configureRequired: Boolean = false,
    contextAction: (@Composable () -> Unit)? = null,
    reasoningAction: (@Composable () -> Unit)? = null
) {
    val focus = LocalFocusManager.current
    val resolvedFocusRequester = focusRequester ?: remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    var isFocused by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The mode owns the durable draft; both editors share its text and selection.
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, TextRange(
                fieldValue.selection.start.coerceIn(0, value.length),
                fieldValue.selection.end.coerceIn(0, value.length)
            ))
        }
    }
    val edit: (TextFieldValue) -> Unit = { next ->
        fieldValue = next
        if (next.text != value) onValueChange(next.text)
    }
    if (editorOpen) {
        ConversationDraftEditor(
            value = fieldValue, onValueChange = { if (editorOpen) edit(it) },
            attachments = attachments, attachmentLoading = attachmentLoading,
            loading = loading, configureRequired = configureRequired,
            onRemoveImage = onRemoveImage,
            onDismiss = { retained ->
                editorOpen = false
                fieldValue = retained
            },
            onSend = { editorOpen = false; onSend() }, onStop = onStop,
            testTag = testTag
        )
    }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    LaunchedEffect(isFocused) {
        if (!isFocused) return@LaunchedEffect
        var previousBottom = ime.getBottom(density)
        snapshotFlow { ime.getBottom(density) to imeTarget.getBottom(density) }.collect { (bottom, target) ->
            // Some IMEs report a zero target briefly while opening. Only a decreasing
            // visible height proves dismissal; collapse on its first frame, not after it.
            if (target == 0 && bottom < previousBottom) focus.clearFocus()
            previousBottom = bottom
        }
    }
    val enabledToSend = value.isNotBlank() || attachments.isNotEmpty() || configureRequired
    // 输入条是恒定单列卡片：文本区在上、控制行在下，聚焦只改描边不改布局。
    // 之前聚焦一次要同时动画 5 个尺寸 + maxLines 硬切，等于每次聚焦重新排版一次。
    val cardShape = RoundedCornerShape(28.dp)

    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 8.dp, bottom = 8.dp)
    ) {
        if (attachments.isNotEmpty()) {
            Surface(
                color = Surface.copy(alpha = .94f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Hairline.copy(alpha = .72f)),
                shadowElevation = 0.dp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
            ) {
                ConversationAttachmentPreview(
                    attachments = attachments,
                    loading = attachmentLoading,
                    onRemove = onRemoveImage
                )
            }
        }
        Surface(
            color = Surface.copy(alpha = .97f),
            shape = cardShape,
            border = BorderStroke(1.dp, if (isFocused) Accent.copy(alpha = .42f) else Hairline.copy(alpha = .9f)),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().testTag("$testTag-composer")
        ) {
            Column(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { if (!editorOpen) edit(it) },
                    modifier = Modifier.fillMaxWidth().testTag("$testTag-input").focusRequester(resolvedFocusRequester)
                        .padding(start = 18.dp, end = 18.dp, top = 15.dp)
                        .heightIn(min = 24.dp, max = 132.dp)
                        .onFocusChanged { state ->
                            if (isFocused != state.isFocused) {
                                isFocused = state.isFocused
                                onFocusChange(state.isFocused)
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Accent),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        // 生成中键盘发送键和它下面那个按钮必须是同一个行为（停止），
                        // 否则用户按发送什么都没发生，看起来像卡死。
                        when {
                            loading -> onStop()
                            enabledToSend && !attachmentLoading -> {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                focus.clearFocus()
                                onSend()
                            }
                        }
                    }),
                    decorationBox = { innerTextField ->
                        Box(Modifier.fillMaxWidth()) {
                            if (value.isEmpty()) {
                                Text(placeholder, color = MutedInk, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            innerTextField()
                        }
                    }
                )
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            focus.clearFocus()
                            onOptionsClick()
                        },
                        enabled = !loading && !attachmentLoading,
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (attachmentLoading) {
                            CircularProgressIndicator(Modifier.size(19.dp), color = Accent, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Add, "输入选项", Modifier.size(29.dp), tint = Ink)
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 上下文进度与思考强度常驻，不随焦点隐藏；模型切换收归顶栏，输入条不再放第二入口。
                            contextAction?.invoke()
                            reasoningAction?.invoke()
                        }
                    }
                    AnimatedVisibility(visible = isFocused, enter = fadeIn(tween(150)), exit = fadeOut(tween(90))) {
                        IconButton(onClick = {
                            // Ignore the compact field's blur callback during the hand-off.
                            val retained = fieldValue.copy(composition = null)
                            editorOpen = true
                            focus.clearFocus()
                            fieldValue = retained
                        }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.OpenInFull, "展开草稿", Modifier.size(21.dp), tint = MutedInk)
                        }
                    }
                    FilledIconButton(
                        onClick = {
                            haptics.performHapticFeedback(if (loading) HapticFeedbackType.LongPress else HapticFeedbackType.Confirm)
                            if (loading) onStop() else {
                                focus.clearFocus()
                                onSend()
                            }
                        },
                        enabled = loading || (enabledToSend && !attachmentLoading),
                        modifier = Modifier.size(48.dp),
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
                                fadeIn(tween(180, easing = FastOutSlowInEasing)) togetherWith
                                    fadeOut(tween(120, easing = FastOutSlowInEasing))
                            },
                            label = "send-stop"
                        ) { isLoading ->
                            if (isLoading) Icon(Icons.Rounded.Stop, "停止生成", Modifier.size(21.dp))
                            else if (configureRequired) Icon(Icons.Rounded.Settings, "选择模型", Modifier.size(23.dp))
                            else Icon(Icons.Rounded.ArrowUpward, "发送", Modifier.size(23.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationThinkingIndicator() {
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "aster-thinking")
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "aster-thinking-float"
    )
    val sway by transition.animateFloat(
        initialValue = -7f,
        targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "aster-thinking-sway"
    )
    val subtitleAlpha by transition.animateFloat(
        initialValue = .78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "aster-thinking-subtitle"
    )
    val lift = with(density) { 3.dp.toPx() } * sin(PI * float).toFloat()

    Row(
        Modifier.heightIn(min = 46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            AsterArtwork(
                Modifier.size(28.dp).graphicsLayer {
                    translationY = -lift
                    rotationZ = sway
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

/**
 * 模型的思考过程。推理模型在给出正文前常有数十秒静默，单独展示思考流，
 * 用户才知道它是在推进而不是卡死。
 */
@Composable
fun ConversationReasoningPanel(
    reasoning: String,
    streaming: Boolean,
    modifier: Modifier = Modifier
) {
    if (reasoning.isBlank()) return
    var expanded by remember { mutableStateOf(streaming) }
    var wasStreaming by remember { mutableStateOf(false) }
    // 思考是过程信息：流式时展开可见，正文开始输出后收成一行，点击可回看。
    LaunchedEffect(streaming) {
        if (wasStreaming && !streaming) expanded = false
        wasStreaming = streaming
    }
    Surface(
        onClick = { expanded = !expanded },
        color = SurfaceInset.copy(alpha = .7f),
        contentColor = Ink,
        shape = RoundedCornerShape(13.dp),
        // 高度动画只留一层：AnimatedVisibility 负责展开收起，流式期间内容在持续变长，
        // 再叠一个 animateContentSize 会永远追不上新高度，两层还会打架。
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp)
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (streaming) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Accent,
                        trackColor = Accent.copy(alpha = .16f),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MutedInk)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (streaming) "正在思考…" else "已思考 ${reasoning.length} 字",
                    style = MaterialTheme.typography.labelLarge,
                    color = MutedInk,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    null,
                    Modifier.size(18.dp),
                    tint = MutedInk
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(150)) + expandVertically(tween(200, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(160))
            ) {
                Text(
                    reasoning,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

@Composable
fun ConversationMessageAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    accent: Boolean = false
) {
    // 图标化让动作行不与正文抢注意力，但热区仍是 48dp（图标视觉保持小），
    // 文字对比度也要够：45% 墨色在浅色画布上接近 2.5:1，属于「看得见但读不出」。
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = if (accent) Accent else Ink.copy(alpha = .72f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
fun ConversationImages(attachments: List<ChatImageAttachment>) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { attachment ->
            val fitted = fittedImageDp(attachment.width, attachment.height, maxWidth = 200f, maxHeight = 200f)
            val frame = if (fitted == null) Modifier.size(116.dp) else Modifier.size(fitted.first.dp, fitted.second.dp)
            Surface(
                color = Color.White.copy(alpha = .12f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .16f))
            ) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    modifier = frame.clip(RoundedCornerShape(13.dp))
                )
            }
        }
    }
}

@Composable
fun ConversationAttachmentPreview(
    attachments: List<ChatImageAttachment>,
    loading: Boolean,
    onRemove: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp).horizontalScroll(rememberScrollState()),
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
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp)
                ) {
                    Icon(Icons.Rounded.Close, "移除 ${attachment.name}", Modifier.padding(15.dp).size(18.dp))
                }
            }
        }
    }
}

@Composable
fun ConversationSheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Surface,
        contentColor = if (enabled) Ink else MutedInk,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Hairline.copy(alpha = .75f)),
        modifier = modifier
    ) {
        Column(Modifier.heightIn(min = 84.dp).padding(horizontal = 8.dp, vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) Accent else MutedInk)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MutedInk, textAlign = TextAlign.Center) }
        }
    }
}


/** The accepted reading veil: clear at composer top, opaque at the navigation edge. */
@Composable
fun ConversationReadingVeil(modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.verticalGradient(
        0f to Color.Transparent,
        .22f to Canvas.copy(alpha = .72f),
        1f to Canvas
    )))
}

/**
 * Tracks whether the user is actively scrolling toward the end of [listState]:
 * visible only during such a gesture, fading out [hideDelayMillis] after it stops,
 * and hiding immediately once the bottom is reached.
 */
@Composable
fun rememberTowardBottomScrollVisible(
    listState: androidx.compose.foundation.lazy.LazyListState,
    hideDelayMillis: Long = 4000L
): Boolean {
    var visible by remember { mutableStateOf(false) }
    var ticker by remember { mutableIntStateOf(0) }
    LaunchedEffect(ticker) {
        if (ticker == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(hideDelayMillis)
        visible = false
    }
    LaunchedEffect(listState) {
        var lastProgress = Triple(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset,
            listState.isScrollInProgress
        )
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }.collect { current ->
            val progress = current.first.toLong() * Int.MAX_VALUE + current.second
            val previous = lastProgress.first.toLong() * Int.MAX_VALUE + lastProgress.second
            if (current.third && progress > previous && listState.canScrollForward) {
                visible = true
                ticker++
            } else if (!current.third || !listState.canScrollForward) {
                if (visible) {
                    visible = false
                    ticker = 0
                }
            }
            lastProgress = current
        }
    }
    return visible
}

@Composable
fun ConversationJumpToBottom(
    visible: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fadeMillisIn: Int = 140,
    fadeMillisOut: Int = 100
) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(fadeMillisIn)), exit = fadeOut(tween(fadeMillisOut))) {
        Surface(
            onClick = onClick,
            color = Surface,
            contentColor = Accent,
            shape = CircleShape,
            border = BorderStroke(1.dp, Hairline),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    if (loading) "跟随生成" else "回到底部",
                    Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
fun ConversationCopyAction(content: String) {
    val context = LocalContext.current
    var copied by remember(content) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    ConversationMessageAction(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
        if (copied) "已复制" else "复制", accent = copied, onClick = {
            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("Aster", content))
            copied = true
        })
}
