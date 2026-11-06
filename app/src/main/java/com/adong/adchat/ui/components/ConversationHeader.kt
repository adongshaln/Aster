package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.*

/**
 * Aster v4.0 — Claude App 极简无框顶栏
 * 去掉上版多余的胶囊底框与圆点，保持顶栏纯粹通透，同时赋予模型切换行果冻按压与阻尼宽度自适应。
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsterIconButton(
            icon = Icons.Rounded.Menu,
            contentDescription = "打开侧栏",
            onClick = onOpenDrawer
        )

        val modelInteraction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Surface(
            onClick = onModelClick,
            interactionSource = modelInteraction,
            color = Color.Transparent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f)
                .testTag("conversation-model-selector")
                .appleSpringPressable(pressedScale = 0.985f, interactionSource = modelInteraction)
        ) {
            Column(Modifier.heightIn(min = 56.dp).padding(start = 8.dp, end = 6.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier
                        .heightIn(min = 28.dp)
                        .padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!modelUnavailable && model.isNotBlank()) {
                        AsterModelCombineLabel(
                            modelId = model,
                            textColor = MutedInk,
                            fontWeight = FontWeight.Medium,
                            lockupHeight = 14.dp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    } else {
                        Text(
                            text = model.ifBlank { "选择模型" },
                            modifier = Modifier.weight(1f, fill = false),
                            color = if (modelUnavailable) Danger else MutedInk,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "切换模型",
                        modifier = Modifier.size(15.dp),
                        tint = if (modelUnavailable) Danger else MutedInk.copy(alpha = 0.75f)
                    )
                }
            }
        }

        actions()
    }
}
