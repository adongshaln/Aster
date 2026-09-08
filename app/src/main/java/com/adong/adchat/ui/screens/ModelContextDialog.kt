package com.adong.adchat.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.ModelContextLimits
import com.adong.adchat.ui.components.AdModalDialog
import com.adong.adchat.ui.theme.MutedInk

@Composable
internal fun ModelContextDialog(model: String, initial: ModelContextLimits?, onDismiss: () -> Unit,
    onApply: (ModelContextLimits?) -> Unit) {
    var window by rememberSaveable(model) { mutableStateOf(initial?.windowTokens?.toString().orEmpty()) }
    var output by rememberSaveable(model) { mutableStateOf(initial?.outputTokens?.toString() ?: "8192") }
    val parsed = runCatching {
        ModelContextLimits(window.toIntOrNull() ?: error("请输入上下文 Token 数。"),
            output.toIntOrNull() ?: error("请输入最大输出 Token 数。")).validate()
    }
    AdModalDialog(title = "模型上下文", subtitle = model, onDismiss = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 410.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("窗口包含输入、输出和推理。请按服务商支持的容量填写；设置更大不会扩展模型能力。", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                OutlinedTextField(window, { window = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("上下文窗口 · Token") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = window.isNotBlank() && parsed.isFailure)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(32768 to "32K", 131072 to "128K", 262144 to "256K", 1048576 to "1M").forEach { (size, title) ->
                        SuggestionChip(onClick = { window = size.toString() }, label = { Text(title) })
                    }
                }
                OutlinedTextField(output, { output = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("最大输出 · Token") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (window.isNotBlank() && parsed.isFailure) Text(parsed.exceptionOrNull()?.message.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                parsed.getOrNull()?.let { limits ->
                    Text("可用输入约 ${limits.inputTokens} Token；另留 ${limits.safetyTokens} Token 安全余量。", style = MaterialTheme.typography.bodySmall)
                }
                Text("本地 Token 为估算，图片按每张 4,096 Token 预留，实际以服务商为准。最大输出包含服务商计入的推理用量。", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                Text("普通聊天优先省略较早完整轮次，本地记录保留。故事固定资料与未整理正文不会被静默删除；装不下时提示调整。", style = MaterialTheme.typography.bodySmall, color = MutedInk)
            }
        }, actions = {
            TextButton(onClick = { onApply(null) }) { Text("恢复默认") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { parsed.getOrNull()?.let(onApply) }, enabled = parsed.isSuccess) { Text("应用") }
        })
}
