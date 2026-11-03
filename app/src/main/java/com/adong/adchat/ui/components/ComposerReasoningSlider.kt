package com.adong.adchat.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import com.adong.adchat.data.ReasoningChoice
import com.adong.adchat.data.ReasoningPolicy
import com.adong.adchat.ui.theme.*
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Effort strength ladder matching `Nwflower/dsh-claude-style` (`packages/client/src/features/effort/`).
 * Levels with rank 0 (`none`, `off`, `default`) keep resting secondary ink; active thinking rungs
 * interpolate along the theme accent ramp (`--dsh-claude-effort-low` -> `--dsh-claude-effort-high`).
 */
private val EFFORT_STRENGTH_RANK = mapOf(
    ReasoningPolicy.DEFAULT to 0,
    "none" to 0,
    "off" to 0,
    "minimal" to 1,
    "light" to 2,
    "low" to 3,
    "medium" to 4,
    "high" to 5,
    "on" to 5,
    "xhigh" to 6,
    "max" to 7,
    "ultra" to 8
)

@Composable
internal fun rememberDshEffortTintColor(
    steps: List<ReasoningChoice>,
    index: Int,
    enabled: Boolean = true
): Color {
    if (!enabled || index !in steps.indices) return MutedInk
    val choice = steps[index]
    val rank = EFFORT_STRENGTH_RANK[choice.id] ?: 0
    if (rank <= 0) return MutedInk
    val activeSteps = steps.filter { (EFFORT_STRENGTH_RANK[it.id] ?: 0) > 0 }
    val activeIdx = activeSteps.indexOfFirst { it.id == choice.id }.coerceAtLeast(0)
    val t = if (activeSteps.size < 2) 1f else activeIdx.toFloat() / (activeSteps.size - 1).toFloat()
    val lowTone = lerp(SurfaceInset, Accent, 0.68f)
    val highTone = lerp(Ink, Accent, 0.82f)
    return lerp(lowTone, highTone, t.coerceIn(0f, 1f))
}

/**
 * Deterministic 32-bit integer hash ported from `dsh-claude-style`'s `t5` matrix generator.
 */
