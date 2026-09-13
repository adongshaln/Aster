package com.adong.adchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.story.StoryProsePresentation
import com.adong.adchat.ui.screens.StructuredMessageText
import com.adong.adchat.ui.theme.*

/** Story prose has a native reading surface, independent of document artifact previews. */
@Composable
internal fun StoryProseContent(presentation: StoryProsePresentation, streaming: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (presentation.planning.isNotBlank()) {
            StoryProseSection(
                title = if (presentation.planningIncomplete) "预设思考 · ${if (streaming) "接收中" else "未完整结束"}" else "预设思考",
                text = presentation.planning,
                note = "从原始回复保留，不受显示正则隐藏影响",
                initiallyExpanded = streaming
            )
        } else Text(
            if (streaming) "正在接收回复…" else "正文中未发现预设思考片段；无法据此判断模型是否进行了内部推理。",
            color = MutedInk, style = MaterialTheme.typography.labelSmall
        )
        presentation.blocks.forEachIndexed { index, block ->
            key(index, block.title) {
                if (block.title != null) StoryProseSection(block.title, block.text)
                else StructuredMessageText(block.text, streaming && index == presentation.blocks.lastIndex, false, false)
            }
        }
        if (presentation.skippedScripts.isNotEmpty()) Text(
            "未执行的正则：${presentation.skippedScripts.joinToString("、")}",
            color = MutedInk, style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun StoryProseSection(title: String, text: String, note: String? = null, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceInset,
        border = BorderStroke(1.dp, Hairline), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = Ink, style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "收起$title" else "展开$title", tint = MutedInk)
            }
            if (expanded) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (note != null) Text(note, color = MutedInk, style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(text, color = Ink, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}
