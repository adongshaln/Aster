package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.*

/** The same title, model and action hierarchy in every conversation mode. */
@Composable
fun ConversationHeader(
    title: String,
    model: String,
    onOpenDrawer: () -> Unit,
    onModelClick: () -> Unit,
    modelUnavailable: Boolean = false,
    actions: @Composable RowScope.() -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AsterIconButton(Icons.Rounded.Menu, "打开侧栏", onOpenDrawer)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(onClick = onModelClick, color = Color.Transparent, shape = MaterialTheme.shapes.small) {
                Row(Modifier.heightIn(min = 32.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model, Modifier.weight(1f, fill = false), color = if (modelUnavailable) Danger else MutedInk,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Rounded.ExpandMore, "切换模型", Modifier.size(16.dp), tint = MutedInk)
                }
            }
        }
        actions()
    }
}
