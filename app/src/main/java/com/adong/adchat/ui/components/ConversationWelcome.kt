package com.adong.adchat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.ui.theme.*

data class ConversationStarter(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val prompt: String
)

/**
 * Aster v1.0 — Claude Editorial Salon 欢迎页 × Apple 错峰弹簧卡片流
 * =================================================================
 * 1. 顶部 `AsterMark` 搭载 Apple 欠阻尼弹簧入场 (`AsterSpring.gentleBounce`) 与呼吸悬浮 (`claudeBreathingFloat`)；
 * 2. 顶部标签采用 Claude 胶囊微章设计，主标题使用 `FontFamily.Serif` 文学衬线体；
 * 3. 预设卡片 (`ConversationStarter`) 搭载 `appleStaggeredEntrance` 错峰弹簧瀑布流入场与 `appleSpringPressable` 触感回弹。
 */
@Composable
fun ConversationWelcome(
    label: String,
    title: String,
    subtitle: String,
    starters: List<ConversationStarter>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(14.dp))

        AnimatedVisibility(
            visible = revealed,
            enter = fadeIn(animationSpec = AsterSpring.editorial()) +
                    scaleIn(initialScale = 0.78f, animationSpec = AsterSpring.gentleBounce()) +
                    slideInVertically(
                        initialOffsetY = { it / 2 },
                        animationSpec = AsterSpring.spatial()
                    )
        ) {
            Surface(
                color = AccentSoft.copy(alpha = 0.85f),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.18f)),
                shadowElevation = 2.dp,
                modifier = Modifier.claudeBreathingFloat(amplitudeDp = 3.dp, durationMillis = 2800)
            ) {
                AsterMark(Modifier.padding(14.dp).size(42.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Claude 风格顶部微胶囊标签
        Surface(
            color = SurfaceInset.copy(alpha = 0.85f),
            shape = CircleShape,
            border = BorderStroke(0.8.dp, Hairline)
        ) {
            Text(
                text = label,
                color = Accent,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                letterSpacing = 1.1.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Ink,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = subtitle,
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
        )

        if (starters.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            var globalIndex = 0
            starters.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { starter ->
                        val cardIndex = globalIndex++
                        Surface(
                            onClick = { onSelect(starter.prompt) },
                            color = Surface,
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, Hairline.copy(alpha = 0.92f)),
                            shadowElevation = 1.dp,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .appleStaggeredEntrance(index = cardIndex, stepDelayMillis = 60L)
                                .appleSpringPressable(pressedScale = 0.96f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .defaultMinSize(minHeight = 92.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(AccentSoft.copy(alpha = 0.75f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(starter.icon, null, Modifier.size(18.dp), tint = Accent)
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.NorthEast,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = MutedInk.copy(alpha = 0.45f)
                                    )
                                }
                                Column(modifier = Modifier.padding(top = 14.dp)) {
                                    Text(
                                        text = starter.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Ink
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = starter.detail,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MutedInk
                                    )
                                }
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        footer()
    }
}

@Composable
fun ConversationAuthor(error: Boolean = false) {
    Row(
        modifier = Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = if (error) Danger.copy(alpha = 0.14f) else AccentSoft.copy(alpha = 0.88f),
            shape = CircleShape,
            border = BorderStroke(0.8.dp, if (error) Danger.copy(alpha = 0.3f) else Accent.copy(alpha = 0.22f))
        ) {
            AsterMark(
                modifier = Modifier
                    .padding(4.5.dp)
                    .size(15.dp),
                tint = if (error) Danger else Accent
            )
        }
        Text(
            text = "Aster",
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
            color = if (error) Danger else Ink.copy(alpha = 0.88f)
        )
    }
}
