package com.adong.adchat.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.*

/**
 * Aster v1.0 — Claude Editorial Header × Apple Dynamic Model Capsule
 * ==================================================================
 * 保持原有 `ConversationHeader` 函数签名 100% 不变，同时将原本平铺的文字副标题
 * 升级为带状态呼吸点的 Apple 灵动胶囊模型切换器（支持 Spatial Spring 宽度自适应与触觉按压反馈）。
 */
@Composable
fun ConversationHeader(
    title: String,
    model: String,
    onOpenDrawer: () -> Unit,
    onModelClick: () -> Unit,
    modelUnavailable: Boolean = false,
    actions: @Composable RowScope.() -> Unit
) {
    val statusDotColor by animateColorAsState(
        targetValue = if (modelUnavailable) Danger else Accent,
        animationSpec = AsterSpring.editorial(),
        label = "ModelStatusColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsterIconButton(
            icon = Icons.Rounded.Menu,
            contentDescription = "打开侧栏",
            onClick = onOpenDrawer
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp, end = 6.dp)
                .animateContentSize(animationSpec = AsterSpring.spatial())
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))

            // Apple 灵动胶囊风格模型切换指示器
            Surface(
                onClick = onModelClick,
                color = SurfaceInset.copy(alpha = 0.78f),
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(0.8.dp, Hairline.copy(alpha = 0.85f)),
                modifier = Modifier
                    .appleSpringPressable(pressedScale = 0.94f)
                    .animateContentSize(animationSpec = AsterSpring.spatial())
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 26.dp)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.5.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Text(
                        text = model.ifBlank { "选择模型" },
                        modifier = Modifier.weight(1f, fill = false),
                        color = if (modelUnavailable) Danger else MutedInk,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = "切换模型",
                        modifier = Modifier.size(14.dp),
                        tint = MutedInk
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
    }
}
