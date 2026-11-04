package com.adong.adchat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.data.PromptInjectionEntry
import com.adong.adchat.data.PromptInjectionPreset
import com.adong.adchat.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptInjectionSheet(
    enabled: Boolean,
    preset: PromptInjectionPreset,
    onEnabledChange: (Boolean) -> Unit,
    onToggleEntry: (String) -> Unit,
    onUpdateEntry: (id: String, name: String, role: String, position: String, content: String) -> Unit,
    onAddEntry: (name: String, role: String, position: String, content: String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onOptionsChange: (pureMode: Boolean, zeroWidth: Boolean) -> Unit,
    onResetPreset: () -> Unit,
    onDismiss: () -> Unit
) {
    var editingEntryId by remember { mutableStateOf<String?>(null) }
    var addingNew by remember { mutableStateOf(false) }

    AsterOptionsSheet(
        title = "提示词按需注入",
        subtitle = "${preset.name} · 已启用 ${preset.enabledCount}/${preset.entries.size} 项",
        onDismiss = onDismiss
    ) {
        // Master Switch Card
        Surface(
            color = Surface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (enabled) Accent.copy(alpha = 0.45f) else Hairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .clickable { onEnabledChange(!enabled) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (enabled) "提示词注入已开启（${preset.enabledCount} 项生效）" else "启用提示词按需注入",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) Accent else Ink,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "开启后自动激活 Ny-Gemini 1.4.3 工具序列化越狱隧道与上下文折叠防拒答机制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedInk,
                        lineHeight = 16.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
        }

        // Pure Mode & Zero-Width Toggles
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallOptionChip(
                title = "Tool 序列化隧道",
                subtitle = if (preset.pureMode) "已开启 (防拒答)" else "已关闭",
                checked = preset.pureMode,
                onClick = { onOptionsChange(!preset.pureMode, preset.zeroWidthObfuscation) },
                modifier = Modifier.weight(1f)
            )
            SmallOptionChip(
                title = "零宽字符防审查",
                subtitle = if (preset.zeroWidthObfuscation) "已开启 (Ny-结构越狱)" else "已关闭",
                checked = preset.zeroWidthObfuscation,
                onClick = { onOptionsChange(preset.pureMode, !preset.zeroWidthObfuscation) },
                modifier = Modifier.weight(1f)
            )
        }

        // Header Row for Entries
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "按需注入条目（点击可展开编辑）",
                style = MaterialTheme.typography.labelLarge,
                color = MutedInk,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { addingNew = !addingNew },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Rounded.Add, null, Modifier.size(15.dp), tint = Accent)
                Spacer(Modifier.width(3.dp))
                Text("新增", color = Accent, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = onResetPreset,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Rounded.Restore, null, Modifier.size(15.dp), tint = MutedInk)
                Spacer(Modifier.width(3.dp))
                Text("重置默认", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            }
        }

        AnimatedVisibility(visible = addingNew) {
            EntryEditorCard(
                initialName = "自定义注入指令",
                initialRole = "system",
                initialPosition = "after_history",
                initialContent = "",
                onSave = { name, role, pos, content ->
                    if (content.isNotBlank()) {
                        onAddEntry(name, role, pos, content)
                        addingNew = false
                    }
                },
                onCancel = { addingNew = false },
                onDelete = null
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preset.entries.forEach { entry ->
                val isEditing = editingEntryId == entry.id
                Surface(
                    color = Surface,
                    shape = RoundedCornerShape(15.dp),
                    border = BorderStroke(1.dp, if (entry.enabled) Hairline else Hairline.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            Modifier
                                .clickable { editingEntryId = if (isEditing) null else entry.id }
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        entry.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (entry.enabled) Ink else MutedInk,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    RoleTag(entry.role)
                                    Spacer(Modifier.width(4.dp))
                                    PositionTag(entry.position)
                                }
                                if (entry.description.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        entry.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MutedInk,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { editingEntryId = if (isEditing) null else entry.id },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Rounded.Edit, "编辑", Modifier.size(16.dp), tint = MutedInk)
                            }
                            Switch(
                                checked = entry.enabled,
                                onCheckedChange = { onToggleEntry(entry.id) }
                            )
                        }

                        AnimatedVisibility(visible = isEditing) {
                            EntryEditorCard(
                                initialName = entry.name,
                                initialRole = entry.role,
                                initialPosition = entry.position,
                                initialContent = entry.content,
                                onSave = { name, role, pos, content ->
                                    onUpdateEntry(entry.id, name, role, pos, content)
                                    editingEntryId = null
                                },
                                onCancel = { editingEntryId = null },
                                onDelete = {
                                    onDeleteEntry(entry.id)
                                    editingEntryId = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallOptionChip(
    title: String,
    subtitle: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (checked) AccentSoft.copy(alpha = 0.55f) else Surface,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (checked) Accent.copy(alpha = 0.4f) else Hairline),
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = if (checked) Accent else Ink,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MutedInk,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RoleTag(role: String) {
    val (bg, fg) = when (role) {
        "assistant" -> AccentSoft to Accent
        "user" -> SageSoft to Sage
        else -> SurfaceInset to MutedInk
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            role.uppercase(),
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

@Composable
private fun PositionTag(position: String) {
    val label = when (position) {
        "before_history" -> "前置"
        "in_chat" -> "穿插"
        else -> "后置"
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(SurfaceInset)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            label,
            fontSize = 9.5.sp,
            color = MutedInk
        )
    }
}

@Composable
private fun EntryEditorCard(
    initialName: String,
    initialRole: String,
    initialPosition: String,
    initialContent: String,
    onSave: (name: String, role: String, position: String, content: String) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var role by remember(initialRole) { mutableStateOf(initialRole) }
    var position by remember(initialPosition) { mutableStateOf(initialPosition) }
    var content by remember(initialContent) { mutableStateOf(initialContent) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(SurfaceInset.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("条目名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system" to "System", "user" to "User", "assistant" to "Assistant").forEach { (r, label) ->
                FilterChip(
                    selected = role == r,
                    onClick = { role = r },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("before_history" to "对话前 (前置)", "after_history" to "对话末 (后置)").forEach { (pos, label) ->
                FilterChip(
                    selected = position == pos,
                    onClick = { position = pos },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("提示词内容（支持 {{lastUserMessage}} 等变量）") },
            minLines = 3,
            maxLines = 7,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(15.dp), tint = Danger)
                    Spacer(Modifier.width(3.dp))
                    Text("删除", color = Danger)
                }
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = onCancel) {
                Text("取消", color = MutedInk)
            }
            Spacer(Modifier.width(6.dp))
            AsterPillButton(
                text = "保存条目",
                primary = true,
                compact = true,
                enabled = content.isNotBlank(),
                onClick = { onSave(name, role, position, content) }
            )
        }
    }
}
