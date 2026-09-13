package com.adong.adchat.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adong.adchat.data.MAX_TAVERN_PROMPT_CHARS
import com.adong.adchat.data.TavernPromptSetting
import com.adong.adchat.ui.theme.*

@Composable
internal fun TavernPromptEditor(prompt: TavernPromptSetting, busy: Boolean, error: String?,
                                onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(prompt.identifier) { mutableStateOf(prompt.content) }
    var discard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val dirty = draft != prompt.content
    val tooLong = draft.length > MAX_TAVERN_PROMPT_CHARS
    fun close() { if (!busy && !saving) { if (dirty) discard = true else onDismiss() } }
    LaunchedEffect(saving, busy, prompt.content, error) {
        if (saving && !busy) {
            saving = false
            if (prompt.content == draft) onDismiss()
            else saveError = error ?: "内容未保存，请重试"
        }
    }
    Dialog(onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::close, enabled = !busy && !saving) {
                        Icon(Icons.Rounded.Close, "关闭条目编辑", tint = MutedInk)
                    }
                    Text("编辑条目", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ink)
                    TextButton(onClick = { saving = true; saveError = null; onSave(draft) },
                        enabled = dirty && !tooLong && !busy && !saving) {
                        Text(if (saving || busy) "保存中…" else "保存")
                    }
                }
                Text(prompt.name, Modifier.padding(horizontal = 20.dp), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = Ink, style = MaterialTheme.typography.titleLarge)
                Text("${prompt.role.uppercase()} · ${if (prompt.enabled) "已启用" else "已停用"} · ${if (prompt.contentModified) "内容已修改" else "原始内容"}",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MutedInk, style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(value = draft, onValueChange = { draft = it; saveError = null },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).testTag("tavern-prompt-content"),
                    label = { Text("条目内容") }, enabled = !busy && !saving, isError = tooLong,
                    textStyle = MaterialTheme.typography.bodyLarge)
                val message = if (tooLong) "最多 $MAX_TAVERN_PROMPT_CHARS 个字符" else saveError
                if (message != null) Text(message, Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { draft = prompt.defaultContent; saveError = null },
                        enabled = draft != prompt.defaultContent && !busy && !saving) { Text("恢复原始内容") }
                    Spacer(Modifier.weight(1f))
                    Text("${draft.length} 字符", color = MutedInk, style = MaterialTheme.typography.labelSmall)
                }
                Text("保存后用于后续请求；编辑停用条目不会自动启用。", Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    color = MutedInk, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        text = { Text("返回后，本次编辑的内容将丢失。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}
