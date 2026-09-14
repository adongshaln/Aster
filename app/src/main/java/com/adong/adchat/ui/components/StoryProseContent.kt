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
    val bodyStarted = presentation.blocks.any { it.text.isNotBlank() }
    val thoughtsInitiallyExpanded = streaming && !bodyStarted
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (presentation.thinking.isNotBlank()) {
            StoryProseSection(
                title = if (presentation.thinkingIncomplete) "思考文本 · ${if (streaming) "接收中" else "未完整结束"}" else "思考文本",
                text = presentation.thinking,
                note = "回复中 think / thinking 标签内的文本，可能包含分析或试写",
                initiallyExpanded = thoughtsInitiallyExpanded,
                collapseWhen = bodyStarted
            )
        }
        if (presentation.planning.isNotBlank()) {
            StoryProseSection(
                title = if (presentation.planningIncomplete) "思考过程 · ${if (streaming) "接收中" else "未完整结束"}" else "思考过程",
                text = presentation.planning,
                note = "由当前预设要求模型输出的规划内容",
                initiallyExpanded = thoughtsInitiallyExpanded,
                collapseWhen = bodyStarted
            )
        } else if (presentation.showPlanningStatus) Text(
            if (streaming && !bodyStarted) "正在等待回复…"
            else "未发现可显示的思考过程；这不代表模型没有进行内部推理。",
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
private fun StoryProseSection(
    title: String,
    text: String,
    note: String? = null,
    initiallyExpanded: Boolean = false,
    collapseWhen: Boolean = false
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(collapseWhen) {
        if (collapseWhen) expanded = false
    }
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