private fun dshMatrixHash(row: Int, col: Int, salt: Int): Float {
    var h = ((row + 1) * -1640531535) xor ((col + 1) * -2048144789) xor ((salt + 1) * 668265263)
    h = (h xor (h ushr 15)) * 625341585
    h = h xor (h ushr 13)
    return (h.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
}

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun dshMatrixBaseOpacity(colProgress: Float): Float {
    if (colProgress <= 0.05f) return 0f
    if (colProgress >= 0.75f) return 1f
    val a = (colProgress - 0.05f) / 0.70f
    return a * a * (3f - 2f * a)
}

private fun dshMatrixFlowStrength(row: Int, col: Int, colProgress: Float): Float {
    val bucket = min(8, floor(colProgress * 9f).toInt())
    val g = dshMatrixHash(bucket, row, 6)
    val c = dshMatrixHash(row, col, 7)
    val v = g * 0.4f + c * 0.6f
    return 0.04f + smoothStep(0.46f, 0.58f, v) * 0.92f
}

/**
 * `dsh-claude-style` composer seat & popover trigger for reasoning effort.
 * The popup also owns touches over its entry so an outside dismissal cannot reopen it.
 */
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
    val choices = remember(model) { ReasoningPolicy.choices(model) }
    if (choices.isEmpty()) return
    val levels = remember(choices) { choices.filterNot { it.id == ReasoningPolicy.DEFAULT } }
    if (levels.isEmpty()) return
    val choice = ReasoningPolicy.choiceOf(model, effort)
    val automatic = choice == null || choice.id == ReasoningPolicy.DEFAULT
    val levelIndex = levels.indexOfFirst { it.id == choice?.id }
    val triggerTint = if (automatic) {
        MutedInk
    } else {
        rememberDshEffortTintColor(levels, levelIndex, enabled)
    }
    val animatedTriggerColor by animateColorAsState(
        targetValue = triggerTint,
        animationSpec = AsterSpring.editorial(),
        label = "effort-trigger-color"
    )
    val triggerLabel = if (automatic) "自动" else choice?.label ?: "自动"
    val density = LocalDensity.current
    val margin = with(density) { 12.dp.roundToPx() }
    val gap = with(density) { 6.dp.roundToPx() }
    val position = remember(margin) { ReasoningPopupPositionProvider(margin) }
    val expandedChangeState = rememberUpdatedState(onExpandedChange)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = AsterSpring.snappy(),
        label = "effort-trigger-chevron"
    )
    BackHandler(expanded) { onExpandedChange(false) }

    Box(modifier.onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow()
        position.anchorBounds = IntRect(
            IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
            coordinates.size
        )
    }) {
        val triggerInteraction = remember { MutableInteractionSource() }
        Surface(
            onClick = { onExpandedChange(!expanded) },
            enabled = enabled,
            interactionSource = triggerInteraction,
            shape = RoundedCornerShape(8.dp),
            color = if (expanded) SurfaceInset.copy(alpha = 0.85f) else SurfaceInset.copy(alpha = 0.55f),
            contentColor = animatedTriggerColor,
            modifier = Modifier
                .heightIn(min = 34.dp)
                .appleSpringPressable(enabled = enabled, pressedScale = 0.95f, interactionSource = triggerInteraction)
                .semantics {
                    contentDescription = "选择思考强度"
                    stateDescription = "$triggerLabel，${if (expanded) "已展开" else "已收起"}"
                }
        ) {
            Row(
                Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = triggerLabel,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = animatedTriggerColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                    tint = MutedInk.copy(alpha = 0.78f)
                )
            }
        }
        if (expanded && enabled) {
            val motion = LocalAsterMotionEnabled.current
            val entrance = remember { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(Unit) {
                entrance.animateTo(1f, if (motion) AsterSpring.spatial() else snap())
            }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnClickOutside = true,
                    usePlatformDefaultWidth = false
                )
            ) {
                Layout(content = {
                    // Disjoint hit regions keep taps over the entry in this window without
                    // putting a gesture detector on top of the slider or model button.
                    repeat(4) {
                        Box(Modifier.pointerInput(Unit) {
                            detectTapGestures { expandedChangeState.value(false) }
                        })
                    }
                    // Matches `.dsh-claude-popover-card.dsh-claude-effort-popover`:
                    // 12dp radius, 1dp border-l1, compact 264..320dp width, bottom-right transform origin.
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Surface,
                        contentColor = Ink,
                        border = BorderStroke(1.dp, Hairline),
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .graphicsLayer {
                                alpha = entrance.value.coerceIn(0f, 1f)
                                scaleX = 0.97f + 0.03f * entrance.value
                                scaleY = 0.95f + 0.05f * entrance.value
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                            .testTag("reasoning-popover")
                            .semantics { paneTitle = "思考强度" }
                    ) {
                        ComposerReasoningSlider(
                            model = model,
                            effort = effort,
                            onEffortChange = onEffortChange,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                            onModelClick = {
                                onExpandedChange(false)
                                onModelClick()
                            }
                        )
                    }
                }) { measurables, constraints ->
                    val anchor = position.anchorBounds
                    val above = (anchor.top - margin).coerceAtLeast(0)
                    val below = (constraints.maxHeight - anchor.bottom - margin).coerceAtLeast(0)
                    val available = maxOf(above, below)
                    val actualGap = minOf(gap, available)
                    val width = constraints.maxWidth
                    val cardWidth = minOf(304.dp.roundToPx(), (width - 2 * margin).coerceAtLeast(264.dp.roundToPx().coerceAtMost(width)))
                    val card = measurables[4].measure(
                        Constraints(
                            minWidth = cardWidth,
                            maxWidth = cardWidth,
                            maxHeight = (available - actualGap).coerceAtLeast(0)
                        )
                    )
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

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // Popup also refreshes this during IME adjustPan, when layout callbacks may not run.
        this.anchorBounds = anchorBounds
        val above = anchorBounds.bottom - popupContentSize.height
        return IntOffset(0, if (above >= margin) above else anchorBounds.top)
    }
}

