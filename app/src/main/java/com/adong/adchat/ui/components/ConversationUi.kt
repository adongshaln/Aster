package com.adong.adchat.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.adong.adchat.data.ChatImageAttachment
import com.adong.adchat.ui.fittedImageDp
import com.adong.adchat.ui.theme.*
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** One editor stays mounted while its shell opens from a compact capsule. */
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
    reasoningAction: (@Composable () -> Unit)? = null,
    onSurfaceBounds: (Rect) -> Unit = {},
    onExpandedChange: (Boolean) -> Unit = {}
) {
    val focus = LocalFocusManager.current
    val ownFocusRequester = remember { FocusRequester() }
    val resolvedFocusRequester = focusRequester ?: ownFocusRequester
    val haptics = LocalHapticFeedback.current
    val motionEnabled = LocalAsterMotionEnabled.current
    var isFocused by remember { mutableStateOf(false) }
    var shellHasFocus by remember { mutableStateOf(false) }
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
    val toolbarLiftPx = with(density) { 6.dp.toPx() }
    LaunchedEffect(isFocused) {
        if (!isFocused) return@LaunchedEffect
        var previousBottom = ime.getBottom(density)
        snapshotFlow { ime.getBottom(density) to imeTarget.getBottom(density) }.collect { (bottom, target) ->
            // A slider taking focus can also hide the IME; only dismiss the active text editor here.
            if (isFocused && target == 0 && bottom < previousBottom) focus.clearFocus()
            previousBottom = bottom
        }
    }

    // Moving focus from the editor to its slider or toolbar continues the same editing session.
    val expanded = shellHasFocus && !editorOpen
    val currentOnFocusChange by rememberUpdatedState(onFocusChange)
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    LaunchedEffect(expanded) {
        currentOnFocusChange(expanded)
        currentOnExpandedChange(expanded)
    }
    BackHandler(enabled = expanded && !isFocused) { focus.clearFocus() }
    val opening by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (motionEnabled) AsterSpring.releaseVertical() else snap(),
        label = "composer-opening"
    )
    val progress = if (motionEnabled) opening.coerceIn(0f, 1f) else if (expanded) 1f else 0f
    val shellProgress = if (motionEnabled) opening.coerceIn(-0.035f, 1.06f) else progress
    val keepTools = expanded || progress > 0.001f
    // Let the expanded toolbar arrive as a small wave instead of one flat opacity jump.
    // Exit stays quick so controls never linger after the capsule is closed.
    val contextRevealSpec = if (!motionEnabled) snap<Float>() else if (expanded) {
        tween(durationMillis = 190, delayMillis = 18, easing = FastOutSlowInEasing)
    } else tween(100, easing = FastOutLinearInEasing)
    val expandRevealSpec = if (!motionEnabled) snap<Float>() else if (expanded) {
        tween(durationMillis = 190, delayMillis = 42, easing = FastOutSlowInEasing)
    } else tween(100, easing = FastOutLinearInEasing)
    val reasoningRevealSpec = if (!motionEnabled) snap<Float>() else if (expanded) {
        tween(durationMillis = 190, delayMillis = 66, easing = FastOutSlowInEasing)
    } else tween(100, easing = FastOutLinearInEasing)
    val contextReveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = contextRevealSpec,
        label = "composer-context-reveal"
    )
    val expandReveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = expandRevealSpec,
        label = "composer-expand-reveal"
    )
    val reasoningReveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = reasoningRevealSpec,
        label = "composer-reasoning-reveal"
    )
    val sendSuggestion = suggestion?.takeIf {
        it.isNotBlank() && fieldValue.text.isEmpty() && value.isEmpty() &&
            attachments.isEmpty() && !loading && !attachmentLoading && !configureRequired
    }
    val canSend = !attachmentLoading &&
        (fieldValue.text.isNotBlank() || attachments.isNotEmpty() || configureRequired || sendSuggestion != null)
    val canTrigger = loading || canSend
    val submit: () -> Unit = {
        if (loading) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onStop()
        } else if (canSend) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            focus.clearFocus()
            if (sendSuggestion != null) onSuggestionClick() else onSend()
        }
    }
    val corner by animateDpAsState(
        targetValue = if (expanded || attachments.isNotEmpty()) 24.dp else 30.dp,
        animationSpec = if (motionEnabled) AsterSpring.jelly() else snap(),
        label = "composer-corner"
    )
    val elevation by animateDpAsState(
        targetValue = if (expanded) 6.dp else 2.dp,
        animationSpec = if (motionEnabled) AsterSpring.spatial() else snap(),
        label = "composer-elevation"
    )
    val outline by animateColorAsState(
        targetValue = if (expanded) Ink.copy(alpha = 0.20f) else Hairline.copy(alpha = 0.90f),
        animationSpec = if (motionEnabled) AsterSpring.editorial() else snap(),
        label = "composer-outline"
    )
    val sendContainer by animateColorAsState(
        targetValue = when {
            loading -> Ink
            canTrigger -> Accent
            else -> SurfaceInset
        },
        animationSpec = if (motionEnabled) AsterSpring.editorial() else snap(),
        label = "composer-send-color"
    )
    val sendInk = when {
        loading -> OnInk
        canTrigger -> OnAccent
        else -> MutedInk.copy(alpha = 0.45f)
    }
    val optionsInteraction = remember { MutableInteractionSource() }
    val sendInteraction = remember { MutableInteractionSource() }
    val expandInteraction = remember { MutableInteractionSource() }
    val inputStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink)
    val lineHeightPx = with(density) { maxOf(24.dp.roundToPx(), inputStyle.lineHeight.toPx().roundToInt()) }

    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 4.dp, bottom = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        val compactWidth = minOf(maxWidth * 0.88f, 360.dp)
        val shellWidth by animateDpAsState(
            targetValue = if (expanded || attachments.isNotEmpty()) maxWidth else compactWidth,
            animationSpec = if (motionEnabled) AsterSpring.releaseHorizontal() else snap(),
            label = "composer-width"
        )
        Surface(
            color = Surface,
            shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
            border = BorderStroke(0.8.dp, outline),
            shadowElevation = elevation.coerceAtLeast(0.dp),
            modifier = Modifier
                .width(shellWidth.coerceIn(0.dp, maxWidth))
                .testTag("${testTag}-composer")
                .onFocusChanged { shellHasFocus = it.hasFocus }
                .focusGroup()
                .onGloballyPositioned { onSurfaceBounds(it.boundsInWindow()) }
        ) {
            Column {
                // Attachments remain reachable when the keyboard is closed and are part of the shell.
                if (attachments.isNotEmpty()) {
                    ConversationAttachmentPreview(attachments, attachmentLoading, onRemoveImage)
                }
                Layout(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        BasicTextField(
                            value = fieldValue,
                            onValueChange = { if (!editorOpen) edit(it) },
                            modifier = Modifier
                                .layoutId("field")
                                .testTag("${testTag}-input")
                                .focusRequester(resolvedFocusRequester)
                                .onFocusChanged { isFocused = it.isFocused },
                            textStyle = inputStyle,
                            cursorBrush = SolidColor(Accent),
                            maxLines = 5,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(onSend = { submit() }),
                            decorationBox = { innerTextField ->
                                Box(Modifier.fillMaxWidth()) {
                                    val summarizeDraft = !expanded && fieldValue.text.isNotEmpty()
                                    if (fieldValue.text.isEmpty() || summarizeDraft) {
                                        Text(
                                            text = if (summarizeDraft) fieldValue.text.replace('\n', ' ')
                                                else sendSuggestion ?: placeholder,
                                            modifier = if (summarizeDraft) Modifier.clearAndSetSemantics {} else Modifier,
                                            color = if (summarizeDraft) Ink else MutedInk.copy(alpha = 0.68f),
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = if (expanded && sendSuggestion != null) 3 else 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    // Keeping the inner field in the same slot preserves selection and IME composition.
                                    Box(Modifier.graphicsLayer { alpha = if (summarizeDraft) 0f else 1f }) {
                                        innerTextField()
                                    }
                                }
                            }
                        )
                        IconButton(
                            onClick = { focus.clearFocus(); onOptionsClick() },
                            enabled = !loading && !attachmentLoading,
                            interactionSource = optionsInteraction,
                            modifier = Modifier.layoutId("options").size(48.dp)
                        ) {
                            if (attachmentLoading) AsterLoadingIndicator(size = 18.dp)
                            else Icon(
                                Icons.Rounded.Add, "输入选项",
                                Modifier.size(22.dp).appleSpringPressable(
                                    pressedScale = 0.90f,
                                    enabled = !loading && !attachmentLoading,
                                    interactionSource = optionsInteraction
                                ),
                                tint = MutedInk
                            )
                        }
                        IconButton(
                            onClick = submit,
                            enabled = canTrigger,
                            interactionSource = sendInteraction,
                            modifier = Modifier.layoutId("send").size(48.dp)
                        ) {
                            Box(
                                Modifier.size(38.dp)
                                    .appleSpringPressable(
                                        pressedScale = 0.91f, enabled = canTrigger, enableHaptics = false,
                                        interactionSource = sendInteraction
                                    )
                                    .clip(CircleShape).background(sendContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                val sendMode = when {
                                    loading -> "stop"
                                    configureRequired -> "settings"
                                    else -> "send"
                                }
                                AnimatedContent(
                                    targetState = sendMode,
                                    transitionSpec = {
                                        if (!motionEnabled) {
                                            fadeIn(snap()) togetherWith fadeOut(snap())
                                        } else {
                                            (fadeIn(AsterSpring.editorial()) +
                                                scaleIn(AsterSpring.jelly(), initialScale = 0.72f)) togetherWith
                                                (fadeOut(AsterSpring.snappy()) +
                                                    scaleOut(AsterSpring.snappy(), targetScale = 0.72f))
                                        }
                                    },
                                    label = "composer-send-icon"
                                ) { mode ->
                                    val icon = when (mode) {
                                        "stop" -> Icons.Rounded.Stop
                                        "settings" -> Icons.Rounded.Settings
                                        else -> Icons.Rounded.ArrowUpward
                                    }
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = when (mode) {
                                            "stop" -> "停止生成"
                                            "settings" -> "选择模型"
                                            else -> if (sendSuggestion != null) "发送建议" else "发送"
                                        },
                                        modifier = Modifier.size(if (mode == "stop") 18.dp else 20.dp),
                                        tint = sendInk
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier.layoutId("context")
                                .graphicsLayer {
                                    alpha = progress * contextReveal
                                    translationY = toolbarLiftPx * (1f - contextReveal)
                                }
                                .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {}),
                            contentAlignment = Alignment.CenterStart
                        ) { if (keepTools) contextAction?.invoke() }
                        Box(
                            Modifier.layoutId("expand").size(48.dp)
                                .graphicsLayer {
                                    alpha = progress * expandReveal
                                    translationY = toolbarLiftPx * (1f - expandReveal)
                                }
                                .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {})
                        ) {
                            if (keepTools) {
                                IconButton(
                                    onClick = {
                                        val retained = fieldValue.copy(composition = null)
                                        editorOpen = true
                                        focus.clearFocus()
                                        fieldValue = retained
                                    },
                                    enabled = expanded,
                                    interactionSource = expandInteraction,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Rounded.OpenInFull, "展开草稿",
                                        Modifier.size(19.dp).appleSpringPressable(
                                            pressedScale = 0.92f, interactionSource = expandInteraction
                                        ),
                                        tint = MutedInk
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier.layoutId("reasoning")
                                .graphicsLayer {
                                    alpha = progress * reasoningReveal
                                    translationY = toolbarLiftPx * (1f - reasoningReveal)
                                }
                                .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {})
                        ) { if (keepTools) reasoningAction?.invoke() }
                    }
                ) { measurables, constraints ->
                    val width = constraints.maxWidth
                    val buttonSize = 48.dp.roundToPx()
                    val collapsedHeight = maxOf(60.dp.roundToPx(), lineHeightPx + 20.dp.roundToPx())
                    fun blend(from: Int, to: Int) = (from + (to - from) * progress).roundToInt()
                    val fieldInset = blend(54.dp.roundToPx(), 18.dp.roundToPx())
                    val fieldTop = blend((collapsedHeight - lineHeightPx) / 2, 14.dp.roundToPx())
                    val field = measurables.first { it.layoutId == "field" }.measure(
                        Constraints(
                            minWidth = (width - 2 * fieldInset).coerceAtLeast(1),
                            maxWidth = (width - 2 * fieldInset).coerceAtLeast(1),
                            minHeight = lineHeightPx,
                            maxHeight = blend(lineHeightPx, maxOf(lineHeightPx, 132.dp.roundToPx()))
                        )
                    )
                    val options = measurables.first { it.layoutId == "options" }
                        .measure(Constraints.fixed(buttonSize, buttonSize))
                    val send = measurables.first { it.layoutId == "send" }
                        .measure(Constraints.fixed(buttonSize, buttonSize))
                    val expand = measurables.first { it.layoutId == "expand" }
                        .measure(Constraints.fixed(buttonSize, buttonSize))
                    val context = measurables.first { it.layoutId == "context" }.measure(
                        Constraints(maxWidth = (width - buttonSize * 3 - 12.dp.roundToPx()).coerceAtLeast(0), maxHeight = buttonSize)
                    )
                    val reasoningWidth = (width - buttonSize * 3 - context.width - 12.dp.roundToPx()).coerceAtLeast(0)
                    val reasoning = measurables.first { it.layoutId == "reasoning" }.measure(
                        Constraints(maxWidth = reasoningWidth, maxHeight = buttonSize)
                    )
                    val toolbarTop = 14.dp.roundToPx() + field.height + 4.dp.roundToPx()
                    val expandedHeight = toolbarTop + buttonSize + 6.dp.roundToPx()
                    // The shell may overshoot slightly; text constraints and control positions do not.
                    val height = constraints.constrainHeight(
                        (collapsedHeight + (expandedHeight - collapsedHeight) * shellProgress).roundToInt()
                            .coerceAtLeast(56.dp.roundToPx())
                    )
                    val buttonTop = blend((collapsedHeight - buttonSize) / 2, toolbarTop)
                    layout(width, height) {
                        field.placeRelative(fieldInset, fieldTop)
                        options.placeRelative(6.dp.roundToPx(), buttonTop)
                        send.placeRelative(width - buttonSize - 6.dp.roundToPx(), buttonTop)
                        if (keepTools) {
                            context.placeRelative(54.dp.roundToPx(), toolbarTop)
                            expand.placeRelative(width - 2 * buttonSize - 6.dp.roundToPx(), toolbarTop)
                            reasoning.placeRelative(54.dp.roundToPx() + context.width,
                                toolbarTop + (buttonSize - reasoning.height) / 2)
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
