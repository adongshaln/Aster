package com.adong.adchat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.delay

/** A compact, animated walkthrough of the Star Island clipboard flow. */
@Composable
fun StarIslandUsageDemo(modifier: Modifier = Modifier) {
    val motionEnabled = LocalAsterMotionEnabled.current
    var step by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(motionEnabled) {
        if (motionEnabled) {
            while (true) {
                delay(3000)
                step = (step + 1) % 3
            }
        }
    }

    val stageScale by animateFloatAsState(
        targetValue = when (step) { 1 -> 1.12f; 2 -> 0.94f; else -> 1f },
        animationSpec = if (motionEnabled) AsterSpring.jelly() else snap(),
        label = "island-demo-ring-scale"
    )
    val sampleOffset by animateFloatAsState(
        targetValue = if (step == 1) 22f else 0f,
        animationSpec = if (motionEnabled) AsterSpring.releaseHorizontal() else snap(),
        label = "island-demo-copy-offset"
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("星岛怎么用", style = androidx.compose.material3.MaterialTheme.typography.titleSmall, color = Ink)
                Text("复制内容，点一下，随手处理", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = MutedInk)
            }
            Text("${step + 1} / 3", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = MutedInk)
        }

        Surface(
            color = SurfaceInset,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Hairline.copy(alpha = 0.8f)),
            modifier = Modifier.fillMaxWidth().height(190.dp)
                .semantics { contentDescription = "星岛使用演示，第 ${step + 1} 步。点按下一步可切换演示。" }
        ) {
            Box(Modifier.fillMaxWidth()) {
                // The persistent top capsule mirrors the real floating island. Its hand-drawn
                // accent ring swells when the walkthrough reaches the tap step.
                Surface(
                    color = Night,
                    shape = CircleShape,
                    shadowElevation = 5.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier.size(31.dp).scale(if (motionEnabled) stageScale else 1f)
                                .clip(CircleShape)
                                .background(Accent.copy(alpha = 0.14f))
                                .border(1.6.dp, Accent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(21.dp).clip(CircleShape).background(Color(0xFF050505)))
                        }
                        Text("星岛", color = Color(0xFFF7F6F0), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }

                // Clipboard sample glides toward the island during step two.
                Surface(
                    color = Surface,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Hairline.copy(alpha = 0.9f)),
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                        .offset(x = sampleOffset.dp, y = if (step == 1) (-40).dp else 0.dp)
                        .scale(if (step == 2 && motionEnabled) 0.88f else 1f)
                        .semantics(mergeDescendants = true) { contentDescription = "已复制的文字示例" }
                ) {
                    Row(
                        Modifier.width(222.dp).padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(17.dp), tint = Accent)
                        Text(
                            if (step == 2) "这段文字怎么翻译？" else "复制一段文字或链接",
                            Modifier.weight(1f), color = Ink, style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (step < 2) Icon(Icons.Rounded.Link, null, Modifier.size(16.dp), tint = MutedInk.copy(alpha = 0.78f))
                    }
                }

                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp)) {
                    AnimatedVisibility(
                        visible = step == 2,
                        enter = if (motionEnabled) fadeIn(AsterSpring.editorial()) + expandVertically(AsterSpring.spatial()) +
                            slideInVertically(AsterSpring.spatial()) { it / 4 } else EnterTransition.None,
                        exit = if (motionEnabled) fadeOut(AsterSpring.editorial()) + shrinkVertically(AsterSpring.snappy()) +
                            slideOutVertically(AsterSpring.snappy()) { it / 5 } else ExitTransition.None
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DemoAction(Icons.Rounded.Translate, "翻译", primary = true)
                            DemoAction(Icons.Rounded.AutoAwesome, "润色")
                            DemoAction(Icons.Rounded.MoreHoriz, "解释")
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
                0 -> "① 复制要处理的内容"
                1 -> "② 点按摄像头旁的墨线圈"
                else -> "③ 选择操作，结果留在星岛"
            }
            val detail = when (current) {
                0 -> "文字和链接都可以；星岛不会主动读取剪贴板。"
                1 -> "点按后才读取刚复制的内容，并识别内容类型。"
                else -> "文字可翻译、润色、解释；网页可阅读，媒体可解析下载。"
            }
            Column(Modifier.fillMaxWidth().height(58.dp)) {
                Text(title, color = Ink, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(3.dp))
                Text(detail, color = MutedInk, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { index ->
                    val width by animateDpAsState(
                        targetValue = if (index == step) 20.dp else 6.dp,
                        animationSpec = if (motionEnabled) AsterSpring.jelly() else snap(),
                        label = "island-demo-dot-$index"
                    )
                    Box(Modifier.size(width, 6.dp).clip(CircleShape).background(if (index == step) Accent else Hairline))
                }
                Text("链接也适用", color = MutedInk, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = { step = (step + 1) % 3 },
                colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                modifier = Modifier.semantics { contentDescription = "切换星岛演示步骤" }
            ) { Text("下一步") }
        }
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
        border = if (primary) null else BorderStroke(1.dp, Hairline.copy(alpha = 0.75f)),
        shadowElevation = if (primary) 2.dp else 0.dp
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(14.dp), tint = if (primary) OnAccent else MutedInk)
            Text(label, color = if (primary) OnAccent else Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
