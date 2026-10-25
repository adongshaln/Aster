package com.adong.adchat.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.adong.adchat.data.ReasoningPolicy
import com.adong.adchat.ui.theme.*
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The popup also owns touches over its entry, so an outside dismissal cannot reopen it. */
@Composable
fun ComposerReasoningControl(
    model: String,
    effort: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEffortChange: (String) -> Unit,
    onModelClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (ReasoningPolicy.choices(model).isEmpty()) return
    val choice = ReasoningPolicy.choiceOf(model, effort)
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 10.dp.roundToPx() }
    val position = remember(margin) { ReasoningPopupPositionProvider(margin) }
    val expandedChangeState = rememberUpdatedState(onExpandedChange)
    BackHandler(expanded) { onExpandedChange(false) }
    Box(modifier.onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow()
        position.anchorBounds = IntRect(
            IntOffset(origin.x.roundToInt(), origin.y.roundToInt()), coordinates.size)
    }) {
        Surface(
            onClick = { onExpandedChange(!expanded) }, enabled = enabled,
            shape = CircleShape, color = SurfaceInset.copy(alpha = .75f), contentColor = MutedInk,
            modifier = Modifier.heightIn(min = 44.dp).appleSpringPressable(enabled = enabled, pressedScale = .97f)
                .semantics {
                    contentDescription = "选择思考强度"
                    stateDescription = "${choice?.label ?: "自动"}，${if (expanded) "已展开" else "已收起"}"
                }
        ) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("思考强度", style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp))
            }
        }
        if (expanded && enabled) {
            val motion = LocalAsterMotionEnabled.current
            val entrance = remember { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(Unit) { entrance.animateTo(1f, if (motion) AsterSpring.spatial() else snap()) }
            Popup(popupPositionProvider = position, onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true,
                    usePlatformDefaultWidth = false)) {
                Layout(content = {
                    // Disjoint hit regions keep taps over the entry in this window without
                    // putting a gesture detector on top of the slider or model button.
                    repeat(4) {
                        Box(Modifier.pointerInput(Unit) {
                            detectTapGestures { expandedChangeState.value(false) }
                        })
                    }
                    Surface(
                        shape = RoundedCornerShape(25.dp), color = Surface, contentColor = Ink,
                        border = BorderStroke(1.dp, Hairline), shadowElevation = 12.dp,
                        modifier = Modifier.graphicsLayer {
                                alpha = entrance.value.coerceIn(0f, 1f)
                                scaleX = .94f + .06f * entrance.value
                                scaleY = .89f + .11f * entrance.value
                                transformOrigin = TransformOrigin(.5f, 1f)
                            }
                            .testTag("reasoning-popover").semantics { paneTitle = "思考强度" }
                    ) {
                        ComposerReasoningSlider(model, effort, onEffortChange,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            onModelClick = { onExpandedChange(false); onModelClick() })
                    }
                }) { measurables, constraints ->
                    val anchor = position.anchorBounds
                    val above = (anchor.top - margin).coerceAtLeast(0)
                    val below = (constraints.maxHeight - anchor.bottom - margin).coerceAtLeast(0)
                    val available = maxOf(above, below)
                    val actualGap = minOf(gap, available)
                    val width = constraints.maxWidth
                    val cardWidth = minOf(340.dp.roundToPx(), (width - 2 * margin).coerceAtLeast(0))
                    val card = measurables[4].measure(Constraints(
                        minWidth = cardWidth, maxWidth = cardWidth,
                        maxHeight = available - actualGap
                    ))
                    val height = anchor.height + actualGap + card.height
                    val opensAbove = card.height + actualGap <= above
                    val cardX = (anchor.center.x - card.width / 2)
                        .coerceIn(margin, (width - card.width - margin).coerceAtLeast(margin))
                    val cardY = if (opensAbove) 0 else anchor.height + actualGap
                    val cardBottom = cardY + card.height
                    val top = measurables[0].measure(Constraints.fixed(width, cardY))
                    val bottom = measurables[1].measure(Constraints.fixed(width, height - cardBottom))
                    val left = measurables[2].measure(Constraints.fixed(cardX, card.height))
                    val rightWidth = (width - cardX - card.width).coerceAtLeast(0)
                    val right = measurables[3].measure(Constraints.fixed(rightWidth, card.height))
                    layout(width, height) {
                        top.place(0, 0)
                        bottom.place(0, cardBottom)
                        left.place(0, cardY)
                        right.place(cardX + card.width, cardY)
                        card.place(cardX, cardY)
                    }
                }
            }
        }
    }
}

