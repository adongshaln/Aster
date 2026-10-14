package com.adong.adchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.data.ContextWindowPresets
import com.adong.adchat.data.ModelContextLimits
import com.adong.adchat.ui.theme.*

/**
 * Aster v2.0 — Apple Control-Center Style Context Ring & Reasoning Capsule
 * ========================================================================
 * 1. `ComposerContextRing` 升级为带 `AsterSpring.spatial()` 阻尼圆弧动画与弹性按压的精致进度环；
 * 2. `ComposerReasoningButton` 从原本拥挤的上下堆叠小字改造为 **Claude 陶土胶囊开关**，开启深度思考时呈现醒目的暖陶土底色与边框；
 * 3. `ComposerContextSheet` 预设档位卡片全部接入 `appleSpringPressable` 弹性缩放手感。
 */
@Composable
fun ComposerContextRing(
    percent: Int,
    configured: Boolean,
    overflow: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val targetTint = when {
        !configured -> MutedInk
        overflow -> Danger
        percent >= 85 -> Accent
        else -> Sage
    }
    val tint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = AsterSpring.editorial(),
        label = "ContextRingTint"
    )
    val settledPercent by produceState(initialValue = percent, percent) {
        kotlinx.coroutines.delay(220)
        value = percent
    }
    val progress by animateFloatAsState(
        targetValue = if (configured) (settledPercent / 100f).coerceIn(0f, 1f) else 0f,
        animationSpec = AsterSpring.spatial(),
        label = "context-ring"
    )
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .appleSpringPressable(pressedScale = 0.88f)
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(30.dp),
                color = tint,
                trackColor = SurfaceInset,
                strokeWidth = 2.8.dp
            )
            Text(
                if (configured) "$percent%" else "--",
                color = tint,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 思考强度按钮：重构为横向舒展的 Claude 陶土胶囊（开启深度思考时高亮为 AccentSoft 胶囊）
 */
@Composable
fun ComposerReasoningButton(
    label: String,
    detailed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg by animateColorAsState(
        targetValue = if (detailed) AccentSoft else SurfaceInset.copy(alpha = 0.65f),
        animationSpec = AsterSpring.editorial(),
        label = "ReasoningChipBg"
    )
    val tint by animateColorAsState(
        targetValue = if (detailed) Accent else MutedInk,
        animationSpec = AsterSpring.editorial(),
        label = "ReasoningChipTint"
    )
    Surface(
        onClick = onClick,
        color = bg,
        shape = CircleShape,
        border = BorderStroke(
            width = 1.dp,
            color = if (detailed) Accent.copy(alpha = 0.35f) else Hairline.copy(alpha = 0.6f)
        ),
        modifier = modifier
            .height(34.dp)
            .appleSpringPressable(pressedScale = 0.92f)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Rounded.Psychology, null, Modifier.size(16.dp), tint = tint)
            Text(
                label,
                color = tint,
                fontSize = 11.5.sp,
                fontWeight = if (detailed) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
fun ComposerContextSheet(
    model: String,
    limits: ModelContextLimits?,
    totalTokens: Int,
    systemTokens: Int,
    historyTokens: Int,
    attachmentTokens: Int,
    overheadTokens: Int,
    omittedTurns: Int,
    lastRequestInputTokens: Int,
    lastRequestOutputTokens: Int,
    lastRequestReasoningTokens: Int,
    onSelectWindow: (Int) -> Unit,
    onCustomize: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val budget = limits?.inputTokens ?: 0
    val rawProgress = if (budget > 0) (totalTokens.toFloat() / budget).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = AsterSpring.spatial(),
        label = "SheetContextBar"
    )
    val overflow = budget > 0 && totalTokens > budget
    AsterOptionsSheet(
        title = "上下文预算",
        subtitle = model.ifBlank { "未选择模型" },
        onDismiss = onDismiss,
        headerIcon = Icons.Rounded.Tune
    ) {
        Surface(
            color = Surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Hairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (budget > 0) "$totalTokens / $budget" else "$totalTokens",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        if (budget > 0) "${(progress * 100).toInt()}%" else "--",
                        color = if (overflow) Danger else Accent,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (overflow) Danger else Accent,
                    trackColor = SurfaceInset
                )
                Text(
                    if (limits == null) "未设上下文长度" else
                        "窗口 ${ContextWindowPresets.label(limits.windowTokens)} · 输出上限 ${limits.outputTokens}",
                    color = MutedInk,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ContextBreakLine("系统提示词", systemTokens)
            ContextBreakLine("对话历史", historyTokens)
            ContextBreakLine("图片附件", attachmentTokens)
            ContextBreakLine("请求开销", overheadTokens)
            if (omittedTurns > 0) {
                Text(
                    "已省略最早 $omittedTurns 轮",
                    color = Danger,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (lastRequestInputTokens > 0 || lastRequestOutputTokens > 0) {
            Text(
                "上次请求 输入 $lastRequestInputTokens · 输出 $lastRequestOutputTokens" +
                    if (lastRequestReasoningTokens > 0) " · 含推理 $lastRequestReasoningTokens" else "",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        ModelContextPresetsInline(
            limits = limits,
            onSelect = onSelectWindow
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCustomize,
                enabled = model.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .appleSpringPressable(pressedScale = 0.96f, enabled = model.isNotBlank())
            ) {
                Text("自定义数值")
            }
            OutlinedButton(
                onClick = onReset,
                enabled = limits != null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .appleSpringPressable(pressedScale = 0.96f, enabled = limits != null)
            ) {
                Text("恢复默认")
            }
        }
    }
}

@Composable
private fun ModelContextPresetsInline(limits: ModelContextLimits?, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        ContextWindowPresets.values.forEach { (window, label) ->
            val selected = limits?.windowTokens == window
            Surface(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(window)
                },
                color = if (selected) AccentSoft else Surface,
                contentColor = if (selected) Accent else Ink,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (selected) Accent.copy(alpha = 0.45f) else Hairline
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .appleSpringPressable(pressedScale = 0.97f)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (selected) {
                        Surface(color = Accent, shape = CircleShape) {
                            Text(
                                "当前",
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextBreakLine(label: String, tokens: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MutedInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            "$tokens",
            color = if (tokens > 0) Ink else MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (tokens > 0) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
