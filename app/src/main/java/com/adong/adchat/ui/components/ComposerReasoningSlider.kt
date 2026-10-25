package com.adong.adchat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.ReasoningPolicy
import com.adong.adchat.ui.theme.Accent
import com.adong.adchat.ui.theme.AccentSoft
import com.adong.adchat.ui.theme.Hairline
import com.adong.adchat.ui.theme.Ink
import com.adong.adchat.ui.theme.LocalAsterMotionEnabled
import com.adong.adchat.ui.theme.MutedInk
import com.adong.adchat.ui.theme.OnAccent
import com.adong.adchat.ui.theme.OnAccentSoft
import com.adong.adchat.ui.theme.QuoteAmber
import com.adong.adchat.ui.theme.SurfaceInset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** A local preview while dragging; one preference update when the native slider finishes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerReasoningSlider(
    model: String,
    effort: String,
    onEffortChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val choices = remember(model) { ReasoningPolicy.choices(model) }
    val levels = remember(choices) { choices.filterNot { it.id == ReasoningPolicy.DEFAULT } }
    if (levels.isEmpty()) return
    val resolved = ReasoningPolicy.choiceOf(model, effort)?.id ?: ReasoningPolicy.DEFAULT
    var selection by remember(model, effort, enabled) { mutableStateOf(resolved) }
    var pending by remember(model, effort, enabled) { mutableStateOf(false) }
    var entry by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val motionEnabled = LocalAsterMotionEnabled.current
    val automatic = selection == ReasoningPolicy.DEFAULT
    val index = levels.indexOfFirst { it.id == selection }.takeIf { it >= 0 }
    // Automatic has no represented strength: the track is neutral and its thumb is hidden.
    // A central keyboard starting position does not imply the model's own default effort.
    val sliderIndex = index ?: (levels.lastIndex / 2)
    val highest = !automatic && selection == levels.last().id
    val label = if (automatic) "由模型决定" else levels[index ?: 0].label
    val ink = if (enabled) Ink else Ink.copy(alpha = .38f)
    val muted = if (enabled) MutedInk else Ink.copy(alpha = .38f)
    val accent = Accent
    val onAccent = OnAccent
    val softInk = OnAccentSoft
    val amber = QuoteAmber
    val inactive = SurfaceInset
    val tick = if (enabled) MutedInk.copy(alpha = .65f) else Ink.copy(alpha = .22f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    fun preview(next: Int) {
        if (!enabled) return
        val id = levels[next.coerceIn(levels.indices)].id
        if (id != selection) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            if (id == levels.last().id && motionEnabled) entry++
            selection = id
        }
        pending = true
    }

    fun commit() {
        if (!enabled || !pending) return
        pending = false
        if (selection != resolved) onEffortChange(selection)
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Psychology, null, Modifier.size(16.dp), tint = muted)
            Text("思考强度", color = muted, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f))
            Text(if (highest) "$label · 最高档" else label,
                color = if (highest && enabled) softInk else ink,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (highest) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choices.any { it.id == ReasoningPolicy.DEFAULT }) {
                Surface(
                    selected = automatic,
                    onClick = {
                        if (!automatic) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selection = ReasoningPolicy.DEFAULT
                        pending = false
                        if (resolved != ReasoningPolicy.DEFAULT) onEffortChange(ReasoningPolicy.DEFAULT)
                    },
                    enabled = enabled,
                    color = if (automatic) AccentSoft else Color.Transparent,
                    contentColor = if (!enabled) muted else if (automatic) softInk else MutedInk,
                    border = BorderStroke(1.dp, if (automatic && enabled) Accent.copy(alpha = .4f) else Hairline),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(54.dp).height(36.dp)
                        .semantics { contentDescription = "自动思考强度，使用模型默认设置" }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("自动", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (levels.size == 1) {
                Surface(onClick = { preview(0); commit() }, enabled = enabled,
                    color = if (automatic) inactive else AccentSoft,
                    contentColor = ink, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(levels.single().label) }
                }
            } else {
                Box(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp))) {
                    ReasoningPeakGlow(highest && enabled, entry, motionEnabled, rtl, accent, amber)
                    Slider(
                        value = sliderIndex.toFloat(),
                        onValueChange = { preview(it.roundToInt()) },
                        onValueChangeFinished = ::commit,
                        enabled = enabled,
                        steps = (levels.size - 2).coerceAtLeast(0),
                        valueRange = 0f..levels.lastIndex.toFloat(),
                        modifier = Modifier.fillMaxWidth().align(Alignment.Center)
                            .onPreviewKeyEvent { event ->
                                // Material treats a two-point range (steps=0) as continuous;
                                // keyboard increments must still move by a whole supported level.
                                val direction = when (event.key) {
                                    Key.DirectionRight -> if (rtl) -1 else 1
                                    Key.DirectionLeft -> if (rtl) 1 else -1
                                    Key.DirectionUp -> 1
                                    Key.DirectionDown -> -1
                                    else -> 0
                                }
                                if (!enabled || direction == 0) false else {
                                    if (event.type == KeyEventType.KeyDown) {
                                        val current = levels.indexOfFirst { it.id == selection }.takeIf { it >= 0 } ?: sliderIndex
                                        preview((current + direction).coerceIn(levels.indices))
                                    } else if (event.type == KeyEventType.KeyUp) commit()
                                    true
                                }
                            }.semantics {
                                contentDescription = "思考强度"
                                stateDescription = if (automatic) "自动，由模型决定；调整可手动选择档位" else
                                    "$label，第 ${sliderIndex + 1} 档，共 ${levels.size} 档"
                                setProgress { target ->
                                    if (!enabled || !target.isFinite()) false else {
                                        val rounded = target.roundToInt().coerceIn(levels.indices)
                                        val next = if (levels.size == 2 && rounded == sliderIndex && target != sliderIndex.toFloat()) {
                                            (sliderIndex + if (target > sliderIndex) 1 else -1).coerceIn(levels.indices)
                                        } else rounded
                                        if (!automatic && levels[next].id == selection) false else {
                                            preview(next)
                                            commit()
                                            true
                                        }
                                    }
                                }
                            },
                        thumb = {
                            Box(Modifier.size(20.dp).clip(CircleShape)
                                .background(when {
                                    automatic -> Color.Transparent
                                    !enabled -> Ink.copy(alpha = .38f)
                                    else -> accent
                                }), contentAlignment = Alignment.Center) {
                                if (!automatic) Canvas(Modifier.size(6.dp)) {
                                    drawCircle(if (enabled) onAccent else inactive)
                                }
                            }
                        },
                        track = {
                            Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                                val start = Offset(if (rtl) size.width else 0f, center.y)
                                val end = Offset(if (rtl) 0f else size.width, center.y)
                                drawLine(inactive, start, end, 4.dp.toPx(), StrokeCap.Round)
                                val fraction = sliderIndex.toFloat() / levels.lastIndex
                                val selected = Offset(start.x + (end.x - start.x) * fraction, center.y)
                                if (!automatic) drawLine(if (enabled) accent else tick, start, selected, 4.dp.toPx(), StrokeCap.Round)
                                for (level in levels.indices) {
                                    val x = start.x + (end.x - start.x) * level / levels.lastIndex
                                    drawCircle(if (!automatic && level <= sliderIndex && enabled) onAccent.copy(alpha = .65f) else tick,
                                        1.5.dp.toPx(), Offset(x, center.y))
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** One bounded burst on an explicit entry, then a still halo. No idle frame loop. */
@Composable
private fun ReasoningPeakGlow(
    active: Boolean, entry: Int, motionEnabled: Boolean, rtl: Boolean, clay: Color, gold: Color
) {
    val phase = remember { Animatable(1f) }
    var playedEntry by remember { mutableIntStateOf(0) }
    LaunchedEffect(active, entry, motionEnabled) {
        if (active && entry > playedEntry && motionEnabled) {
            playedEntry = entry
            phase.snapTo(0f)
            phase.animateTo(1f, tween(920, easing = LinearOutSlowInEasing))
        } else {
            playedEntry = entry
            phase.snapTo(1f)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (!active) return@Canvas
        val endX = if (rtl) 10.dp.toPx() else size.width - 10.dp.toPx()
        val hub = Offset(endX, center.y)
        val glowRadius = 34.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = .14f), clay.copy(alpha = .05f), Color.Transparent),
            center = hub, radius = glowRadius), glowRadius, hub)
        val t = phase.value
        if (!motionEnabled || t >= 1f) return@Canvas
        val fade = (1f - t) * (1f - t)
        clipRect {
            // A short glint crosses only the track's immediate neighbourhood.
            val sweep = if (rtl) size.width * (1f - t) else size.width * t
            val band = 30.dp.toPx()
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, gold.copy(alpha = .55f * fade), Color.Transparent),
                startX = sweep - band, endX = sweep + band),
                topLeft = Offset(0f, center.y - 3.dp.toPx()), size = Size(size.width, 6.dp.toPx()))
            for (ray in 0 until 7) {
                val angle = (ray * 2.0 * PI / 7.0 - PI / 2.0).toFloat()
                val distance = (5f + 19f * t) * density
                val point = hub + Offset(cos(angle) * distance, sin(angle) * distance * .75f)
                star(point, (2.5f + 2f * (1f - t)) * density, angle + t,
                    (if (ray % 2 == 0) gold else clay).copy(alpha = .8f * fade))
            }
            star(hub, (5f + 6f * sin(PI.toFloat() * t)) * density, t * .35f, gold.copy(alpha = .85f * (1f - t)))
        }
    }
}

private fun DrawScope.star(center: Offset, radius: Float, rotation: Float, color: Color) {
    val path = Path()
    for (point in 0 until 8) {
        val angle = rotation + point * PI.toFloat() / 4f
        val r = if (point % 2 == 0) radius else radius * .22f
        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r
        if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
