package com.adong.adchat.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
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

/** The toolbar keeps only this small entry; the slider lives in an independent window above it. */
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
    val position = remember(margin, gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.center.x - popupContentSize.width / 2)
                    .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                val above = anchorBounds.top - popupContentSize.height - gap
                val y = if (above >= margin) above else
                    (anchorBounds.bottom + gap).coerceAtMost(windowSize.height - popupContentSize.height - margin)
                return IntOffset(x, y.coerceAtLeast(margin))
            }
        }
    }
    BackHandler(expanded) { onExpandedChange(false) }
    Box(modifier) {
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
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true)) {
                Surface(
                    shape = RoundedCornerShape(25.dp), color = Surface, contentColor = Ink,
                    border = BorderStroke(1.dp, Hairline), shadowElevation = 12.dp,
                    modifier = Modifier.width(minOf(340.dp, (LocalConfiguration.current.screenWidthDp - 24).dp))
                        .graphicsLayer {
                            alpha = entrance.value.coerceIn(0f, 1f)
                            scaleX = .94f + .06f * entrance.value
                            scaleY = .89f + .11f * entrance.value
                            transformOrigin = TransformOrigin(.5f, 1f)
                        }
                        .testTag("reasoning-popover").semantics { paneTitle = "思考强度" }
                ) {
                    ComposerReasoningSlider(model, effort, onEffortChange,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                        onModelClick = { onExpandedChange(false); onModelClick() })
                }
            }
        }
    }
}

/** A native discrete slider with a broad capsule track and a separate model-default option. */
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
    var selection by remember(model, effort, enabled) { mutableStateOf(resolved) }
    var pending by remember(model, effort, enabled) { mutableStateOf(false) }
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
    fun commit() {
        if (!enabled || !pending) return
        pending = false
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
            value = sliderIndex.toFloat(), onValueChange = { preview(it.roundToInt()) },
            onValueChangeFinished = ::commit, enabled = enabled,
            steps = (levels.size - 2).coerceAtLeast(0), valueRange = 0f..levels.lastIndex.coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth().height(54.dp)
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionRight -> if (rtl) -1 else 1
                        Key.DirectionLeft -> if (rtl) 1 else -1
                        Key.DirectionUp -> 1
                        Key.DirectionDown -> -1
                        else -> 0
                    }
                    if (!enabled || direction == 0) false else {
                        if (event.type == KeyEventType.KeyDown) preview((sliderIndex + direction).coerceIn(levels.indices))
                        else if (event.type == KeyEventType.KeyUp) commit()
                        true
                    }
                }.semantics {
                    contentDescription = "思考强度滑块"
                    stateDescription = if (automatic) "自动，由模型决定" else "$label，第 ${sliderIndex + 1} 档，共 ${levels.size} 档"
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
                Box(Modifier.size(36.dp).shadow(if (automatic) 0.dp else 2.dp, CircleShape)
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
                        val fraction = sliderIndex.toFloat() / levels.lastIndex.coerceAtLeast(1)
                        val selectedX = if (rtl) size.width * (1f - fraction) else size.width * fraction
                        if (!automatic) {
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
                                val activeLeft = if (rtl) selectedX else left
                                val activeWidth = if (rtl) size.width + radius - selectedX else selectedX - left
                                drawRect(clay, Offset(activeLeft, 0f), Size(activeWidth, size.height))
                            }
                        }
                        if (!highest) for (level in levels.indices) {
                            val x = size.width * level / levels.lastIndex.coerceAtLeast(1)
                            val logicalLevel = if (rtl) levels.lastIndex - level else level
                            drawCircle(if (!automatic && logicalLevel <= sliderIndex) Color.White.copy(alpha = .42f) else ticks,
                                2.5.dp.toPx(), Offset(x, center.y))
                        }
                    }
                }
            }
        )
        TextButton(onClick = {
            selection = ReasoningPolicy.DEFAULT
            pending = false
            if (resolved != ReasoningPolicy.DEFAULT) onEffortChange(ReasoningPolicy.DEFAULT)
        }, enabled = enabled && !automatic, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 36.dp)) {
            Text(if (automatic) "由模型决定思考强度" else "使用模型默认", style = MaterialTheme.typography.labelSmall, color = MutedInk)
        }
    }
}
