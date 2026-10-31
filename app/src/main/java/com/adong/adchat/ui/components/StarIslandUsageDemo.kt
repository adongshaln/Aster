package com.adong.adchat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Interactive 3-act walkthrough of the authentic Star Island experience:
 * Act 0: Resident clay ink ring around the camera cutout.
 * Act 1: Tap to unfold the paper tag from the ring for clipboard translation/reading.
 * Act 2: Long-press to expand the screen selection frame with 4 paper-core clay eyelets & instant Markdown Q&A.
 */
@Composable
fun StarIslandUsageDemo(modifier: Modifier = Modifier) {
    val motionEnabled = LocalAsterMotionEnabled.current
    var step by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(motionEnabled) {
        if (motionEnabled) {
            while (true) {
                delay(3200)
                step = (step + 1) % 3
            }
        }
    }

    val ringScale by animateFloatAsState(
        targetValue = when (step) {
            0 -> 1f
            1 -> 1.06f
            else -> 0.96f
        },
        animationSpec = if (motionEnabled) AsterSpring.jelly() else snap(),
        label = "island-demo-ring-scale"
    )
    val cardWidth by animateDpAsState(
        targetValue = when (step) {
            0 -> 34.dp
            1 -> 244.dp
            else -> 256.dp
        },
        animationSpec = if (motionEnabled) AsterSpring.releaseHorizontal() else snap(),
        label = "island-demo-card-width"
    )
    val cardHeight by animateDpAsState(
        targetValue = when (step) {
            0 -> 34.dp
            1 -> 112.dp
            else -> 132.dp
        },
        animationSpec = if (motionEnabled) AsterSpring.releaseVertical() else snap(),
        label = "island-demo-card-height"
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (step == 0) 0f else 1f,
        animationSpec = if (motionEnabled) AsterSpring.editorial() else snap(),
        label = "island-demo-card-alpha"
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("星岛怎么用", style = MaterialTheme.typography.titleSmall, color = Ink)
                Text("点按墨线圈处理剪贴板 · 长按框选屏幕极速提问", style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
            Surface(
                color = AccentSoft,
                shape = CircleShape,
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.35f))
            ) {
                Text(
                    "${step + 1} / 3",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Surface(
            color = SurfaceInset,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Hairline),
            modifier = Modifier
                .fillMaxWidth()
                .height(178.dp)
                .semantics { contentDescription = "星岛交互动画演示" }
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                val clayColor = Accent
                val paperColor = Surface
                val edgeColor = Hairline

                // Evolving paper card unfolded directly from the camera ink ring
                if (cardAlpha > 0.01f) {
                    Surface(
                        color = paperColor,
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.3.dp, clayColor.copy(alpha = 0.72f)),
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp)
                            .size(cardWidth.coerceAtLeast(0.dp), cardHeight.coerceAtLeast(0.dp))
                            .graphicsLayer { alpha = cardAlpha.coerceIn(0f, 1f) }
                    ) {
                        Box(Modifier.fillMaxSize().padding(top = 28.dp, start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                            if (step == 1) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(14.dp), tint = Accent)
                                        Text(
                                            "剪贴板文字 · 极速",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Ink,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        DemoPill("收起")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        DemoAction(Icons.Rounded.Translate, "翻译", primary = true)
                                        DemoAction(Icons.Rounded.AutoAwesome, "润色")
                                        DemoAction(Icons.Rounded.CropFree, "解释")
                                    }
                                }
                            } else if (step == 2) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            "问问这里",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Ink,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        DemoPill("极速模式", tinted = true)
                                        Spacer(Modifier.weight(1f))
                                        DemoPill("收起")
                                        DemoPill("结束")
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Mini crop preview with 4 clay eyelet corners
                                        Canvas(Modifier.size(42.dp, 32.dp)) {
                                            val pad = 4.dp.toPx()
                                            val rectSize = Size(size.width - pad * 2, size.height - pad * 2)
                                            drawRoundRect(
                                                color = edgeColor,
                                                topLeft = Offset(pad, pad),
                                                size = rectSize,
                                                cornerRadius = CornerRadius(5.dp.toPx())
                                            )
                                            drawRoundRect(
                                                color = clayColor,
                                                topLeft = Offset(pad, pad),
                                                size = rectSize,
                                                cornerRadius = CornerRadius(5.dp.toPx()),
                                                style = Stroke(width = 1.5.dp.toPx())
                                            )
                                            val corners = listOf(
                                                Offset(pad, pad),
                                                Offset(size.width - pad, pad),
                                                Offset(pad, size.height - pad),
                                                Offset(size.width - pad, size.height - pad)
                                            )
                                            corners.forEach { pt ->
                                                drawCircle(clayColor, radius = 3.2.dp.toPx(), center = pt)
                                                drawCircle(paperColor, radius = 1.5.dp.toPx(), center = pt)
                                            }
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                "**核心结论**：已提取选区要点",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Ink,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "• 支持 Markdown 排版与追问",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MutedInk,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Resident camera black core + hand-drawn clay ink ring anchored at top center
                Canvas(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(36.dp)
                        .graphicsLayer {
                            scaleX = ringScale
                            scaleY = ringScale
                        }
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    if (step == 0) {
                        drawArc(
                            color = clayColor,
                            startAngle = -58f,
                            sweepAngle = 352f,
                            useCenter = false,
                            topLeft = Offset(center.x - 13.5.dp.toPx(), center.y - 13.5.dp.toPx()),
                            size = Size(27.dp.toPx(), 27.dp.toPx()),
                            style = Stroke(width = 2.1.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    drawCircle(
                        color = Color(0xFF0A0A0A),
                        radius = 9.5.dp.toPx(),
                        center = center
                    )
                }

                // Idle hint chip in step 0
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)) {
                    AnimatedVisibility(
                        visible = step == 0,
                        enter = if (motionEnabled) fadeIn(AsterSpring.editorial()) + expandVertically(AsterSpring.spatial()) else EnterTransition.None,
                        exit = if (motionEnabled) fadeOut(AsterSpring.snappy()) + shrinkVertically(AsterSpring.snappy()) else ExitTransition.None
                    ) {
                        Surface(
                            color = Surface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Hairline),
                            shadowElevation = 2.dp
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = Accent)
                                Text(
                                    "墨线圈常驻前摄旁 · 点按或长按唤醒",
                                    color = Ink,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (motionEnabled) {
                    (fadeIn(AsterSpring.editorial()) + slideInVertically(AsterSpring.spatial()) { it / 4 }) togetherWith
                        (fadeOut(AsterSpring.editorial()) + slideOutVertically(AsterSpring.snappy()) { -it / 5 })
                } else {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                }
            },
            label = "island-demo-caption"
        ) { current ->
            val title = when (current) {
                0 -> "① 常驻墨线圈 · 零后台打扰"
                1 -> "② 点按墨线圈 · 纸签原位抽出"
                else -> "③ 长按墨线圈 · 贴边框选极速提问"
            }
            val detail = when (current) {
                0 -> "一笔陶土墨线环绕前摄黑芯；不主动轮询剪贴板，横屏与沉浸全屏自动隐退。"
                1 -> "墨线圈直接延展为纸签边框，支持文字翻译、润色、解释、网页阅读与无水印视频解析。"
                else -> "全屏贴边展开框选层，确认后吐出紧凑提问卡，以最低思考档位极速输出 Markdown 回答。"
            }
            Column(Modifier.fillMaxWidth().height(58.dp)) {
                Text(title, color = Ink, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(3.dp))
                Text(detail, color = MutedInk, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val width by animateDpAsState(
                        targetValue = if (index == step) 20.dp else 6.dp,
                        animationSpec = if (motionEnabled) AsterSpring.jelly() else snap(),
                        label = "island-demo-dot-$index"
                    )
                    Box(
                        Modifier
                            .size(width.coerceAtLeast(0.dp), 6.dp)
                            .clip(CircleShape)
                            .background(if (index == step) Accent else Hairline)
                    )
                }
                Text("一切皆由线圈展开", color = MutedInk, style = MaterialTheme.typography.labelSmall)
            }
            AsterPillButton(
                text = "下一步",
                compact = true,
                onClick = { step = (step + 1) % 3 },
                modifier = Modifier.semantics { contentDescription = "切换星岛演示步骤" }
            )
        }
    }
}

@Composable
private fun DemoPill(label: String, tinted: Boolean = false) {
    Surface(
        color = if (tinted) AccentSoft else SurfaceInset,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (tinted) Accent.copy(alpha = 0.35f) else Hairline)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = if (tinted) Accent else Ink,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DemoAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean = false
) {
    Surface(
        color = if (primary) Accent else Surface,
        shape = RoundedCornerShape(14.dp),
        border = if (primary) null else BorderStroke(1.dp, Hairline.copy(alpha = 0.85f)),
        shadowElevation = if (primary) 2.dp else 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, Modifier.size(13.dp), tint = if (primary) OnAccent else MutedInk)
            Text(label, color = if (primary) OnAccent else Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
