package com.adong.adchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.ApiModel
import com.adong.adchat.ui.ConnectionPhase
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.theme.*

enum class RouteKind { Chat, Image, Analysis }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickModelSwitcher(
    kind: RouteKind,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onManageApis: () -> Unit
) {
    var selectedProfileId by remember {
        mutableStateOf(when (kind) {
            RouteKind.Chat -> vm.chatProfile.id
            RouteKind.Image -> vm.imageProfile.id
            RouteKind.Analysis -> vm.mangaAnalysisProfile.id
        })
    }
    var query by remember { mutableStateOf("") }
    val selectedProfile = vm.profiles.firstOrNull { it.id == selectedProfileId } ?: vm.profiles.first()
    val profileListState = rememberLazyListState()
    LaunchedEffect(Unit) {
        vm.profiles.indexOfFirst { it.id == selectedProfileId }.takeIf { it > 0 }?.let { profileListState.scrollToItem(it) }
    }
    val cached = vm.modelsFor(selectedProfile.id)
    val filteredByKind = when (kind) {
        RouteKind.Chat -> cached.filterNot { it.id.isImageLike() }.ifEmpty { cached }
        RouteKind.Image -> cached.filter { it.id.isImageLike() }.ifEmpty { cached }
        RouteKind.Analysis -> cached
    }
    val activeProfileId = when (kind) {
        RouteKind.Chat -> vm.chatProfile.id
        RouteKind.Image -> vm.imageProfile.id
        RouteKind.Analysis -> vm.mangaAnalysisProfile.id
    }
    val activeModel = when (kind) {
        RouteKind.Chat -> vm.chatProfile.chatModel
        RouteKind.Image -> vm.imageProfile.imageModel
        RouteKind.Analysis -> vm.mangaAnalysisProfile.mangaAnalysisModel
    }
    val configuredModel = when {
        selectedProfile.id == activeProfileId -> activeModel
        kind == RouteKind.Chat -> selectedProfile.chatModel
        kind == RouteKind.Image -> selectedProfile.imageModel
        else -> selectedProfile.mangaAnalysisModel.ifBlank { selectedProfile.chatModel }
    }
    val allModels = buildList {
        if (configuredModel.isNotBlank() && filteredByKind.none { it.id == configuredModel }) add(ApiModel(configuredModel, "当前配置"))
        addAll(filteredByKind)
    }.distinctBy { it.id }
    val models = remember(allModels, query) {
        if (query.isBlank()) allModels
        else allModels.filter { it.id.contains(query.trim(), ignoreCase = true) || it.ownedBy.contains(query.trim(), ignoreCase = true) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Canvas,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(width = 42.dp, color = Hairline) }
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(when (kind) {
                        RouteKind.Chat -> Icons.Rounded.Hub
                        RouteKind.Image -> Icons.Rounded.Palette
                        RouteKind.Analysis -> Icons.Rounded.Psychology
                    }, null, Modifier.size(23.dp), tint = Accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(when (kind) {
                        RouteKind.Chat -> "选择对话模型"
                        RouteKind.Image -> "选择绘图模型"
                        RouteKind.Analysis -> "选择漫画辅助模型"
                    }, style = MaterialTheme.typography.titleLarge)
                    Text(when (kind) {
                        RouteKind.Chat -> "仅应用于当前对话，不影响其他任务"
                        RouteKind.Image -> "先确认 API 路由，再选择模型"
                        RouteKind.Analysis -> "用于理解多页设定并整理逐页译文"
                    }, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onDismiss, colors = IconButtonDefaults.iconButtonColors(containerColor = Surface, contentColor = MutedInk)) {
                    Icon(Icons.Rounded.Close, "关闭")
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API 路由", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${vm.profiles.size} 个配置", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(9.dp))
            LazyRow(state = profileListState, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(vm.profiles, key = { it.id }) { profile ->
                    val selected = profile.id == selectedProfileId
                    val connection = vm.connectionFor(profile.id)
                    Surface(
                        onClick = {
                            selectedProfileId = profile.id
                            query = ""
                        },
                        color = if (selected) Night else Surface,
                        contentColor = if (selected) Color.White else Ink,
                        border = if (selected) BorderStroke(1.dp, Color(0xFF494641)) else null,
                        shape = RoundedCornerShape(19.dp),
                        modifier = Modifier.width(228.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Color(0xFF3A3835) else Canvas), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Dns, null, Modifier.size(18.dp), tint = if (selected) Accent else MutedInk)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (connection.phase == ConnectionPhase.Success) "连接可用" else connection.title, color = if (selected) Color(0xFFBEB9B3) else MutedInk, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                                Box(Modifier.size(9.dp).clip(CircleShape).background(if (connection.phase == ConnectionPhase.Success) Sage else Color(0xFFAAA49D)))
                            }
                            Spacer(Modifier.height(11.dp))
                            Text(profile.baseUrl, color = if (selected) Color(0xFFBEB9B3) else MutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(19.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("可用模型", style = MaterialTheme.typography.labelLarge)
                    Text(selectedProfile.name, color = MutedInk, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(color = Surface, shape = CircleShape) {
                    Text("${allModels.size}", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = MutedInk, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (allModels.size >= 6) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(19.dp), tint = MutedInk) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清空", Modifier.size(18.dp)) } },
                    placeholder = { Text("搜索模型 ID") },
                    shape = RoundedCornerShape(17.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Surface,
                        unfocusedContainerColor = Surface,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }
            Spacer(Modifier.height(10.dp))
            if (models.isEmpty()) {
                Surface(color = Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (allModels.isEmpty()) Icons.Rounded.CloudSync else Icons.Rounded.SearchOff, null, tint = Accent)
                        Spacer(Modifier.height(9.dp))
                        Text(if (allModels.isEmpty()) "还没有可选模型" else "没有匹配的模型", style = MaterialTheme.typography.titleMedium)
                        Text(if (allModels.isEmpty()) "请在 API 管理中测试连接并同步模型" else "尝试更短的关键词", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(models, key = { it.id }) { model ->
                        val selected = selectedProfile.id == activeProfileId && model.id == activeModel
                        Surface(
                            onClick = {
                                when (kind) {
                                    RouteKind.Chat -> vm.selectChatModel(selectedProfile.id, model.id)
                                    RouteKind.Image -> vm.selectImageModel(selectedProfile.id, model.id)
                                    RouteKind.Analysis -> vm.selectMangaAnalysisModel(selectedProfile.id, model.id)
                                }
                                onDismiss()
                            },
                            color = if (selected) AccentSoft else Surface,
                            border = if (selected) BorderStroke(1.dp, Color(0xFFFFB9A7)) else null,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(if (selected) Color.White.copy(alpha = .75f) else Canvas), contentAlignment = Alignment.Center) {
                                    Icon(if (kind == RouteKind.Image) Icons.Rounded.Image else Icons.Rounded.ModelTraining, null, Modifier.size(20.dp), tint = if (selected) Accent else MutedInk)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(model.id, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(model.ownedBy.ifBlank { selectedProfile.name }, color = MutedInk, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                                if (selected) {
                                    Surface(color = Accent, contentColor = Color.White, shape = CircleShape) {
                                        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Rounded.Check, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("当前", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                } else {
                                    Icon(Icons.Rounded.ChevronRight, null, tint = MutedInk)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(onClick = { onDismiss(); onManageApis() }, color = Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.Settings, null, Modifier.size(17.dp), tint = MutedInk); Spacer(Modifier.width(7.dp)); Text("管理 API 与模型", color = MutedInk, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(14.dp))
        }
    }
}

private fun String.isImageLike(): Boolean {
    val id = lowercase()
    return listOf("image", "dall", "flux", "stable-diffusion", "sdxl", "ideogram", "recraft").any(id::contains)
}
