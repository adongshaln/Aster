package com.adong.adchat.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.adong.adchat.ui.fittedImageDp
import com.adong.adchat.ui.theme.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Aster v4.0 — Claude App 极简无框美学 × Apple 果冻阻尼物理交互
 * ============================================================
 * 核心颠覆性设计：
 * 1. 彻底重做 `ConversationReasoningPanel`（思考面板）：
 *    - 废除上一版沉重的卡片背景框、外描边、左侧圆底图标徽章与右侧药丸标签；
 *    - 1:1 对齐 Claude 移动端原生极简设计：平时仅表现为一行轻盈无框的微光文字 `思考过程 ›`（流式时文字本身柔和呼吸脉动 `正在思考… ›`），
 *      箭头紧贴文字右侧，点击时触发果冻形变并以 `90°` 果冻弹簧旋转；
 *    - 展开时以高阻尼弹簧 (`AsterSpring.spatial()`) 丝滑垂落，左侧仅保留一条 `1.5.dp` 极细暖灰垂直引导线，克制、通透、零视觉负担。
 * 2. 彻底简化 `ConversationThinkingIndicator`：
 *    - 移除外层卡片框与双行累赘文案，让 `AsterArtwork` 星标直接悬浮在羊皮纸画布上做果冻呼吸，旁配单行微光小字。
 * 3. `ConversationComposer` 输入舱与建议条：
 *    - 3.3 预测建议条化繁为简，改为左对齐悬浮轻胶囊，以 Apple 果冻弹簧 (`AsterSpring.jelly()`) 从输入框上方Q弹浮现；
 *    - 输入舱聚焦时产生细腻的阻尼抬升与果冻微张 (`scaleX/scaleY`)，发送按钮随输入状态以果冻弹簧 (`dampingRatio = 0.52f`) 灵动缩放。
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
    val cardShape = RoundedCornerShape(26.dp)

    // Apple 阻尼抬升 + 果冻微张感：聚焦时输入框轻微舒展并加深柔和阴影
    val composerScaleX by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.992f,
        animationSpec = AsterSpring.jelly(),
        label = "ComposerScaleX"
    )
    val composerScaleY by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.988f,
        animationSpec = AsterSpring.spatial(),
        label = "ComposerScaleY"
    )
    val composerElevation by animateDpAsState(
        targetValue = if (isFocused) 6.dp else 2.dp,
        animationSpec = AsterSpring.spatial(),
        label = "ComposerElevation"
    )
    val composerBorderColor by animateColorAsState(
        targetValue = if (isFocused) Ink.copy(alpha = 0.22f) else Hairline.copy(alpha = 0.85f),
        animationSpec = AsterSpring.editorial(),
        label = "ComposerBorder"
    )

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 4.dp, bottom = 10.dp)
    ) {
        // 附件预览条
        AnimatedVisibility(
            visible = attachments.isNotEmpty(),
            enter = expandVertically(animationSpec = AsterSpring.spatial()) +
                fadeIn(animationSpec = AsterSpring.editorial()),
            exit = shrinkVertically(animationSpec = AsterSpring.spatial()) +
                fadeOut(animationSpec = AsterSpring.editorial())
        ) {
            Surface(
                color = Surface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(0.8.dp, Hairline),
                shadowElevation = 1.dp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 5.dp)
            ) {
                ConversationAttachmentPreview(
                    attachments = attachments,
                    loading = attachmentLoading,
                    onRemove = onRemoveImage
                )
            }
        }

        // 3.3 输入建议条：极简 Claude 悬浮轻胶囊 + Apple 果冻弹簧弹出
        var shownSuggestion by remember { mutableStateOf<String?>(null) }
        if (suggestion != null) shownSuggestion = suggestion
        AnimatedVisibility(
            visible = suggestion != null && value.isBlank(),
            enter = slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = AsterSpring.spatial()
            ) + scaleIn(
                initialScale = 0.86f,
                transformOrigin = TransformOrigin(0.15f, 1f),
                animationSpec = AsterSpring.jelly()
            ) + fadeIn(animationSpec = AsterSpring.editorial()),
            exit = slideOutVertically(
                targetOffsetY = { it / 3 },
                animationSpec = AsterSpring.snappy()
            ) + scaleOut(
                targetScale = 0.90f,
                transformOrigin = TransformOrigin(0.15f, 1f),
                animationSpec = AsterSpring.snappy()
            ) + fadeOut(animationSpec = tween(110))
        ) {
            Surface(
                onClick = onSuggestionClick,
                color = Surface.copy(alpha = 0.94f),
                contentColor = Ink,
                shape = CircleShape,
                border = BorderStroke(0.8.dp, Hairline),
                shadowElevation = 1.5.dp,
                modifier = Modifier
                    .padding(bottom = 7.dp)
                    .appleSpringPressable(pressedScale = 0.95f)
            ) {
                Row(
                    Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        "输入建议",
                        Modifier.size(14.dp),
                        tint = Accent
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        shownSuggestion.orEmpty(),
                        Modifier.weight(1f, fill = false),
                        color = Ink.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.NorthWest,
                        null,
                        Modifier.size(13.dp),
                        tint = MutedInk.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Claude 极简温润输入舱
        Surface(
            color = Surface,
            shape = cardShape,
            border = BorderStroke(0.8.dp, composerBorderColor),
            shadowElevation = composerElevation,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = composerScaleX
                    scaleY = composerScaleY
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
                        .padding(start = 18.dp, end = 18.dp, top = 15.dp, bottom = 2.dp)
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
                                    color = MutedInk.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            focus.clearFocus()
                            onOptionsClick()
                        },
                        enabled = !loading && !attachmentLoading,
                        modifier = Modifier
                            .size(42.dp)
                            .appleSpringPressable(pressedScale = 0.86f, enabled = !loading && !attachmentLoading)
                    ) {
                        if (attachmentLoading) {
                            AsterLoadingIndicator(size = 18.dp)
                        } else {
                            Icon(
                                Icons.Rounded.Add,
                                "输入选项",
                                Modifier.size(24.dp),
                                tint = MutedInk
                            )
                        }
                    }

                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            contextAction?.invoke()
                            reasoningAction?.invoke()
                        }
                    }

                    AnimatedVisibility(
                        visible = isFocused,
                        enter = scaleIn(
                            initialScale = 0.75f,
                            animationSpec = AsterSpring.jelly()
                        ) + fadeIn(animationSpec = AsterSpring.editorial()),
                        exit = scaleOut(
                            targetScale = 0.75f,
                            animationSpec = AsterSpring.snappy()
                        ) + fadeOut(animationSpec = tween(90))
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
                            Icon(Icons.Rounded.OpenInFull, "展开草稿", Modifier.size(18.dp), tint = MutedInk)
                        }
                    }

                    // Claude 极简陶土圆点发送按钮（带 Apple 果冻弹簧缩放）
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
                        targetValue = if (canTrigger) Color.White else MutedInk.copy(alpha = 0.45f),
                        animationSpec = AsterSpring.editorial(),
                        label = "SendContentColor"
                    )
                    val sendScale by animateFloatAsState(
                        targetValue = if (canTrigger) 1f else 0.88f,
                        animationSpec = AsterSpring.jelly(),
                        label = "SendScale"
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
                            .size(38.dp)
                            .graphicsLayer {
                                scaleX = sendScale
                                scaleY = sendScale
                            }
                            .appleSpringPressable(pressedScale = 0.84f, enabled = canTrigger),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = sendContainerColor,
                            contentColor = sendContentColor,
                            disabledContainerColor = SurfaceInset,
                            disabledContentColor = MutedInk.copy(alpha = 0.4f)
                        )
                    ) {
                        AnimatedContent(
                            targetState = loading,
                            transitionSpec = {
                                (scaleIn(
                                    initialScale = 0.55f,
                                    animationSpec = AsterSpring.jelly()
                                ) + fadeIn(animationSpec = AsterSpring.editorial())) togetherWith
                                    (scaleOut(
                                        targetScale = 0.55f,
                                        animationSpec = AsterSpring.snappy()
                                    ) + fadeOut(animationSpec = tween(100)))
                            },
                            label = "send-stop"
                        ) { isLoading ->
                            if (isLoading) Icon(Icons.Rounded.Stop, "停止生成", Modifier.size(18.dp))
                            else if (configureRequired) Icon(Icons.Rounded.Settings, "选择模型", Modifier.size(18.dp))
                            else Icon(Icons.Rounded.ArrowUpward, "发送", Modifier.size(19.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Claude App 原生风格极简无框等待指示器：
 * 星标直接呼吸悬浮在画布上，配合果冻弹性脉动，彻底去除冗余卡片边框。
 */
@Composable
fun ConversationThinkingIndicator() {
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "aster-thinking")
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "aster-thinking-float"
    )
    val sway by transition.animateFloat(
        initialValue = -12f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "aster-thinking-sway"
    )
    val pulseScale by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "aster-thinking-scale"
    )
    val liftPx = with(density) { 2.5.dp.toPx() }

    Row(
        Modifier
            .heightIn(min = 38.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            AsterArtwork(
                Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        translationY = -liftPx * sin(PI * float).toFloat()
                        rotationZ = sway
                        scaleX = pulseScale
                        scaleY = pulseScale
                    }
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "正在思考…",
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.claudeStreamingPulse()
        )
    }
}

/**
 * Claude App 原生极简无框思考面板 (Featherlight Minimalist Reasoning Panel)：
 * - 零外框、零背景盒、零多余徽章：平时仅是一行轻巧的 `正在思考… ›` 或 `思考过程 ›`；
 * - 箭头紧跟文字右侧，点击时整行产生 Apple 果冻形变，箭头以 `AsterSpring.jelly()` 旋转 90°；
 * - 展开过程采用高粘滞阻尼弹簧 (`AsterSpring.spatial()`)，左侧仅一条 `1.5.dp` 极细暖灰导引线衬托思考文本。
 */
@Composable
fun ConversationReasoningPanel(
    reasoning: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    bodyStarted: Boolean = false
) {
    if (reasoning.isBlank()) return
    val thinkingLive = streaming && !bodyStarted
    var expanded by remember { mutableStateOf(thinkingLive) }
    var wasLive by remember { mutableStateOf(false) }

    LaunchedEffect(thinkingLive) {
        if (wasLive && !thinkingLive) expanded = false
        wasLive = thinkingLive
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = AsterSpring.jelly(),
        label = "ReasoningChevronJelly"
    )
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        // 极简无框触发行（对齐 Claude App 原生 "Thought for X seconds ›"）
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { expanded = !expanded }
                )
                .appleSpringPressable(pressedScale = 0.94f)
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (thinkingLive) "正在思考…" else "已思考 · ${reasoning.length} 字",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                ),
                color = MutedInk,
                modifier = if (thinkingLive) Modifier.claudeStreamingPulse() else Modifier
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = if (expanded) "收起思考过程" else "展开思考过程",
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
                tint = MutedInk.copy(alpha = 0.72f)
            )
        }

        // 粘滞阻尼展开正文：仅保留左侧 1.5dp 极细竖线，彻底告别笨重卡片感
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = AsterSpring.editorial()) +
                expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = AsterSpring.spatial()
                ),
            exit = fadeOut(animationSpec = tween(120)) +
                shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = AsterSpring.spatial()
                )
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 6.dp, start = 4.dp, bottom = 4.dp)
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Hairline)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MutedInk.copy(alpha = 0.85f),
                    lineHeight = 20.sp
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
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = if (accent) Accent else MutedInk.copy(alpha = 0.82f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .size(44.dp)
            .appleSpringPressable(pressedScale = 0.86f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
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
                border = BorderStroke(0.8.dp, Hairline)
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
            Box(Modifier.size(74.dp)) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(15.dp))
                )
                Surface(
                    onClick = { onRemove(attachment.id) },
                    enabled = !loading,
                    color = Night.copy(alpha = 0.78f),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                        .appleSpringPressable(pressedScale = 0.86f, enabled = !loading)
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
        border = BorderStroke(0.8.dp, Hairline),
        modifier = modifier.appleSpringPressable(pressedScale = 0.94f, enabled = enabled)
    ) {
        Column(
            Modifier
                .heightIn(min = 84.dp)
                .padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) Accent else MutedInk)
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
            detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MutedInk, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun ConversationReadingVeil(modifier: Modifier = Modifier) {
    // Claude 官方 7 档非线性 Alpha 渐变（逆向规范）：28dp 羽化距离内按 y≈x^1.5 幂律采样，
    // 消除高分屏线性渐变的色阶断层。
    Box(
        modifier.background(
            Brush.verticalGradient(
                0f to Canvas.copy(alpha = 0.72f),
                0.15f to Canvas.copy(alpha = 0.76f),
                0.30f to Canvas.copy(alpha = 0.82f),
                0.45f to Canvas.copy(alpha = 0.88f),
                0.60f to Canvas.copy(alpha = 0.93f),
                0.75f to Canvas.copy(alpha = 0.97f),
                0.87f to Canvas.copy(alpha = 0.99f),
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
        enter = scaleIn(
            initialScale = 0.65f,
            animationSpec = AsterSpring.jelly()
        ) + slideInVertically(animationSpec = AsterSpring.spatial()) { it / 2 } +
            fadeIn(animationSpec = tween(fadeMillisIn)),
        exit = scaleOut(
            targetScale = 0.72f,
            animationSpec = AsterSpring.snappy()
        ) + slideOutVertically(animationSpec = AsterSpring.snappy()) { it / 3 } +
            fadeOut(animationSpec = tween(fadeMillisOut))
    ) {
        Surface(
            onClick = onClick,
            color = Surface,
            contentColor = Ink,
            shape = CircleShape,
            border = BorderStroke(0.8.dp, Hairline),
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .size(44.dp)
                .appleSpringPressable(pressedScale = 0.86f)
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
