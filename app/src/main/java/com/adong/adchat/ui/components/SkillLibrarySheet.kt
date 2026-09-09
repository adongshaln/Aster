package com.adong.adchat.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.LoadedSkill
import com.adong.adchat.data.SkillRuntime
import com.adong.adchat.ui.theme.Ink
import com.adong.adchat.ui.theme.MutedInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream

/** The same library/selection surface is used by ordinary chat, story workspaces and Settings. */
@Composable
fun SkillPickerEntry(conversationScope: String?, enabled: Boolean = true, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ConversationSheetAction(Icons.Rounded.AutoAwesome, "技能", enabled, { open = true }, modifier.fillMaxWidth(),
        if (conversationScope == null) "安装、查看与管理技能" else "选择此对话可用的技能")
    if (open) SkillLibrarySheet(conversationScope, onDismiss = { open = false })
}

@Composable
fun SkillLibrarySheet(conversationScope: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { SkillRuntime.persistent(context) }
    val coroutineScope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<LoadedSkill>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var source by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<LoadedSkill?>(null) }
    suspend fun reload() {
        val result = withContext(Dispatchers.IO) { runtime.listInstalled() to conversationScope?.let(runtime::selection).orEmpty() }
        skills = result.first; selected = result.second
    }
    fun action(message: String, block: () -> Unit) {
        if (busy) return
        busy = true; notice = null
        coroutineScope.launch {
            try { withContext(Dispatchers.IO) { block() }; reload(); notice = message }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice = error.message ?: "操作失败，请重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { reload() }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) action("技能已安装；可在输入选项中选择使用") {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 10 * 1024 * 1024) { "技能 ZIP 不能超过 10 MB" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            } ?: error("无法打开 ZIP 文件")
            runtime.installZip(bytes)
        }
    }
    AsterOptionsSheet("技能", if (conversationScope == null) "管理你的写作方法、参考资料与工作流程" else
        "最多选 4 个，模型按需读取；选择自动保存", onDismiss, Icons.Rounded.AutoAwesome) {
        OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text("GitHub 技能链接") }, placeholder = { Text("粘贴包含 SKILL.md 的目录链接") },
            shape = RoundedCornerShape(16.dp), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && source.isNotBlank(), onClick = {
                val url = source.trim()
                action("技能已安装；已有技能的版本已更新") { runtime.install(url) }
            }) { Text("安装 / 更新") }
            OutlinedButton(enabled = !busy, onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("导入 ZIP") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        notice?.let { Text(it, color = MutedInk, style = MaterialTheme.typography.bodySmall) }
        if (skills.isEmpty() && !busy) Text("还没有技能。安装后，可在普通聊天、故事讨论和正文中分别选择。", color = MutedInk)
        skills.forEach { skill ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.titleMedium, color = Ink)
                            Text(skill.description.ifBlank { "此技能未提供简介" }, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MutedInk)
                        }
                        if (conversationScope != null) Checkbox(skill.sourceUrl in selected, modifier = Modifier.testTag("skill-select-${skill.name}"), enabled = !busy && skill.enabled,
                            onCheckedChange = { checked ->
                                val next = if (checked) selected + skill.sourceUrl else selected - skill.sourceUrl
                                action("此对话的技能选择已保存") { runtime.select(conversationScope, next) }
                            })
                    }
                    Text(if (!skill.enabled) "已停用" else if (skill.containsScripts)
                        "含脚本 · 可读取说明与资料，脚本执行需要额外环境" else "可读取说明与资料，使用现有工具",
                        style = MaterialTheme.typography.labelSmall, color = MutedInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { detail = if (detail == skill.sourceUrl) null else skill.sourceUrl }) { Text(if (detail == skill.sourceUrl) "收起" else "详情") }
                        TextButton(enabled = !busy, onClick = { action(if (skill.enabled) "技能已停用" else "技能已启用") { runtime.enable(skill.sourceUrl, !skill.enabled) } }) { Text(if (skill.enabled) "停用" else "启用") }
                        if (skill.sourceUrl.startsWith("https://")) TextButton(enabled = !busy, onClick = { action("技能版本已更新") { runtime.install(skill.sourceUrl) } }) { Text("更新") }
                        TextButton(enabled = !busy, onClick = { deleting = skill }) { Text("删除") }
                    }
                    if (detail == skill.sourceUrl) {
                        Text("来源：${skill.sourceUrl}\n版本：${skill.sha256.take(12)}\n文件：${skill.files.size.takeIf { it > 0 } ?: 1}",
                            style = MaterialTheme.typography.bodySmall, color = MutedInk)
                        Text(skill.files.keys.sorted().joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        Text(skill.content, style = MaterialTheme.typography.bodySmall, color = Ink)
                    }
                }
            }
        }
        Text("安装不等于执行。技能只能使用当前会话已开放的工具；本地技能不会运行 Python 或 Shell。服务配置导出不包含技能包。",
            style = MaterialTheme.typography.bodySmall, color = MutedInk)
    }
    deleting?.let { skill ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${skill.name}？") },
            text = { Text("删除本机安装的技能包，已有聊天内容会保留。") },
            confirmButton = { TextButton(onClick = { deleting = null; action("技能已删除") { runtime.remove(skill.sourceUrl) } }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
