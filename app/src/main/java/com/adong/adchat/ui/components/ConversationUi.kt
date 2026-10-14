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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.ui.fittedImageDp
import com.adong.adchat.ui.theme.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Aster v2.0 — Claude Floating Composer × Apple Spring Reasoning & Action System
 * ==============================================================================
 * 核心视觉与动效颠覆：
 * 1. `ConversationComposer`：
 *    - 3.3 预测建议条改造为「Apple 灵动悬浮胶囊」，以 `AsterSpring.spatial()` 从底部弹性上浮缩放弹出，带陶土色「✦ 填入」胶囊徽章；
 *    - 输入框聚焦时，触发 Apple 弹簧上浮阴影 (`2.dp -> 8.dp`)、微缩放 (`0.992f -> 1f`) 与 Claude 陶土色柔光描边；
 *    - 左下角 `+` 按钮升级为凹陷暖石圆形胶囊，发送按钮升级为 **Claude 陶土色弹簧变形按钮**（可发送时以欠阻尼弹簧放大浮现并变为 `#D97757` 陶土色，生成中平滑变形为绛红停止态）。
 * 2. `ConversationReasoningPanel`（深度思考面板）：
 *    - 告别原本灰扑扑的方框与普通菊花转圈，全面复刻 **Claude 3.7 Sonnet 原生深度思考卡片**：
 *    - 顶部左侧配备陶土色柔光呼吸徽章与字数/状态胶囊，右侧配备 `AsterSpring.snappy()` 180° 旋转箭头；
 *    - 展开时以 `AsterSpring.spatial()` 物理弹簧舒展，并在思考文本左侧绘制 **Claude 标志性 2.5dp 垂直陶土色时间轴引导线**。
 * 3. `ConversationThinkingIndicator` & `ConversationJumpToBottom`：
 *    - 思考指示器升级为悬浮胶囊卡片；回到底部按钮接入 `AsterSpring.gentleBounce()` 弹性缩放弹出与悬浮阴影。
 */
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
    placeholder: String = "说说你的想法...",
    testTag: String = "chat",
    configureRequired: Boolean = false,
    suggestion: String? = null,
    onSuggestionClick: () -> Unit = {},
    contextAction: (@Composable () -> Unit)? = null,
    reasoningAction: (@Composable () -> Unit)? = null
) {
    val focus = LocalFocusManager.current
    val resolvedFocusRequester = focusRequester ?: remember { FocusRequester() }
    val haptics = LocalHapticFeedback.current
    var isFocused by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(
                value,
                TextRange(
                    fieldValue.selection.start.coerceIn(0, value.length),
                    fieldValue.selection.end.coerceIn(0, value.length)
                )
            )
        }
    }
    val edit: (TextFieldValue) -> Unit = { next ->
        fieldValue = next
        if (next.text != value) onValueChange(next.text)
    }
    if (editorOpen) {
        ConversationDraftEditor(
            value = fieldValue,
            onValueChange = { if (editorOpen) edit(it) },
            attachments = attachments,
            attachmentLoading = attachmentLoading,
            loading = loading,
            configureRequired = configureRequired,
            onRemoveImage = onRemoveImage,
            onDismiss = { retained ->
                editorOpen = false
                fieldValue = retained
            },
            onSend = { editorOpen = false; onSend() },
            onStop = onStop,
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
            if (target == 0 && bottom < previousBottom) focus.clearFocus()
            previousBottom = bottom
        }
    }

    val enabledToSend = value.isNotBlank() || attachments.isNotEmpty() || configureRequired
    val cardShape = RoundedCornerShape(28.dp)

    // Apple 焦点弹簧动画：聚焦时轻微上浮放大、阴影加深、边框渐变为 Claude 陶土色
    val composerScale by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.992f,
        animationSpec = AsterSpring.spatial(),
        label = "ComposerFocusScale"
    )
    val composerElevation by animateDpAsState(
        targetValue = if (isFocused) 8.dp else 3.dp,
        animationSpec = AsterSpring.spatial(),
        label = "ComposerElevation"
    )
    val composerBorderColor by animateColorAsState(
        targetValue = if (isFocused) Accent.copy(alpha = 0.58f) else Hairline,
        animationSpec = AsterSpring.editorial(),
        label = "ComposerBorderColor"
    )

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 6.dp, bottom = 10.dp)
    ) {
        // 附件预览卡（带 Spring 入场）
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = expandVertically(AsterSpring.spatial()) + fadeIn(AsterSpring.editorial()),
            exit = shrinkVertically(AsterSpring.spatial()) + fadeOut(AsterSpring.editorial())
        ) {
            Surface(
                color = Surface,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Hairline),
                shadowElevation = 2.dp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp)
            ) {
                ConversationAttachmentPreview(
                    attachments = attachments,
                    loading = attachmentLoading,
                    onRemove = onRemoveImage
                )
            }
        }

        // 3.3 输入建议条：重构为 Apple 灵动悬浮胶囊（从底部弹性弹出）
        var shownSuggestion by remember { mutableStateOf<String?>(null) }
        if (suggestion != null) shownSuggestion = suggestion
        AnimatedVisibility(
            visible = suggestion != null && value.isBlank(),
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = AsterSpring.spatial()
            ) + scaleIn(
                initialScale = 0.90f,
                animationSpec = AsterSpring.gentleBounce()
            ) + fadeIn(AsterSpring.editorial()),
            exit = slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = AsterSpring.snappy()
            ) + scaleOut(
                targetScale = 0.92f,
                animationSpec = AsterSpring.snappy()
            ) + fadeOut(tween(120))
        ) {
            Surface(
                onClick = onSuggestionClick,
                color = Surface,
                contentColor = Ink,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.35f)),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .appleSpringPressable(pressedScale = 0.96f)
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = AccentSoft,
                        shape = CircleShape
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                "输入建议",
                                Modifier.size(13.dp),
                                tint = Accent
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "下一句",
                                color = Accent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        shownSuggestion.orEmpty(),
                        Modifier.weight(1f),
                        color = Ink,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "填入 ↵",
                        color = Accent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Claude 风格悬浮主输入舱
        Surface(
            color = Surface,
            shape = cardShape,
            border = BorderStroke(if (isFocused) 1.4.dp else 1.dp, composerBorderColor),
            shadowElevation = composerElevation,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = composerScale
                    scaleY = composerScale
                }
                .testTag("${testTag}-composer")
        ) {
            Column(Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { if (!editorOpen) edit(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("${testTag}-input")
                        .focusRequester(resolvedFocusRequester)
                        .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp)
                        .heightIn(min = 26.dp, max = 136.dp)
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
                                Text(
                                    placeholder,
                                    color = MutedInk.copy(alpha = 0.78f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                // 底部控制栏
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧圆角暖石附件/工具按钮
                    Surface(
                        onClick = {
                            focus.clearFocus()
                            onOptionsClick()
                        },
                        enabled = !loading && !attachmentLoading,
                        color = SurfaceInset.copy(alpha = 0.8f),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(38.dp)
                            .appleSpringPressable(pressedScale = 0.88f, enabled = !loading && !attachmentLoading)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (attachmentLoading) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    color = Accent,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Add,
                                    "输入选项",
                                    Modifier.size(22.dp),
                                    tint = Ink
                                )
                            }
                        }
                    }

                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            contextAction?.invoke()
                            reasoningAction?.invoke()
                        }
                    }

                    AnimatedVisibility(
                        visible = isFocused,
                        enter = scaleIn(0.8f, AsterSpring.snappy()) + fadeIn(AsterSpring.editorial()),
                        exit = scaleOut(0.8f, AsterSpring.snappy()) + fadeOut(tween(90))
                    ) {
                        IconButton(
                            onClick = {
                                val retained = fieldValue.copy(composition = null)
                                editorOpen = true
                                focus.clearFocus()
                                fieldValue = retained
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .appleSpringPressable(pressedScale = 0.88f)
                        ) {
                            Icon(Icons.Rounded.OpenInFull, "展开草稿", Modifier.size(19.dp), tint = MutedInk)
                        }
                    }

                    // Claude 标志性陶土色发送按钮（带 Apple 弹簧缩放与颜色过渡）
                    val canTrigger = loading || (enabledToSend && !attachmentLoading)
                    val sendContainerColor by animateColorAsState(
                        targetValue = when {
                            loading -> Night
                            canTrigger -> Accent
                            else -> SurfaceInset
                        },
                        animationSpec = AsterSpring.editorial(),
                        label = "SendBtnColor"
                    )
                    val sendContentColor by animateColorAsState(
                        targetValue = if (canTrigger) Color.White else MutedInk.copy(alpha = 0.55f),
                        animationSpec = AsterSpring.editorial(),
                        label = "SendBtnContentColor"
                    )
                    val sendScale by animateFloatAsState(
                        targetValue = if (canTrigger) 1f else 0.92f,
                        animationSpec = AsterSpring.gentleBounce(),
                        label = "SendBtnReadyScale"
                    )

                    FilledIconButton(
                        onClick = {
                            haptics.performHapticFeedback(
                                if (loading) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
                            )
                            if (loading) onStop() else {
                                focus.clearFocus()
                                onSend()
                            }
                        },
                        enabled = canTrigger,
                        modifier = Modifier
                            .size(42.dp)
                            .graphicsLayer {
                                scaleX = sendScale
                                scaleY = sendScale
                            }
                            .appleSpringPressable(pressedScale = 0.86f, enabled = canTrigger),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = sendContainerColor,
                            contentColor = sendContentColor,
                            disabledContainerColor = SurfaceInset,
                            disabledContentColor = MutedInk.copy(alpha = 0.45f)
                        )
                    ) {
                        AnimatedContent(
                            targetState = loading,
                            transitionSpec = {
                                (scaleIn(0.65f, AsterSpring.gentleBounce()) + fadeIn(AsterSpring.editorial())) togetherWith
                                    (scaleOut(0.65f, AsterSpring.snappy()) + fadeOut(tween(110)))
                            },
                            label = "send-stop"
                        ) { isLoading ->
                            if (isLoading) Icon(Icons.Rounded.Stop, "停止生成", Modifier.size(20.dp))
                            else if (configureRequired) Icon(Icons.Rounded.Settings, "选择模型", Modifier.size(20.dp))
                            else Icon(Icons.Rounded.ArrowUpward, "发送", Modifier.size(21.dp))
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
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "aster-thinking-float"
    )
    val sway by transition.animateFloat(
        initialValue = -10f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "aster-thinking-sway"
    )
    val subtitleAlpha by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "aster-thinking-subtitle"
    )
    val lift = with(density) { 3.dp.toPx() } * sin(PI * float).toFloat()

    Surface(
        color = Surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.28f)),
        shadowElevation = 2.dp,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(AccentSoft),
                contentAlignment = Alignment.Center
            ) {
                AsterArtwork(
                    Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            translationY = -lift
                            rotationZ = sway
                        }
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Aster 正在深度构思",
                    color = Ink,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "正在梳理上下文与逻辑链...",
                    color = Accent.copy(alpha = subtitleAlpha),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * Claude 3.7 风格折叠式深度思考手风琴面板（带左侧陶土色垂直引导线与 Apple 弹簧展开）
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

    LaunchedEffect(streaming) {
        if (wasStreaming && !streaming) expanded = false
        wasStreaming = streaming
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = AsterSpring.snappy(),
        label = "ReasoningChevron"
    )
    val borderColor by animateColorAsState(
        targetValue = if (streaming) Accent.copy(alpha = 0.45f) else Hairline,
        animationSpec = AsterSpring.editorial(),
        label = "ReasoningBorder"
    )

    Surface(
        onClick = { expanded = !expanded },
        color = Surface,
        contentColor = Ink,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .appleSpringPressable(pressedScale = 0.985f)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左侧 Claude 陶土柔光状态徽章
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (streaming) AccentSoft else SurfaceInset),
                    contentAlignment = Alignment.Center
                ) {
                    if (streaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Accent,
                            trackColor = Accent.copy(alpha = 0.18f),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Psychology,
                            null,
                            Modifier.size(16.dp),
                            tint = Accent
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (streaming) "正在深度思考..." else "已深度思考 · ${reasoning.length} 字",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (streaming) Accent else Ink.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = SurfaceInset.copy(alpha = 0.75f),
                    shape = CircleShape
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (expanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedInk
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            null,
                            Modifier
                                .size(15.dp)
                                .graphicsLayer { rotationZ = chevronRotation },
                            tint = MutedInk
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(AsterSpring.editorial()) + expandVertically(animationSpec = AsterSpring.spatial()),
                exit = fadeOut(tween(110)) + shrinkVertically(animationSpec = AsterSpring.snappy())
            ) {
                Row(
                    modifier = Modifier
                        .padding(top = 10.dp, start = 4.dp)
                        .height(IntrinsicSize.Min)
                ) {
                    // Claude 标志性左侧垂直思考时间轴线
                    Box(
                        modifier = Modifier
                            .width(2.5.dp)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(Accent.copy(alpha = 0.48f))
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MutedInk,
                        lineHeight = 20.sp
                    )
                }
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
    Surface(
        onClick = onClick,
        color = if (accent) AccentSoft else Color.Transparent,
        contentColor = if (accent) Accent else Ink.copy(alpha = 0.72f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .size(44.dp)
            .appleSpringPressable(pressedScale = 0.88f)
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            val fitted = fittedImageDp(attachment.width, attachment.height, maxWidth = 200f, maxHeight = 200f)
            val frame = if (fitted == null) Modifier.size(116.dp) else Modifier.size(fitted.first.dp, fitted.second.dp)
            Surface(
                color = Surface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Hairline),
                shadowElevation = 1.dp
            ) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    modifier = frame.clip(RoundedCornerShape(15.dp))
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
        Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            Box(Modifier.size(76.dp)) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                )
                Surface(
                    onClick = { onRemove(attachment.id) },
                    enabled = !loading,
                    color = Night.copy(alpha = 0.82f),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .appleSpringPressable(pressedScale = 0.88f, enabled = !loading)
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
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 1.dp,
        modifier = modifier.appleSpringPressable(pressedScale = 0.95f, enabled = enabled)
    ) {
        Column(
            Modifier
                .heightIn(min = 86.dp)
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (enabled) AccentSoft else SurfaceInset),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = if (enabled) Accent else MutedInk)
            }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MutedInk, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun ConversationReadingVeil(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.22f to Canvas.copy(alpha = 0.72f),
                1f to Canvas
            )
        )
    )
}

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
            } else if (!listState.canScrollForward) {
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
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(0.72f, AsterSpring.gentleBounce()) +
            slideInVertically(AsterSpring.spatial()) { it / 2 } +
            fadeIn(tween(fadeMillisIn)),
        exit = scaleOut(0.78f, AsterSpring.snappy()) +
            slideOutVertically(AsterSpring.snappy()) { it / 3 } +
            fadeOut(tween(fadeMillisOut))
    ) {
        Surface(
            onClick = onClick,
            color = Surface,
            contentColor = Accent,
            shape = CircleShape,
            border = BorderStroke(1.dp, Accent.copy(alpha = 0.35f)),
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(46.dp)
                .appleSpringPressable(pressedScale = 0.88f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    if (loading) "跟随生成" else "回到底部",
                    Modifier.size(23.dp)
                )
            }
        }
    }
}

@Composable
fun ConversationCopyAction(content: String) {
    val context = LocalContext.current
    var copied by remember(content) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1800)
            copied = false
        }
    }
    ConversationMessageAction(
        if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
        if (copied) "已复制" else "复制",
        accent = copied,
        onClick = {
            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("Aster", content)
            )
            copied = true
        }
    )
}
