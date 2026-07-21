package com.adong.adchat.ui.screens

import com.adong.adchat.BuildConfig

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.data.ApiModel
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.IMAGE_API_MODE_AUTO
import com.adong.adchat.data.IMAGE_API_MODE_GEMINI
import com.adong.adchat.data.IMAGE_API_MODE_OPENAI
import com.adong.adchat.data.hasValidBaseUrl
import com.adong.adchat.data.invalidExtraHeaderLines
import com.adong.adchat.data.normalized
import com.adong.adchat.ui.ConnectionPhase
import com.adong.adchat.ui.ConnectionUiState
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.components.AdActionOption
import com.adong.adchat.ui.components.AdActionSheet
import com.adong.adchat.ui.components.AdConfirmDialog
import com.adong.adchat.ui.components.AdModalDialog
import com.adong.adchat.ui.components.AdToggleCard
import com.adong.adchat.ui.components.AdChoiceOption
import com.adong.adchat.ui.components.AdSelectionSheet
import com.adong.adchat.ui.components.RouteKind
import com.adong.adchat.ui.theme.*

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenDrawer: () -> Unit) {
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    if (editing != null) {
        ProfileEditor(
            initial = editing!!,
            isNew = editingIsNew,
            models = vm.modelsFor(editing!!.id),
            state = vm.connectionFor(editing!!.id),
            onBack = {
                editing?.id?.let(vm::discardProfileDraft)
                editing = null
            },
            onTest = { draft, onUpdated -> vm.testProfile(draft, onUpdated) },
            onSave = { vm.saveProfile(it); editing = null }
        )
    } else {
        SettingsHome(vm = vm, onOpenDrawer = onOpenDrawer, onEdit = { editingIsNew = false; editing = it }, onCreate = { editingIsNew = true; editing = it })
    }
}

