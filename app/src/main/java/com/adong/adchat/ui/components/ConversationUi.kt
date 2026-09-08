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
    trailingActions: @Composable () -> Unit = {}
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
            value = fieldValue, onValueChange = edit,
            attachments = attachments, attachmentLoading = attachmentLoading,
            loading = loading, configureRequired = configureRequired,
            onRemoveImage = onRemoveImage,
            onDismiss = { editorOpen = false },
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
            shape = capsuleShape,
            border = BorderStroke(1.dp, if (isFocused) Accent.copy(alpha = .35f) else Hairline),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().testTag("$testTag-composer")
        ) {
            Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = minimumHeight)) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = edit,
                    modifier = Modifier.fillMaxWidth().testTag("$testTag-input").focusRequester(resolvedFocusRequester)
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
                                Text(placeholder, color = MutedInk, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                            onOptionsClick()
                        },
                        enabled = !loading && !attachmentLoading,
                        modifier = Modifier.size(46.dp)
                    ) {
                        if (attachmentLoading) {
                            CircularProgressIndicator(Modifier.size(19.dp), color = Accent, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Add, "输入选项", Modifier.size(29.dp), tint = Ink)
                        }
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        AnimatedVisibility(visible = isFocused, enter = fadeIn(tween(150)), exit = fadeOut(tween(90))) {
                            trailingActions()
                        }
                    }
                    AnimatedVisibility(visible = isFocused, enter = fadeIn(tween(150)), exit = fadeOut(tween(90))) {
                        IconButton(onClick = {
                            focus.clearFocus()
                            editorOpen = true
                        }, modifier = Modifier.size(46.dp)) {
                            Icon(Icons.Rounded.OpenInFull, "展开草稿", Modifier.size(21.dp), tint = MutedInk)
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
fun ConversationMessageAction(
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
        modifier = Modifier.heightIn(min = 48.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
        contentColor = if (enabled) Ink else MutedInk.copy(alpha = .45f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Hairline.copy(alpha = .75f)),
        modifier = modifier
    ) {
        Column(Modifier.heightIn(min = 84.dp).padding(horizontal = 8.dp, vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) Accent else MutedInk.copy(alpha = .4f))
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
        .46f to Canvas.copy(alpha = .38f),
        1f to Canvas.copy(alpha = .96f)
    )))
}

@Composable
fun ConversationJumpToBottom(visible: Boolean, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
        Surface(onClick = onClick, color = Surface, contentColor = Accent, shape = CircleShape,
            border = BorderStroke(1.dp, Hairline), tonalElevation = 0.dp, shadowElevation = 0.dp) {
            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (loading) "跟随生成" else "回到底部", style = MaterialTheme.typography.labelLarge)
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