/**
 * Authentic port of `Nwflower/dsh-claude-style`'s `.dsh-claude-effort` reasoning slider:
 * - Header row: `"推理等级"` (`13sp` secondary ink) + directional animated value label (tinted along the effort ramp) + compact model/default action.
 * - Ends row: `"更快"` (`Faster`) on left and `"更强"` (`Smarter`) on right (`11sp` caption ink).
 * - Track: `30dp` outer height, `26dp` inner rounded rectangle (`8dp` corner radius), `4dp` circular step ticks, and `26%` primary ink fill bar.
 * - Apex (top rung) mode: hides fill and ticks, revealing a 5-row pixel dot matrix (`--dsh-claude-apex` & `--dsh-claude-apex-flash`) with wavefront entry, shimmer flash, and horizontal particle flow.
 * - Knob: `16dp × 30dp` rounded rectangle (`5dp` corner radius) with subtle border & shadow, glowing at apex and scaling `1.10×` while dragging.
 * - Continuous while dragging with magnetic sin-snap feel; only supported effort levels are committed on release.
 */
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
    var previousIndex by remember(model) { mutableIntStateOf(levels.indexOfFirst { it.id == resolved }.coerceAtLeast(0)) }
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
    val highest = !automatic && levels.size > 1 && selection == levels.last().id
    val label = if (automatic) "自动（默认）" else levels[index ?: 0].label
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dark = LocalThemeDark.current
    val motion = LocalAsterMotionEnabled.current

    // dsh-claude-style tokens:
    // --dsh-claude-apex: color-mix(in oklch, brand-primary 82%, label-primary)
    // --dsh-claude-apex-flash: color-mix(in srgb, brand-primary 52%, #ffffff)
    val currentAccent = Accent
    val currentInk = Ink
    val apexColor = remember(dark, currentAccent, currentInk) { lerp(currentInk, currentAccent, 0.82f) }
    val apexFlashColor = remember(dark, currentAccent) { lerp(Color.White, currentAccent, 0.52f) }
    val valueTint = if (automatic) MutedInk else rememberDshEffortTintColor(levels, sliderIndex, enabled)
    val animatedValueColor by animateColorAsState(
        targetValue = valueTint,
        animationSpec = AsterSpring.editorial(),
        label = "dsh-effort-value-color"
    )
    val trackBg = Ink.copy(alpha = if (dark) 0.11f else 0.075f)
    val trackFill = Ink.copy(alpha = 0.24f)
    val tickColor = Ink.copy(alpha = 0.26f)

    // `.dsh-claude-effort[data-dragging] .dsh-claude-effort-knob { scale: 1.1 }`
    val knobScale by animateFloatAsState(
        targetValue = if (dragging && motion) 1.10f else 1f,
        animationSpec = if (motion) AsterSpring.snappy() else snap(),
        label = "dsh-knob-scale"
    )
    val apexProgress by animateFloatAsState(
        targetValue = if (highest) 1f else 0f,
        animationSpec = if (motion) AsterSpring.editorial() else snap(),
        label = "dsh-apex-progress"
    )

    // Continuous time clock for the top-rung dot matrix (`--dsh-claude-period-flash: 1450ms`, `--dsh-claude-period-flow: 4000ms`)
    val infiniteTransition = rememberInfiniteTransition(label = "dsh-apex-matrix")
    val matrixClockSec by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (highest && motion) 58f else 0f,
        animationSpec = infiniteRepeatable(tween(58_000, easing = LinearEasing), RepeatMode.Restart),
        label = "dsh-matrix-clock"
    )
    val frontWave = remember { Animatable(1f) }
    LaunchedEffect(highest, peakEntry, motion) {
        if (highest && motion) {
            frontWave.snapTo(0f)
            frontWave.animateTo(1f, tween(650, easing = LinearEasing))
        } else {
            frontWave.snapTo(1f)
        }
    }

    // A saved value echo must not interrupt the release animation. A real external change does.
    LaunchedEffect(model, resolved, enabled) {
        if (selection != resolved || !enabled) {
            settleJob?.cancel()
            pending = false
            previousIndex = sliderIndex
            selection = resolved
            position = initialIndex.toFloat()
        }
    }
    DisposableEffect(model) { onDispose { settleJob?.cancel() } }

    fun preview(next: Int) {
        if (!enabled) return
        val clamped = next.coerceIn(levels.indices)
        val id = levels[clamped].id
        if (id != selection) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (id == levels.last().id) peakEntry++
            previousIndex = sliderIndex
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
        settle(levels.indexOfFirst { it.id == selection }.coerceAtLeast(0))
        if (selection != resolved) onEffortChange(selection)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // 1. `.dsh-claude-effort-head`: "推理等级" + directional swap value + trailing actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "推理等级",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal
                ),
                color = MutedInk
            )
            Spacer(Modifier.width(6.dp))
            val direction = if (sliderIndex >= previousIndex) 1 else -1
            AnimatedContent(
                targetState = label,
                transitionSpec = {
                    if (!motion || dragging) {
                        fadeIn(snap()) togetherWith fadeOut(snap())
                    } else {
                        (fadeIn(tween(180)) +
                            slideInVertically(tween(200)) { h -> (h / 3) * direction }) togetherWith
                            (fadeOut(tween(150)) +
                                slideOutVertically(tween(200)) { h -> -(h / 3) * direction })
                    }.using(SizeTransform(clip = false))
                },
                label = "dsh-effort-value-swap"
            ) { currentLabel ->
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = animatedValueColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.weight(1f))
            if (!automatic) {
                val autoInteraction = remember { MutableInteractionSource() }
                Text(
                    text = "默认",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = MutedInk.copy(alpha = 0.85f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            enabled = enabled,
                            interactionSource = autoInteraction,
                            indication = null
                        ) {
                            settleJob?.cancel()
                            previousIndex = sliderIndex
                            selection = ReasoningPolicy.DEFAULT
                            pending = false
                            position = (levels.lastIndex / 2).toFloat()
                            if (resolved != ReasoningPolicy.DEFAULT) onEffortChange(ReasoningPolicy.DEFAULT)
                        }
                        .appleSpringPressable(pressedScale = 0.93f, interactionSource = autoInteraction)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // 2. `.dsh-claude-effort-ends`: "更快" (Faster) <-> "更强" (Smarter)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "更快",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                ),
                color = MutedInk.copy(alpha = 0.72f)
            )
            Text(
                text = "更强",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                ),
                color = MutedInk.copy(alpha = 0.72f)
            )
        }

        // 3. `.dsh-claude-effort-track`: 26dp rounded track (8dp radius), 4dp ticks, 5-row apex matrix, and 16x30dp rectangular knob
        Slider(
            value = position,
            onValueChange = { raw ->
                settleJob?.cancel()
                val clamped = raw.coerceIn(range)
                // Subtle magnetic detents (`Y(J)` in dsh-claude-style: U = 0.8 sin attraction toward step centers)
                val span = levels.lastIndex.coerceAtLeast(1).toFloat()
                val normalized = (clamped / span).coerceIn(0f, 1f) * span
                val stepBase = floor(normalized)
                val frac = normalized - stepBase
                val magnetic = if (stepBase >= span) {
                    span
                } else {
                    (stepBase + frac - 0.55f * sin(2.0 * PI * frac).toFloat() / (2f * PI.toFloat())).coerceIn(range)
                }
                position = magnetic
                preview(clamped.roundToInt())
            },
            onValueChangeFinished = ::commit,
            enabled = enabled,
            interactionSource = interactionSource,
            steps = 0,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .testTag("reasoning-slider")
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionRight -> if (rtl) -1 else 1
                        Key.DirectionLeft -> if (rtl) 1 else -1
                        Key.DirectionUp -> 1
                        Key.DirectionDown -> -1
                        else -> 0
                    }
                    if (!enabled || direction == 0) {
                        false
                    } else {
                        if (event.type == KeyEventType.KeyDown) {
                            val next = (sliderIndex + direction).coerceIn(levels.indices)
                            preview(next)
                            settle(next)
                        } else if (event.type == KeyEventType.KeyUp) {
                            commit()
                        }
                        true
                    }
                }
                .semantics {
                    contentDescription = "思考强度滑块"
                    stateDescription = if (automatic) {
                        "自动，由模型决定"
                    } else {
                        "$label，第 ${sliderIndex + 1} 档，共 ${levels.size} 档"
                    }
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        sliderIndex.toFloat(),
                        range,
                        (levels.size - 2).coerceAtLeast(0)
                    )
                    setProgress { value ->
                        if (!enabled || !value.isFinite()) {
                            false
                        } else {
                            var next = value.roundToInt().coerceIn(levels.indices)
                            if (levels.size == 2 && next == sliderIndex && value != sliderIndex.toFloat()) {
                                next = (sliderIndex + if (value > sliderIndex) 1 else -1).coerceIn(levels.indices)
                            }
                            if (!automatic && next == sliderIndex) {
                                false
                            } else {
                                preview(next)
                                commit()
                                true
                            }
                        }
                    }
                },
            thumb = {
                // `.dsh-claude-effort-knob`: width 16dp, height 30dp, border-radius 5dp, scale 1.1 on drag, apex glow
                val knobShape = RoundedCornerShape(5.dp)
                val knobBg = if (automatic) {
                    Color.White.copy(alpha = if (dark) 0.72f else 0.88f)
                } else if (highest) {
                    lerp(Color.White, apexColor, 0.22f)
                } else {
                    Color.White
                }
                val knobBorder = when {
                    highest -> apexColor.copy(alpha = 0.48f)
                    dark -> Color.Black.copy(alpha = 0.42f)
                    else -> Color(0xFF141413).copy(alpha = 0.14f)
                }
                Box(
                    Modifier
                        .size(width = 16.dp, height = 30.dp)
                        .testTag("reasoning-thumb")
                        .graphicsLayer {
                            scaleX = knobScale
                            scaleY = knobScale
                        }
                        .shadow(
                            elevation = if (highest) 6.dp else 2.dp,
                            shape = knobShape,
                            ambientColor = if (highest) apexColor else Color.Black,
                            spotColor = if (highest) apexColor else Color.Black
                        )
                        .background(knobBg, knobShape)
                        .border(BorderStroke(1.dp, knobBorder), knobShape)
                )
            },
            track = {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                ) {
                    // Extend track by half knob width (8dp) on both sides so knob sits flush inside the 26dp rounded track
                    val halfKnob = 8.dp.toPx()
                    val trackLeft = -halfKnob
                    val trackRight = size.width + halfKnob
                    val trackWidth = trackRight - trackLeft
                    val corner = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    val trackPath = Path().apply {
                        addRoundRect(RoundRect(trackLeft, 0f, trackRight, size.height, corner))
                    }

                    clipPath(trackPath) {
                        // Track base (`--dsw-alias-interactive-bg-hover`)
                        drawRect(
                            color = trackBg,
                            topLeft = Offset(trackLeft, 0f),
                            size = Size(trackWidth, size.height)
                        )

                        val fraction = (position / levels.lastIndex.coerceAtLeast(1)).coerceIn(0f, 1f)
                        val knobCenterX = if (rtl) size.width * (1f - fraction) else size.width * fraction

                        // Normal fill & 4dp step ticks (fade out when apex is active)
                        val normalAlpha = (1f - apexProgress).coerceIn(0f, 1f)
                        if (normalAlpha > 0.01f) {
                            if (!automatic) {
                                val fillStart = if (rtl) knobCenterX else trackLeft
                                val fillEnd = if (rtl) trackRight else (knobCenterX + halfKnob).coerceAtMost(trackRight)
                                if (fillEnd > fillStart) {
                                    drawRect(
                                        color = trackFill.copy(alpha = trackFill.alpha * normalAlpha),
                                        topLeft = Offset(fillStart, 0f),
                                        size = Size(fillEnd - fillStart, size.height)
                                    )
                                }
                            }
                            val tickRadius = 2.dp.toPx()
                            for (lvl in levels.indices) {
                                val tickX = size.width * lvl / levels.lastIndex.coerceAtLeast(1)
                                drawCircle(
                                    color = tickColor.copy(alpha = tickColor.alpha * normalAlpha),
                                    radius = tickRadius,
                                    center = Offset(tickX, center.y)
                                )
                            }
                        }

                        // `.dsh-claude-effort-matrix` (5 rows of pixel squares when at top rung `data-apex`)
                        if (apexProgress > 0.01f) {
                            val rows = 5
                            val gapPx = max(1f, 1.dp.toPx())
                            val padV = 1.dp.toPx()
                            val sq = max(3f, floor((size.height - 2f * padV - (rows - 1) * gapPx) / rows))
                            val cols = max(1, floor((trackWidth + gapPx) / (sq + gapPx)).toInt())
                            val totalGridW = cols * sq + (cols - 1) * gapPx
                            val totalGridH = rows * sq + (rows - 1) * gapPx
                            val startX = trackLeft + (trackWidth - totalGridW) / 2f
                            val startY = (size.height - totalGridH) / 2f
                            val sqRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                            val flashPeriod = 1.45f
                            val flowPeriod = 4.0f
                            val frontT = frontWave.value

                            for (r in 0 until rows) {
                                for (c in 0 until cols) {
                                    val colNorm = if (cols > 1) c.toFloat() / (cols - 1).toFloat() else 1f
                                    val v = if (rtl) 1f - colNorm else colNorm
                                    val baseAlpha = dshMatrixBaseOpacity(v) * apexProgress
                                    if (baseAlpha <= 0.005f) continue

                                    val cellColor: Color
                                    if (!motion) {
                                        cellColor = apexColor
                                    } else {
                                        // 1. Base flash (`dshClaudeEffortFlash`: 1450ms period, peak at 20%..34%)
                                        val flashMix = 0.62f - dshMatrixHash(r, c, 1) * 0.22f
                                        val flashLight = lerp(apexFlashColor, apexColor, flashMix)
                                        val delaySec = dshMatrixHash(r, c, 2) * flashPeriod * 0.95f + flashPeriod * 0.21f
                                        val durSec = flashPeriod * (0.92f + dshMatrixHash(r, c, 3) * 0.16f)
                                        val phase = ((matrixClockSec + delaySec) % durSec) / durSec
                                        val flashFactor = when {
                                            phase < 0.14f -> 0f
                                            phase < 0.20f -> (phase - 0.14f) / 0.06f
                                            phase < 0.34f -> 1f
                                            phase < 0.65f -> 1f - (phase - 0.34f) / 0.31f
                                            else -> 0f
                                        }
                                        var color = lerp(apexColor, flashLight, flashFactor)

                                        // 2. Continuous horizontal particle flow (`dshClaudeEffortFlow`: 4000ms period, 0..6% window)
                                        val flowDelay = v * flowPeriod + r * flowPeriod * 0.03f + dshMatrixHash(r, c, 5) * flowPeriod * 0.05f
                                        val flowPhase = ((matrixClockSec + flowDelay) % flowPeriod) / flowPeriod
                                        if (flowPhase < 0.06f) {
                                            val tri = if (flowPhase < 0.03f) flowPhase / 0.03f else (0.06f - flowPhase) / 0.03f
                                            val strength = dshMatrixFlowStrength(r, c, v) * 0.95f * tri
                                            color = lerp(color, apexFlashColor, strength.coerceIn(0f, 1f))
                                        }

                                        // 3. Entry wavefront (`dshClaudeEffortFront` on entering apex)
                                        if (frontT < 1f) {
                                            val frontDelay = (1f - v) * 0.31f + dshMatrixHash(r, c, 4) * 0.055f
                                            val localFront = ((frontT - frontDelay * 0.6f) / 0.35f).coerceIn(0f, 1f)
                                            if (localFront > 0f && localFront < 1f) {
                                                val pulse = if (localFront < 0.3f) localFront / 0.3f else (1f - localFront) / 0.7f
                                                color = lerp(color, apexFlashColor, (pulse * 0.9f).coerceIn(0f, 1f))
                                            }
                                        }
                                        cellColor = color
                                    }

                                    val x = startX + c * (sq + gapPx)
                                    val y = startY + r * (sq + gapPx)
                                    drawRoundRect(
                                        color = cellColor.copy(alpha = baseAlpha.coerceIn(0f, 1f)),
                                        topLeft = Offset(x, y),
                                        size = Size(sq, sq),
                                        cornerRadius = sqRadius
                                    )
                                }
                            }
                        }
                    }

                    // Subtle border highlight around track when apex is active
                    if (apexProgress > 0.05f) {
                        drawRoundRect(
                            color = apexColor.copy(alpha = 0.28f * apexProgress),
                            topLeft = Offset(trackLeft, 0f),
                            size = Size(trackWidth, size.height),
                            cornerRadius = corner,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            }
        )

        // 4. Compact footer row: current model shortcut (matching dsh-claude-style's popover footer divider & row)
        HorizontalDivider(
            modifier = Modifier.padding(top = 2.dp),
            thickness = 0.5.dp,
            color = Hairline.copy(alpha = 0.7f)
        )
        val modelInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .clickable(
                    interactionSource = modelInteraction,
                    indication = null,
                    onClick = onModelClick
                )
                .appleSpringPressable(pressedScale = 0.96f, interactionSource = modelInteraction)
                .padding(horizontal = 4.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsterInlineBrandGlyph(
                modelId = model,
                size = 13.dp,
                modifier = Modifier.padding(end = 5.dp)
            )
            Text(
                text = model.substringAfterLast('/'),
                color = MutedInk,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "选择模型",
                modifier = Modifier.size(15.dp),
                tint = MutedInk.copy(alpha = 0.72f)
            )
        }
    }
}