@Composable
private fun SettingsHome(vm: MainViewModel, onOpenDrawer: () -> Unit, onEdit: (ApiProfile) -> Unit, onCreate: (ApiProfile) -> Unit) {
    var addMenu by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<ApiProfile?>(null) }
    var transferMode by remember { mutableStateOf<String?>(null) }
    var includeApiKeys by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                IconButton(onClick = onOpenDrawer, modifier = Modifier.offset(x = (-8).dp, y = (-4).dp)) { Icon(Icons.Rounded.Menu, "\u6253\u5f00\u4fa7\u680f") }
                Column(Modifier.weight(1f)) {
                    Text("API 路由中心", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("分别管理对话与绘图服务", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { transferMode = "import"; importError = null }) { Icon(Icons.Rounded.FileUpload, "导入配置") }
                IconButton(onClick = { transferMode = "export" }) { Icon(Icons.Rounded.FileDownload, "导出配置") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("路由" to Icons.AutoMirrored.Rounded.CallSplit, "API" to Icons.Rounded.Dns, "助手" to Icons.Rounded.SmartToy).forEachIndexed { index, (label, icon) ->
                    val selected = section == index
                    Surface(
                        onClick = { section = index },
                        modifier = Modifier.weight(1f),
                        color = if (selected) Night else Surface,
                        contentColor = if (selected) Color.White else MutedInk,
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(if (selected) Color(0xFF3A3835) else Canvas), contentAlignment = Alignment.Center) {
                                Icon(icon, null, Modifier.size(15.dp), tint = if (selected) Accent else MutedInk)
                            }
                            Spacer(Modifier.width(7.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        if (section == 0) {
            item { IndependenceBanner(vm) }
            item {
                Column {
                    SectionTitle("ACTIVE ROUTES", "当前路由", "对话路由仅影响当前窗口，绘图路由保持独立。")
                Spacer(Modifier.height(12.dp))
                RouteAssignmentCard(
                    kind = RouteKind.Chat,
                    profile = vm.chatProfile,
                    profiles = vm.profiles,
                    models = vm.modelsFor(vm.chatProfile.id),
                    onProfile = vm::selectChatProfile,
                    onModel = { vm.selectChatModel(vm.chatProfile.id, it) }
                )
                Spacer(Modifier.height(10.dp))
                RouteAssignmentCard(
                    kind = RouteKind.Image,
                    profile = vm.imageProfile,
                    profiles = vm.profiles,
                    models = vm.modelsFor(vm.imageProfile.id),
                    onProfile = vm::selectImageProfile,
                    onModel = { vm.selectImageModel(vm.imageProfile.id, it) }
                )
            }
        }
        }
        if (section == 1) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) { SectionTitle("API PROFILES", "API 配置", "每个配置都有自己的 URL、Key、路径和模型。") }
                    Button(onClick = { addMenu = true }, shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Night)) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    vm.profiles.forEach { profile ->
                        ProfileCard(
                            profile = profile,
                            state = vm.connectionFor(profile.id),
                            modelCount = vm.modelsFor(profile.id).size,
                            usedForChat = profile.id == vm.chatProfile.id,
                            usedForImage = profile.id == vm.imageProfile.id,
                            canDelete = vm.profiles.size > 1,
                            onTest = { vm.testProfile(profile) },
                            onEdit = { onEdit(profile) },
                            onDuplicate = { vm.duplicateProfile(profile.id) },
                            onDelete = { deleteCandidate = profile }
                        )
                    }
                }
            }
        }
        }
        if (section == 2) {
            item {
                Column {
                    SectionTitle("ASSISTANT", "对话行为", "系统提示词只影响对话，不会发送给绘图 API。")
                Spacer(Modifier.height(12.dp))
                Surface(color = Surface, shape = RoundedCornerShape(20.dp)) {
                    OutlinedTextField(
                        value = vm.appConfig.systemPrompt,
                        onValueChange = vm::updateSystemPrompt,
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        minLines = 4,
                        maxLines = 8,
                        placeholder = { Text("定义助手的角色与回答风格") },
                        shape = RoundedCornerShape(15.dp),
                        colors = editorFieldColors()
                    )
                }
            }
        }
        }
        item {
            Text("ADChat ${BuildConfig.VERSION_NAME} \u00b7 Secure multi-route client", color = MutedInk, style = MaterialTheme.typography.labelMedium)
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    if (addMenu) {
        AdActionSheet(
            title = "添加 API 配置",
            subtitle = "选择一个起点，之后仍可修改所有字段",
            actions = listOf(
                AdActionOption("blank", "空白配置", "从 URL、Key 和模型开始填写", Icons.Rounded.AddCircleOutline),
                AdActionOption("openai", "OpenAI / GPT-5.6", "预填 Responses、缓存与绘图路径", Icons.Rounded.Cloud),
                AdActionOption("local", "本地兼容服务", "适合局域网、模拟器和自建网关", Icons.Rounded.Dns)
            ),
            onAction = { action ->
                addMenu = false
                when (action.id) {
                    "blank" -> onCreate(vm.createBlankProfile())
                    "openai" -> onCreate(vm.createOpenAiProfile())
                    "local" -> onCreate(vm.createLocalProfile())
                }
            },
            onDismiss = { addMenu = false },
            headerIcon = Icons.Rounded.AddLink
        )
    }
    deleteCandidate?.let { profile ->
        AdConfirmDialog(
            title = "删除 ${profile.name}？",
            message = "此操作会删除该 API 的 URL、Key、模型缓存与路由配置，且无法撤销。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            icon = Icons.Rounded.DeleteOutline,
            destructive = true,
            onConfirm = { vm.deleteProfile(profile.id); deleteCandidate = null },
            onDismiss = { deleteCandidate = null }
        )
    }
    transferMode?.let { mode ->
        ProfileTransferDialog(
            mode = mode,
            exportJson = vm.exportProfiles(includeApiKeys),
            includeApiKeys = includeApiKeys,
            importText = importText,
            importError = importError,
            onIncludeApiKeys = { includeApiKeys = it },
            onImportText = { importText = it; importError = null },
            onImport = {
                vm.importProfiles(importText).onSuccess { transferMode = null; importText = "" }.onFailure { importError = it.message }
            },
            onDismiss = { transferMode = null; importError = null }
        )
    }
}