private class ReasoningPopupPositionProvider(private val margin: Int) : PopupPositionProvider {
    var anchorBounds by mutableStateOf(IntRect.Zero)

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        // Popup also refreshes this during IME adjustPan, when layout callbacks may not run.
        this.anchorBounds = anchorBounds
        val above = anchorBounds.bottom - popupContentSize.height
        return IntOffset(0, if (above >= margin) above else anchorBounds.top)
    }
}

/** Continuous while dragging; only supported effort levels are committed after release. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerReasoningSlider(
    model: String,
    effort: String,
    onEffortChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onModelClick: () -> Unit = {}
) {
    val choices = remember(model) { ReasoningPolicy.choices(model) }
    val levels = remember(choices) { choices.filterNot { it.id == ReasoningPolicy.DEFAULT } }
    if (levels.isEmpty()) return
    val resolved = ReasoningPolicy.choiceOf(model, effort)?.id ?: ReasoningPolicy.DEFAULT
    var selection by remember(model) { mutableStateOf(resolved) }
    var pending by remember(model) { mutableStateOf(false) }
    val initialIndex = levels.indexOfFirst { it.id == resolved }.takeIf { it >= 0 } ?: levels.lastIndex / 2
    val range = 0f..levels.lastIndex.coerceAtLeast(1).toFloat()
    var position by remember(model) { mutableFloatStateOf(initialIndex.toFloat()) }
    var settleJob by remember(model) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    var peakEntry by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val automatic = selection == ReasoningPolicy.DEFAULT
    val index = levels.indexOfFirst { it.id == selection }.takeIf { it >= 0 }
    val sliderIndex = index ?: levels.lastIndex / 2
    val highest = !automatic && selection == levels.last().id
    val label = if (automatic) "自动" else levels[index ?: 0].label
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dark = LocalThemeDark.current
    val purple = if (dark) Color(0xFFCB9CFF) else Color(0xFF9748EC)
    val clay = if (dark) Accent else Color(0xFFB94F35)
    val titleColor = if (!enabled) MutedInk else if (highest) purple else if (automatic) Ink else clay
    val inactive = SurfaceInset
    val ticks = MutedInk.copy(alpha = .44f)
    val motion = LocalAsterMotionEnabled.current
    val thumbStretch by animateFloatAsState(if (dragging && motion) 1f else 0f,
        animationSpec = if (motion) AsterSpring.snappy() else snap(), label = "reasoning-thumb-stretch")
    // A saved value echo must not interrupt the release animation. A real external change does.
    LaunchedEffect(model, resolved, enabled) {
        if (selection != resolved || !enabled) {
            settleJob?.cancel()
            pending = false
            selection = resolved
            position = initialIndex.toFloat()
        }
    }
    DisposableEffect(model) { onDispose { settleJob?.cancel() } }
    val sparkle = remember { Animatable(1f) }
    LaunchedEffect(highest, peakEntry, motion) {
        if (highest && motion) {
            sparkle.snapTo(0f)
            sparkle.animateTo(1f, tween(1400, easing = LinearOutSlowInEasing))
        } else sparkle.snapTo(1f)
    }
    fun preview(next: Int) {
        if (!enabled) return
        val id = levels[next.coerceIn(levels.indices)].id
        if (id != selection) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (id == levels.last().id) peakEntry++
            selection = id
        }
        pending = true
    }
    fun settle(next: Int) {
        settleJob?.cancel()
        if (!motion) {
            position = next.toFloat()
        } else {
            settleJob = scope.launch {
                animate(position, next.toFloat(), animationSpec = AsterSpring.snappy()) { value, _ ->
                    position = value.coerceIn(range)
                }
            }
        }
    }
    fun commit() {
        if (!enabled || !pending) return
        pending = false
        settle(levels.indexOfFirst { it.id == selection })
        if (selection != resolved) onEffortChange(selection)
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.titleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif),
            color = titleColor, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 3.dp))
        TextButton(onClick = onModelClick, contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.heightIn(min = 40.dp)) {
            Text(model.substringAfterLast('/'), color = MutedInk, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Rounded.ChevronRight, "选择模型", Modifier.size(17.dp), tint = MutedInk)
        }
        Slider(
            value = position, onValueChange = {
                settleJob?.cancel()
                position = it.coerceIn(range)
                preview(position.roundToInt())
            },
            onValueChangeFinished = ::commit, enabled = enabled, interactionSource = interactionSource,
            steps = 0, valueRange = range,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("reasoning-slider")
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionRight -> if (rtl) -1 else 1
                        Key.DirectionLeft -> if (rtl) 1 else -1
                        Key.DirectionUp -> 1
                        Key.DirectionDown -> -1
                        else -> 0
                    }
                    if (!enabled || direction == 0) false else {
                        if (event.type == KeyEventType.KeyDown) {
                            val next = (sliderIndex + direction).coerceIn(levels.indices)
                            preview(next)
                            settle(next)
                        }
                        else if (event.type == KeyEventType.KeyUp) commit()
                        true
                    }
                }.semantics {
                    contentDescription = "思考强度滑块"
                    stateDescription = if (automatic) "自动，由模型决定" else "$label，第 ${sliderIndex + 1} 档，共 ${levels.size} 档"
                    progressBarRangeInfo = ProgressBarRangeInfo(sliderIndex.toFloat(), range, (levels.size - 2).coerceAtLeast(0))
                    setProgress { value ->
                        if (!enabled || !value.isFinite()) false else {
                            var next = value.roundToInt().coerceIn(levels.indices)
                            if (levels.size == 2 && next == sliderIndex && value != sliderIndex.toFloat()) {
                                next = (sliderIndex + if (value > sliderIndex) 1 else -1).coerceIn(levels.indices)
                            }
                            if (!automatic && next == sliderIndex) false else { preview(next); commit(); true }
                        }
                    }
                },
            thumb = {
                Box(Modifier.size(36.dp).testTag("reasoning-thumb").graphicsLayer {
                    scaleX = 1f + .1f * thumbStretch
                    scaleY = 1f - .07f * thumbStretch
                }.shadow(if (automatic) 0.dp else 2.dp, CircleShape)
                    .background(if (automatic) Color.Transparent else Color.White, CircleShape)
                    .then(if (automatic) Modifier else Modifier.border(BorderStroke(.8.dp, Color(0xFFE0DFE4)), CircleShape)))
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                    // The track extends beneath the thumb so the end positions remain round caps.
                    val radius = size.height / 2f
                    val left = -radius
                    val width = size.width + 2 * radius
                    val capsule = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        left, 0f, size.width + radius, size.height, CornerRadius(radius))) }
                    clipPath(capsule) {
                        drawRect(inactive, topLeft = Offset(left, 0f), size = Size(width, size.height))
                        val fraction = position / levels.lastIndex.coerceAtLeast(1)
                        val selectedX = if (rtl) size.width * (1f - fraction) else size.width * fraction
                        if (!automatic) {
                            val activeLeft = if (rtl) selectedX else left
                            val activeRight = if (rtl) size.width + radius else selectedX
                            clipRect(left = activeLeft, right = activeRight) {
                            if (highest) {
                                drawRect(Brush.horizontalGradient(
                                    if (rtl) listOf(Color(0xFF8244CE), Color(0xFFBA70FA), Color(0xFFDE80B9))
                                    else listOf(Color(0xFFDE80B9), Color(0xFFBA70FA), Color(0xFF8244CE)),
                                    startX = left, endX = size.width + radius),
                                    topLeft = Offset(left, 0f), size = Size(width, size.height))
                                val t = sparkle.value
                                for (star in 0 until 18) {
                                    val base = ((star * 0.618034f) % 1f)
                                    val x = left + width * ((base + .06f * t) % 1f)
                                    val y = size.height * (.2f + .6f * ((star * .381966f) % 1f))
                                    val twinkle = if (motion && t < 1f) .45f + .35f * sin(t * 2f * PI.toFloat() + star) else .55f
                                    drawCircle(Color.White.copy(alpha = twinkle), (if (star % 4 == 0) 1.5f else .9f).dp.toPx(), Offset(x, y))
                                }
                                if (t < 1f && motion) {
                                    val sweep = left + width * t
                                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .2f * sin(PI.toFloat() * t)), Color.Transparent),
                                        startX = sweep - 36.dp.toPx(), endX = sweep + 36.dp.toPx()),
                                        topLeft = Offset(left, 0f), size = Size(width, size.height))
                                }
                            } else {
                                drawRect(clay, Offset(left, 0f), Size(width, size.height))
                            }
                            }
                        }
                        if (!highest) for (level in levels.indices) {
                            val x = size.width * level / levels.lastIndex.coerceAtLeast(1)
                            val logicalLevel = if (rtl) levels.lastIndex - level else level
                            drawCircle(if (!automatic && logicalLevel <= position) Color.White.copy(alpha = .42f) else ticks,
                                2.5.dp.toPx(), Offset(x, center.y))
                        }
                    }
                }
            }
        )
        TextButton(onClick = {
            settleJob?.cancel()
            selection = ReasoningPolicy.DEFAULT
            pending = false
            position = (levels.lastIndex / 2).toFloat()
            if (resolved != ReasoningPolicy.DEFAULT) onEffortChange(ReasoningPolicy.DEFAULT)
        }, enabled = enabled && !automatic, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 36.dp)) {
            Text(if (automatic) "由模型决定思考强度" else "使用模型默认", style = MaterialTheme.typography.labelSmall, color = MutedInk)
        }
    }
}