@Composable
private fun ProfileTransferDialog(
    mode: String,
    exportJson: String,
    includeApiKeys: Boolean,
    importText: String,
    importError: String?,
    onIncludeApiKeys: (Boolean) -> Unit,
    onImportText: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    val exporting = mode == "export"
    AdModalDialog(
        title = if (exporting) "导出 API 配置" else "导入 API 配置",
        subtitle = if (exporting) "生成可跨设备迁移的 JSON" else "从 ADChat JSON 恢复路由与模型",
        icon = if (exporting) Icons.Rounded.FileDownload else Icons.Rounded.FileUpload,
        onDismiss = onDismiss,
        content = {
            if (exporting) {
                AdToggleCard(
                    title = "包含 API Key",
                    subtitle = "仅在可信设备间迁移时开启",
                    checked = includeApiKeys,
                    onCheckedChange = onIncludeApiKeys,
                    warning = true
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = exportJson,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    textStyle = MaterialTheme.typography.labelMedium,
                    shape = RoundedCornerShape(18.dp),
                    colors = editorFieldColors()
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("粘贴 ADChat 导出的 JSON", modifier = Modifier.weight(1f), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    Surface(
                        onClick = {
                            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            if (text.isNotBlank()) onImportText(text)
                        },
                        color = AccentSoft,
                        contentColor = Accent,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ContentPaste, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("粘贴", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(
                    value = importText,
                    onValueChange = onImportText,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    placeholder = { Text("{ \"format\": \"adchat-profiles-v1\", ... }") },
                    textStyle = MaterialTheme.typography.labelMedium,
                    shape = RoundedCornerShape(18.dp),
                    colors = editorFieldColors()
                )
                importError?.let {
                    Surface(color = DangerSoft, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 9.dp)) {
                        Text(it, Modifier.padding(horizontal = 11.dp, vertical = 8.dp), color = Danger, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        actions = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
            ) { Text("取消", fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = {
                    if (exporting) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ADChat API Profiles", exportJson))
                        onDismiss()
                    } else onImport()
                },
                enabled = exporting || importText.isNotBlank(),
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (exporting) Ink else Accent)
            ) {
                Icon(if (exporting) Icons.Rounded.ContentCopy else Icons.Rounded.FileUpload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (exporting) "复制 JSON" else "导入", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun IndependenceBanner(vm: MainViewModel) {
    val testing = vm.connectionFor(vm.chatProfile.id).phase == ConnectionPhase.Testing || vm.connectionFor(vm.imageProfile.id).phase == ConnectionPhase.Testing
    Surface(color = Night, contentColor = Color.White, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF383633)), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.CallSplit, null, tint = Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("独立路由已启用", style = MaterialTheme.typography.titleMedium)
                Text("每个对话窗口都可独立选择 API 与模型。", color = Color(0xFFBDB8B2), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = vm::testActiveRoutes,
                enabled = !testing,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF3A3835), contentColor = Color.White)
            ) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                else Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (testing) "测试中" else "诊断")
            }
        }
    }
}

@Composable
private fun RouteAssignmentCard(
    kind: RouteKind,
    profile: ApiProfile,
    profiles: List<ApiProfile>,
    models: List<ApiModel>,
    onProfile: (String) -> Unit,
    onModel: (String) -> Unit
) {
    var showProfileSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    val configured = when (kind) {
        RouteKind.Chat -> profile.chatModel
        RouteKind.Image -> profile.imageModel
        RouteKind.Analysis -> profile.mangaAnalysisModel.ifBlank { profile.chatModel }
    }
    val candidates = when (kind) {
        RouteKind.Chat -> models.filterNot { it.id.isImageLike() }.ifEmpty { models }
        RouteKind.Image -> models.filter { it.id.isImageLike() }.ifEmpty { models }
        RouteKind.Analysis -> models
    }
    val displayModels = buildList {
        if (configured.isNotBlank() && candidates.none { it.id == configured }) add(ApiModel(configured))
        addAll(candidates)
    }.distinctBy { it.id }
    val accent = if (kind == RouteKind.Chat) Accent else Sage
    val soft = if (kind == RouteKind.Chat) AccentSoft else SageSoft

    Surface(color = Surface, shape = RoundedCornerShape(23.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(soft), contentAlignment = Alignment.Center) {
                    Icon(if (kind == RouteKind.Chat) Icons.Rounded.Forum else Icons.Rounded.Palette, null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (kind == RouteKind.Chat) "当前对话路由" else "绘图路由", style = MaterialTheme.typography.titleMedium)
                    Text(if (kind == RouteKind.Chat) "仅作用于当前窗口" else "独立 API 与模型", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                }
                Surface(color = soft, contentColor = accent, shape = CircleShape) {
                    Text(if (kind == RouteKind.Chat) "CHAT" else "IMAGE", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            RouteValueButton("API", profile.name, Icons.Rounded.Dns, accent) { showProfileSheet = true }
            Spacer(Modifier.height(8.dp))
            RouteValueButton("模型", configured.ifBlank { "未选择" }, Icons.Rounded.ViewInAr, accent) { showModelSheet = true }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(7.dp))
                Text(profile.baseUrl, color = MutedInk, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (showProfileSheet) {
        AdSelectionSheet(
            title = if (kind == RouteKind.Chat) "选择对话 API" else "选择绘图 API",
            subtitle = "每个 API 都保留自己的 URL、Key 和模型",
            options = profiles.map { item -> AdChoiceOption(item.id, item.name, item.baseUrl, Icons.Rounded.Dns, if (item.id == profile.id) "当前" else null) },
            selectedId = profile.id,
            onSelect = { onProfile(it.id); showProfileSheet = false },
            onDismiss = { showProfileSheet = false },
            searchPlaceholder = "搜索 API 配置",
            headerIcon = Icons.AutoMirrored.Rounded.CallSplit
        )
    }
    if (showModelSheet) {
        AdSelectionSheet(
            title = if (kind == RouteKind.Chat) "选择对话模型" else "选择绘图模型",
            subtitle = if (displayModels.isEmpty()) "请先测试 API 并同步模型" else "${profile.name} · ${displayModels.size} 个可选",
            options = displayModels.map { model -> AdChoiceOption(model.id, model.id, model.ownedBy.ifBlank { profile.name }, Icons.Rounded.ViewInAr, if (model.id == configured) "当前" else null) },
            selectedId = configured,
            onSelect = { onModel(it.id); showModelSheet = false },
            onDismiss = { showModelSheet = false },
            searchPlaceholder = "搜索模型 ID",
            headerIcon = Icons.Rounded.ModelTraining
        )
    }
}

@Composable
private fun RouteValueButton(label: String, value: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Canvas, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Surface), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = accent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = MutedInk, style = MaterialTheme.typography.labelSmall)
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Surface(color = Surface, shape = CircleShape) {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.padding(6.dp).size(16.dp), tint = MutedInk)
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: ApiProfile,
    state: ConnectionUiState,
    modelCount: Int,
    usedForChat: Boolean,
    usedForImage: Boolean,
    canDelete: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    val statusColor by animateColorAsState(
        targetValue = when (state.phase) {
            ConnectionPhase.Success -> Sage
            ConnectionPhase.Error -> Danger
            ConnectionPhase.Testing -> Accent
            ConnectionPhase.Idle -> MutedInk
        },
        animationSpec = tween(180),
        label = "profile-status"
    )
    Surface(color = Surface, shape = RoundedCornerShape(23.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(Canvas), contentAlignment = Alignment.Center) {
                    if (state.phase == ConnectionPhase.Testing) CircularProgressIndicator(Modifier.size(19.dp), color = Accent, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Dns, null, tint = statusColor, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(profile.baseUrl, color = MutedInk, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (usedForChat || usedForImage) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (usedForChat) StatusBadge("对话中", AccentSoft, Accent)
                            if (usedForImage) StatusBadge("绘图中", SageSoft, Sage)
                        }
                    }
                }
                IconButton(
                    onClick = { showActions = true },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Canvas, contentColor = MutedInk),
                    modifier = Modifier.size(38.dp)
                ) { Icon(Icons.Rounded.MoreHoriz, "更多", Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniMetric("状态", state.title, statusColor, Modifier.weight(1f))
                MiniMetric("模型", if (modelCount == 0) "未同步" else "$modelCount 个", Ink, Modifier.weight(1f))
                MiniMetric("密钥", if (profile.apiKey.isBlank()) "未填写" else "••••${profile.apiKey.takeLast(4)}", Ink, Modifier.weight(1f))
            }
            if (state.phase == ConnectionPhase.Error || (state.phase == ConnectionPhase.Success && modelCount == 0)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    state.detail,
                    color = if (state.phase == ConnectionPhase.Error) Danger else MutedInk,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = state.phase != ConnectionPhase.Testing, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("测试并同步")
                }
                Button(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Night)) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("编辑")
                }
            }
        }
    }
    if (showActions) {
        AdActionSheet(
            title = profile.name,
            subtitle = "管理这个 API 配置",
            actions = listOf(
                AdActionOption("duplicate", "复制配置", "保留当前参数创建副本", Icons.Rounded.ContentCopy),
                AdActionOption("delete", "删除配置", if (canDelete) "删除 URL、Key 和模型缓存" else "至少需要保留一个 API", Icons.Rounded.DeleteOutline, destructive = true, enabled = canDelete)
            ),
            onAction = { action ->
                showActions = false
                if (action.id == "duplicate") onDuplicate() else if (action.id == "delete") onDelete()
            },
            onDismiss = { showActions = false },
            headerIcon = Icons.Rounded.Dns
        )
    }
}

@Composable
private fun Gpt56OptimizationCard(draft: ApiProfile, onDraft: (ApiProfile) -> Unit) {
    Surface(color = Night, contentColor = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF393735)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Accent)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("GPT-5.6 Sol 优化", style = MaterialTheme.typography.titleMedium)
                    Text("智能高命中缓存与协议回退", color = Color(0xFFBDB8B2), style = MaterialTheme.typography.bodyMedium)
                }
                StatusBadge("已识别", Color(0xFF3A3835), Accent)
            }

            Text("API 协议", color = Color(0xFFBDB8B2), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GptOption("Chat Completions", draft.chatApiMode == "chat", Modifier.weight(1f)) { onDraft(draft.copy(chatApiMode = "chat")) }
                GptOption("Responses 推荐", draft.chatApiMode == "responses", Modifier.weight(1f)) { onDraft(draft.copy(chatApiMode = "responses")) }
            }

            AdToggleCard(
                title = "提示词缓存",
                subtitle = "滚动缓存整段对话前缀，并保持会话 cache key 稳定",
                checked = draft.promptCacheEnabled,
                onCheckedChange = { onDraft(draft.copy(promptCacheEnabled = it, promptCacheMode = if (it) "adaptive" else draft.promptCacheMode)) },
                dark = true
            )
            if (draft.promptCacheEnabled) {
                Text("缓存策略", color = Color(0xFFBDB8B2), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GptOption("智能高命中", draft.promptCacheMode != "compatibility", Modifier.weight(1f)) { onDraft(draft.copy(promptCacheMode = "adaptive")) }
                    GptOption("兼容模式", draft.promptCacheMode == "compatibility", Modifier.weight(1f)) { onDraft(draft.copy(promptCacheMode = "compatibility")) }
                }
            }
            Text("智能高命中会优先使用 Chat Completions 显式滚动断点，把当前整段输入作为下一轮可复用前缀；若中转站不支持，会自动回退到所选协议的自动缓存。命中率取决于上下文长度和最新回复长度，并非固定值。", color = Color(0xFFBDB8B2), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GptOption(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) Accent else Color(0xFF393735),
        contentColor = Color.White,
        border = if (selected) BorderStroke(1.dp, Color(0xFFFF9A80)) else BorderStroke(1.dp, Color(0xFF4A4744)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
            if (selected) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ImageProtocolOption(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) AccentSoft else Canvas,
        contentColor = if (selected) Accent else Ink,
        border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = .48f) else Hairline),
        shape = RoundedCornerShape(13.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ProfileEditor(
    initial: ApiProfile,
    isNew: Boolean,
    models: List<ApiModel>,
    state: ConnectionUiState,
    onBack: () -> Unit,
    onTest: (ApiProfile, (ApiProfile) -> Unit) -> Unit,
    onSave: (ApiProfile) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    LaunchedEffect(initial) { draft = initial }
    var advanced by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val normalizedDraft = draft.normalized()
    val normalizedInitial = initial.normalized()
    val connectionChanged = normalizedDraft.baseUrl != normalizedInitial.baseUrl ||
        normalizedDraft.apiKey != normalizedInitial.apiKey ||
        normalizedDraft.modelsPath != normalizedInitial.modelsPath ||
        normalizedDraft.extraHeaders != normalizedInitial.extraHeaders
    val invalidHeaders = draft.invalidExtraHeaderLines()
    val validBaseUrl = draft.hasValidBaseUrl()
    val hasDefaultModel = draft.chatModel.isNotBlank() || draft.imageModel.isNotBlank()
    val visibleState = if (connectionChanged) {
        ConnectionUiState(ConnectionPhase.Idle, "参数已修改", "请重新测试当前参数")
    } else state
    val valid = draft.name.isNotBlank() && validBaseUrl && invalidHeaders.isEmpty()
    val testDisabledReason = when {
        !validBaseUrl -> "请先填写完整的 Base URL"
        invalidHeaders.isNotEmpty() -> "请先修正额外请求头格式"
        else -> null
    }

    BackHandler { if (draft != initial) confirmDiscard = true else onBack() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (draft != initial) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(if (isNew) "添加 API" else "编辑 API", style = MaterialTheme.typography.titleLarge)
                Text("独立管理 URL、Key、路径和模型", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            }
            AnimatedVisibility(draft != initial) {
                StatusBadge("未保存", AccentSoft, Accent)
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { onSave(normalizedDraft) }, enabled = valid) {
                Text("保存", fontWeight = FontWeight.Bold)
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                ConnectionEditorCard(
                    state = visibleState,
                    canTest = testDisabledReason == null,
                    disabledReason = testDisabledReason,
                    onTest = { onTest(normalizedDraft) { updated -> draft = updated } }
                )
            }
            item {
                ProfileReadinessCard(
                    validBaseUrl = validBaseUrl,
                    headersValid = invalidHeaders.isEmpty(),
                    hasApiKey = draft.apiKey.isNotBlank(),
                    isLocalHttp = draft.baseUrl.trim().startsWith("http://"),
                    hasDefaultModel = hasDefaultModel,
                    syncedModelCount = models.size,
                    connectionReady = visibleState.phase == ConnectionPhase.Success
                )
            }
            item {
                EditorSection("基本信息", "用于识别和连接这个 API。") {
                    EditorField("配置名称", draft.name, { draft = draft.copy(name = it) }, "例如：主对话 API", Icons.Outlined.Badge)
                    EditorField("Base URL", draft.baseUrl, { draft = draft.copy(baseUrl = it, cachedModels = emptyList(), lastLatencyMs = null) }, "https://api.example.com", Icons.Outlined.Language,
                        supporting = if (validBaseUrl) "保存时会自动移除末尾 /" else "必须是包含完整主机的 http:// 或 https:// 地址")
                    Column {
                        Text("API Key", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = draft.apiKey,
                            onValueChange = { draft = draft.copy(apiKey = it, cachedModels = emptyList(), lastLatencyMs = null) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("可留空用于本地服务") },
                            leadingIcon = { Icon(Icons.Outlined.Key, null) },
                            trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) { Icon(if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null) } },
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true, shape = RoundedCornerShape(15.dp), colors = editorFieldColors()
                        )
                    }
                }
            }
            item {
                EditorSection("默认模型", "切换到此 API 时，会恢复这里保存的模型。") {
                    EditorModelField("对话模型", draft.chatModel, models.filterNot { it.id.isImageLike() }.ifEmpty { models }, { draft = draft.copy(chatModel = it) }, Icons.Outlined.Forum)
                    EditorModelField("绘图模型", draft.imageModel, models.filter { it.id.isImageLike() }.ifEmpty { models }, { draft = draft.copy(imageModel = it) }, Icons.Outlined.Palette)
                }
            }
            item {
                EditorSection("流式稳定性", "控制中途断线后的安全恢复行为。") {
                    AdToggleCard(
                        title = "流式安全续传",
                        subtitle = "中途断线时最多自动续传一次；基于已生成内容继续，不会从头重跑",
                        checked = draft.autoResumeStream,
                        onCheckedChange = { draft = draft.copy(autoResumeStream = it) }
                    )
                    Text(
                        "仅对连接重置、提前断流和瞬时网络错误生效。认证失败、额度不足及用户主动停止不会自动重试。",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (draft.chatModel.isGpt56Model()) {
                item { Gpt56OptimizationCard(draft = draft, onDraft = { draft = it }) }
            }
            item {
                Surface(color = Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { advanced = !advanced }) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Code, null, tint = Accent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) { Text("接口路径与请求头", style = MaterialTheme.typography.titleMedium); Text("OpenAI 兼容接口的高级设置", color = MutedInk, style = MaterialTheme.typography.bodyMedium) }
                            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        }
                        AnimatedVisibility(advanced) {
                            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Column {
                                    Text("绘图协议", style = MaterialTheme.typography.labelLarge)
                                    Spacer(Modifier.height(7.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ImageProtocolOption("自动识别", draft.imageApiMode == IMAGE_API_MODE_AUTO, Modifier.weight(1f)) {
                                            draft = draft.copy(imageApiMode = IMAGE_API_MODE_AUTO)
                                        }
                                        ImageProtocolOption("OpenAI Images", draft.imageApiMode == IMAGE_API_MODE_OPENAI, Modifier.weight(1f)) {
                                            draft = draft.copy(imageApiMode = IMAGE_API_MODE_OPENAI)
                                        }
                                        ImageProtocolOption("Gemini", draft.imageApiMode == IMAGE_API_MODE_GEMINI, Modifier.weight(1f)) {
                                            draft = draft.copy(imageApiMode = IMAGE_API_MODE_GEMINI)
                                        }
                                    }
                                    Text(
                                        if (draft.imageApiMode == IMAGE_API_MODE_GEMINI) {
                                            "通过 Chat Completions 的多模态 image 输出绘图，支持参考图。"
                                        } else {
                                            "自动模式会根据 gemini-*image 模型切换到 Gemini 绘图协议。"
                                        },
                                        color = MutedInk,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                                EditorField("模型列表路径", draft.modelsPath, { draft = draft.copy(modelsPath = it, cachedModels = emptyList(), lastLatencyMs = null) }, "/v1/models", Icons.AutoMirrored.Outlined.List)
                                EditorField("对话接口路径", draft.chatPath, { draft = draft.copy(chatPath = it) }, "/v1/chat/completions", Icons.AutoMirrored.Outlined.Chat)
                                EditorField("Responses API path", draft.responsesPath, { draft = draft.copy(responsesPath = it) }, "/v1/responses", Icons.Outlined.Bolt)
                                EditorField("绘图接口路径", draft.imagePath, { draft = draft.copy(imagePath = it) }, "/v1/images/generations", Icons.Outlined.Image)
                                EditorField("\u53c2\u8003\u56fe\u7f16\u8f91\u8def\u5f84", draft.imageEditPath, { draft = draft.copy(imageEditPath = it) }, "/v1/images/edits", Icons.Outlined.AutoFixHigh, supporting = "multipart image edit endpoint")
                                EditorField(
                                    "额外请求头",
                                    draft.extraHeaders,
                                    { draft = draft.copy(extraHeaders = it, cachedModels = emptyList(), lastLatencyMs = null) },
                                    "X-Header: value",
                                    Icons.Outlined.DataObject,
                                    supporting = invalidHeaders.firstOrNull()?.let { "格式错误：$it" }
                                        ?: "每行一个 Header: value，空行会自动移除",
                                    minLines = 4
                                )
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { onSave(normalizedDraft) },
                    enabled = valid,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Icon(Icons.Rounded.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (visibleState.phase == ConnectionPhase.Success) "保存并完成" else "保存 API 配置",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
    if (confirmDiscard) {
        AdConfirmDialog(
            title = "放弃未保存的修改？",
            message = "返回后，本次对 API 配置的修改不会保留。",
            confirmLabel = "放弃",
            dismissLabel = "继续编辑",
            icon = Icons.AutoMirrored.Rounded.Undo,
            destructive = true,
            onConfirm = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false }
        )
    }
}

@Composable
private fun ProfileReadinessCard(
    validBaseUrl: Boolean,
    headersValid: Boolean,
    hasApiKey: Boolean,
    isLocalHttp: Boolean,
    hasDefaultModel: Boolean,
    syncedModelCount: Int,
    connectionReady: Boolean
) {
    val checks = listOf(
        Triple("服务地址", if (validBaseUrl) "URL 格式正确" else "需要完整的 http(s) 主机地址", validBaseUrl),
        Triple("请求头", if (headersValid) "额外 Header 格式可用" else "存在无效的 Header: value", headersValid),
        Triple("默认模型", if (hasDefaultModel) "已填写至少一个模型" else "尚未选择对话或绘图模型", hasDefaultModel),
        Triple("模型列表", if (syncedModelCount > 0) "已同步 $syncedModelCount 个模型" else "未同步，仍可手动填写 ID", syncedModelCount > 0),
        Triple("连接验证", if (connectionReady) "当前参数已通过测试" else "保存前建议执行一次测试", connectionReady)
    )
    val authReady = hasApiKey || isLocalHttp
    val readyCount = checks.count { it.third }
    val progress = readyCount / checks.size.toFloat()
    val container by animateColorAsState(
        if (readyCount == checks.size && authReady) SageSoft else Surface,
        tween(200),
        label = "readiness-container"
    )
    Surface(color = container, shape = RoundedCornerShape(22.dp), modifier = Modifier.animateContentSize(tween(220))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 4.dp,
                        color = if (readyCount == checks.size) Sage else Accent,
                        trackColor = Hairline
                    )
                    Text("$readyCount", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("配置完成度", style = MaterialTheme.typography.titleMedium)
                    Text("$readyCount/${checks.size} 项就绪，测试不会产生对话 Token 费用", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    if (readyCount == checks.size) Icons.Rounded.Verified else Icons.AutoMirrored.Rounded.FactCheck,
                    null,
                    tint = if (readyCount == checks.size) Sage else Accent
                )
            }
            Spacer(Modifier.height(14.dp))
            checks.forEachIndexed { index, (title, detail, ready) ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.padding(top = 2.dp).size(21.dp).clip(CircleShape)
                            .background(if (ready) SageSoft else AccentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (ready) Icons.Rounded.Check else Icons.Rounded.PriorityHigh,
                            null,
                            Modifier.size(13.dp),
                            tint = if (ready) Sage else Accent
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.labelLarge)
                        Text(detail, color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (index != checks.lastIndex) Spacer(Modifier.height(10.dp))
            }
            if (!authReady) {
                Spacer(Modifier.height(12.dp))
                Surface(color = AccentSoft, contentColor = Ink, shape = RoundedCornerShape(13.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.KeyOff, null, Modifier.size(16.dp), tint = Accent)
                        Spacer(Modifier.width(7.dp))
                        Text("HTTPS 服务未填写 Key，请确认该服务允许无鉴权访问", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionEditorCard(
    state: ConnectionUiState,
    canTest: Boolean,
    disabledReason: String?,
    onTest: () -> Unit
) {
    val color = when (state.phase) {
        ConnectionPhase.Success -> Sage
        ConnectionPhase.Error -> Danger
        ConnectionPhase.Testing -> Accent
        ConnectionPhase.Idle -> MutedInk
    }
    Surface(
        color = Night,
        contentColor = Color.White,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(tween(220))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFF343331)), contentAlignment = Alignment.Center) {
                    if (state.phase == ConnectionPhase.Testing) {
                        CircularProgressIndicator(Modifier.size(21.dp), color = Accent, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            when (state.phase) {
                                ConnectionPhase.Success -> Icons.Rounded.CheckCircle
                                ConnectionPhase.Error -> Icons.Rounded.ErrorOutline
                                else -> Icons.Rounded.NetworkCheck
                            },
                            null,
                            tint = color,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium)
                    Text(state.detail, color = Color(0xFFBDB8B2), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                state.latencyMs?.let {
                    Surface(color = Color(0xFF343331), contentColor = Color.White, shape = CircleShape) {
                        Text("${it}ms", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(5.dp))
                }
                TextButton(
                    onClick = onTest,
                    enabled = canTest && state.phase != ConnectionPhase.Testing,
                    colors = ButtonDefaults.textButtonColors(contentColor = Accent, disabledContentColor = Color(0xFF77736F))
                ) {
                    Text(if (state.phase == ConnectionPhase.Testing) "测试中" else "测试")
                }
            }
            if (!canTest && disabledReason != null) {
                Spacer(Modifier.height(10.dp))
                Surface(color = Color(0xFF3A2D2B), contentColor = Color(0xFFFFB4A5), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(disabledReason, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column { Text(title, style = MaterialTheme.typography.titleLarge); Text(description, color = MutedInk, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(10.dp)); Surface(color = Surface, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(15.dp), content = content) } }
}

@Composable
private fun EditorField(label: String, value: String, onValue: (String) -> Unit, placeholder: String, icon: ImageVector, supporting: String? = null, minLines: Int = 1) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(7.dp))
        OutlinedTextField(value = value, onValueChange = onValue, modifier = Modifier.fillMaxWidth(), placeholder = { Text(placeholder) }, leadingIcon = { Icon(icon, null, Modifier.size(19.dp)) }, singleLine = minLines == 1, minLines = minLines, maxLines = if (minLines == 1) 1 else minLines + 3, shape = RoundedCornerShape(15.dp), colors = editorFieldColors())
        supporting?.let { Text(it, Modifier.padding(start = 4.dp, top = 5.dp), color = if (it.startsWith("必须") || it.startsWith("格式错误")) Danger else MutedInk, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun EditorModelField(label: String, value: String, models: List<ApiModel>, onValue: (String) -> Unit, icon: ImageVector) {
    var showSheet by remember { mutableStateOf(false) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                if (models.isNotEmpty()) "${models.size} 个已同步" else "可手动填写",
                color = MutedInk,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.height(7.dp))
        if (models.isNotEmpty()) {
            Surface(
                onClick = { showSheet = true },
                color = AccentSoft,
                contentColor = Ink,
                border = BorderStroke(1.dp, Color(0xFFFFC3B4)),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = .72f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(20.dp), tint = Accent)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("从已同步模型选择", color = Accent, style = MaterialTheme.typography.labelMedium)
                        Text(value.ifBlank { "尚未选择" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Surface(color = Color.White.copy(alpha = .72f), contentColor = Accent, shape = RoundedCornerShape(11.dp)) {
                        Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("选择", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(16.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            Text("或手动填写模型 ID", color = MutedInk, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 3.dp))
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("填写模型 ID") },
            leadingIcon = {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Canvas), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Code, null, Modifier.size(18.dp), tint = MutedInk)
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = editorFieldColors()
        )
    }
    if (showSheet) {
        AdSelectionSheet(
            title = label,
            subtitle = "从已同步的模型中选择，也可继续手动填写",
            options = models.map { model -> AdChoiceOption(model.id, model.id, model.ownedBy, Icons.Rounded.ViewInAr, if (model.id == value) "当前" else null) },
            selectedId = value,
            onSelect = { onValue(it.id); showSheet = false },
            onDismiss = { showSheet = false },
            searchPlaceholder = "搜索模型 ID",
            headerIcon = Icons.Rounded.ModelTraining
        )
    }
}

@Composable
private fun SectionTitle(eyebrow: String, title: String, description: String) {
    Text(eyebrow, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
    Spacer(Modifier.height(4.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(3.dp)); Text(description, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun StatusBadge(text: String, background: Color, content: Color) {
    Surface(color = background, contentColor = content, shape = CircleShape) { Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun MiniMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = Canvas, shape = RoundedCornerShape(12.dp), modifier = modifier) { Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) { Text(label, color = MutedInk, style = MaterialTheme.typography.labelMedium); Text(value, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
}

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink, unfocusedBorderColor = Hairline, focusedContainerColor = Canvas,
    unfocusedContainerColor = Canvas, cursorColor = Accent
)

private fun String.isImageLike(): Boolean {
    val id = lowercase()
    return listOf("image", "dall", "flux", "stable-diffusion", "sdxl", "ideogram", "recraft").any(id::contains)
}












private fun String.isGpt56Model(): Boolean {
    val value = lowercase()
    return value.contains("gpt-5.6") || value.contains("gpt-5_6")
}





