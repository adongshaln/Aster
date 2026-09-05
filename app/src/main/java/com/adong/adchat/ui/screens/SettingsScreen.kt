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
import com.adong.adchat.data.isGptModel
import com.adong.adchat.data.usesResponses
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
import com.adong.adchat.ui.components.*
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
    var showTransferActions by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<ApiProfile?>(null) }
    var transferMode by remember { mutableStateOf<String?>(null) }
    var includeApiKeys by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AsterPageHeader("设置", onOpenDrawer, Modifier.padding(horizontal = 8.dp)) {
            AsterIconButton(Icons.Rounded.MoreHoriz, "配置管理", { showTransferActions = true })
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("我的服务", style = MaterialTheme.typography.titleLarge, color = Ink)
                        Text("连接 API，开始使用你的模型", style = MaterialTheme.typography.bodySmall, color = MutedInk)
                    }
                    TextButton(onClick = { addMenu = true }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                }
            }
            items(vm.profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile, vm.connectionFor(profile.id), vm.modelsFor(profile.id).size,
                    profile.id == vm.chatProfile.id, profile.id == vm.imageProfile.id,
                    vm.profiles.size > 1, { vm.testProfile(profile) }, { onEdit(profile) },
                    { vm.duplicateProfile(profile.id) }, { deleteCandidate = profile }
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                SettingsDisclosure("默认模型", "对话 · " + vm.chatProfile.chatModel.ifBlank { "未选择" }, Icons.Rounded.Tune) {
                    RouteAssignmentCard(RouteKind.Chat, vm.chatProfile, vm.profiles,
                        vm.modelsFor(vm.chatProfile.id), vm::selectChatProfile,
                        { vm.selectChatModel(vm.chatProfile.id, it) })
                    RouteAssignmentCard(RouteKind.Image, vm.imageProfile, vm.profiles,
                        vm.modelsFor(vm.imageProfile.id), vm::selectImageProfile,
                        { vm.selectImageModel(vm.imageProfile.id, it) })
                }
            }
            item {
                SettingsDisclosure("助手偏好", "角色、语气与回答习惯", Icons.Rounded.EditNote) {
                    OutlinedTextField(
                        value = vm.appConfig.systemPrompt, onValueChange = vm::updateSystemPrompt,
                        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
                        placeholder = { Text("定义助手的角色与回答风格") },
                        shape = RoundedCornerShape(15.dp), colors = editorFieldColors()
                    )
                    Text("修改自动保存，仅用于对话。", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                Text("Aster ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 20.dp),
                    color = MutedInk, style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
    if (showTransferActions) {
        AdActionSheet(title = "配置管理", subtitle = "在设备之间迁移你的服务配置",
            actions = listOf(
                AdActionOption("import", "导入配置", "从 JSON 恢复服务与模型", Icons.Rounded.FileUpload),
                AdActionOption("export", "导出配置", "备份当前的服务配置", Icons.Rounded.FileDownload)
            ), onAction = { showTransferActions = false; transferMode = it.id; importError = null },
            onDismiss = { showTransferActions = false })
    }
    if (addMenu) {
        AdActionSheet(
            title = "添加 API 配置",
            subtitle = "选择一个起点，之后仍可修改所有字段",
            actions = listOf(
                AdActionOption("blank", "空白配置", "从 URL、Key 和模型开始填写", Icons.Rounded.AddCircleOutline),
                AdActionOption("openai", "OpenAI / GPT", "GPT 模型统一使用 Responses", Icons.Rounded.Cloud),
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
        subtitle = if (exporting) "生成可跨设备迁移的 JSON" else "从 Aster JSON 恢复路由与模型",
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
                    Text("粘贴 Aster 导出的 JSON", modifier = Modifier.weight(1f), color = MutedInk, style = MaterialTheme.typography.bodyMedium)
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
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Aster API Profiles", exportJson))
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

    Surface(color = Surface, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Hairline)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(soft), contentAlignment = Alignment.Center) {
                    Icon(if (kind == RouteKind.Chat) Icons.Rounded.Forum else Icons.Rounded.Palette, null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (kind == RouteKind.Chat) "对话模型" else "图像模型", style = MaterialTheme.typography.titleMedium)
                    Text(if (kind == RouteKind.Chat) "用于当前这段对话" else "用于图像创作与漫画翻译", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                }
                Surface(color = soft, contentColor = accent, shape = CircleShape) {
                    Text(if (kind == RouteKind.Chat) "对话" else "创作", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
    Surface(color = Surface, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Hairline)) {
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
            Text(state.title + if (modelCount > 0) " · $modelCount 个模型" else "",
                color = statusColor, style = MaterialTheme.typography.labelMedium)
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
                TextButton(onClick = onTest, enabled = state.phase != ConnectionPhase.Testing, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
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
                Text("连接服务，选择默认模型", color = MutedInk, style = MaterialTheme.typography.labelMedium)
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
            Modifier.weight(1f).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                EditorSection("连接信息", "填写服务商提供的地址和密钥") {
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
                            trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) { Icon(if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (keyVisible) "隐藏密钥" else "显示密钥") } },
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true, shape = RoundedCornerShape(15.dp), colors = editorFieldColors()
                        )
                    }
                }
            }
            item {
                ConnectionEditorCard(
                    state = visibleState, canTest = testDisabledReason == null,
                    disabledReason = testDisabledReason,
                    onTest = { onTest(normalizedDraft) { updated -> draft = updated } }
                )
            }
            item {
                EditorSection("默认模型", "切换到此 API 时，会恢复这里保存的模型。") {
                    Text(if (draft.chatModel.isGptModel()) "GPT · Responses" else "GPT 自动使用 Responses；其他模型可在高级设置中选择协议",
                        color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    EditorModelField("对话模型", draft.chatModel, models.filterNot { it.id.isImageLike() }.ifEmpty { models }, { draft = draft.copy(chatModel = it) }, Icons.Outlined.Forum)
                    EditorModelField("绘图模型（可选）", draft.imageModel, models.filter { it.id.isImageLike() }.ifEmpty { models }, { draft = draft.copy(imageModel = it) }, Icons.Outlined.Palette)
                }
            }
            item {
                SettingsDisclosure("连接与恢复", "断线续传与缓存兼容", Icons.Rounded.NetworkCheck) {
                    SettingSwitch(
                        title = "流式安全续传",
                        subtitle = "中途断线时最多自动续传一次；基于已生成内容继续，不会从头重跑",
                        checked = draft.autoResumeStream,
                        onCheckedChange = { draft = draft.copy(autoResumeStream = it) }
                    )
                    SettingSwitch("缓存兼容参数", "发送缓存标识；不兼容的网关可关闭，命中由服务端决定",
                        draft.promptCacheEnabled) { draft = draft.copy(promptCacheEnabled = it) }
                    Text(
                        "仅对连接重置、提前断流和瞬时网络错误生效。认证失败、额度不足及用户主动停止不会自动重试。",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                SettingsDisclosure("工具默认值", "联网搜索、创建文件；聊天中也可随时调整", Icons.Rounded.Build) {
                    SettingSwitch(
                        title = "联网搜索",
                        subtitle = if (draft.usesResponses()) {
                            "使用 Responses API 的 web_search 工具"
                        } else {
                            "通过 Chat Completions 的 web_search_options，需模型与网关支持"
                        },
                        checked = draft.webSearchEnabled,
                        onCheckedChange = { enabled ->
                            draft = draft.copy(
                                webSearchEnabled = enabled,
                                fileCreationEnabled = if (enabled && !draft.usesResponses()) false else draft.fileCreationEnabled
                            )
                        }
                    )
                    SettingSwitch(
                        title = "创建文件",
                        subtitle = "允许模型创建可下载的 Markdown、文本、JSON 或 CSV 文件",
                        checked = draft.fileCreationEnabled,
                        onCheckedChange = { enabled ->
                            draft = draft.copy(
                                fileCreationEnabled = enabled,
                                webSearchEnabled = if (enabled && !draft.usesResponses()) false else draft.webSearchEnabled
                            )
                        }
                    )
                    if (!draft.usesResponses()) {
                        Text(
                            "Chat 协议下联网搜索与自定义文件工具互斥；Responses 协议可同时使用。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                SettingsDisclosure("高级设置", "协议、接口路径与请求头", Icons.Rounded.Code) {
                    if (draft.chatModel.isGptModel()) {
                        Text("GPT 模型固定使用 Responses，不自动切换协议。", color = MutedInk,
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("对话协议", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ImageProtocolOption("Chat Completions", !draft.usesResponses(), Modifier.weight(1f)) {
                                draft = draft.copy(chatApiMode = "chat",
                                    fileCreationEnabled = if (draft.webSearchEnabled) false else draft.fileCreationEnabled)
                            }
                            ImageProtocolOption("Responses", draft.usesResponses(), Modifier.weight(1f)) {
                                draft = draft.copy(chatApiMode = "responses")
                            }
                        }
                    }
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
                                "自动识别 Gemini 图片模型；NAI Diffusion 等模型使用 OpenAI Images 兼容协议。"
                            },
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    EditorField("模型列表路径", draft.modelsPath, { draft = draft.copy(modelsPath = it, cachedModels = emptyList(), lastLatencyMs = null) }, "/v1/models", Icons.AutoMirrored.Outlined.List)
                    EditorField("Chat / Gemini 路径", draft.chatPath, { draft = draft.copy(chatPath = it) }, "/v1/chat/completions", Icons.AutoMirrored.Outlined.Chat)
                    EditorField("Responses 路径", draft.responsesPath, { draft = draft.copy(responsesPath = it) }, "/v1/responses", Icons.Outlined.Bolt)
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
                        minLines = 3
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
private fun ConnectionEditorCard(
    state: ConnectionUiState,
    canTest: Boolean,
    disabledReason: String?,
    onTest: () -> Unit
) {
    val tone = if (state.phase == ConnectionPhase.Error) Danger else MutedInk
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title, color = tone, style = MaterialTheme.typography.labelLarge)
                Text(disabledReason ?: state.detail, color = tone, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onTest, enabled = canTest && state.phase != ConnectionPhase.Testing) {
                if (state.phase == ConnectionPhase.Testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("测试并同步")
            }
        }
    }
}

@Composable
private fun SettingsDisclosure(
    title: String, summary: String, icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = Surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(22.dp), tint = Accent)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MutedInk)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "收起" else "展开", tint = MutedInk)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun EditorSection(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(description, color = MutedInk, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(10.dp)); Surface(color = Surface, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(15.dp), content = content) } }
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
private fun SectionTitle(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(6.dp))
    Text(description, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun StatusBadge(text: String, background: Color, content: Color) {
    Surface(color = background, contentColor = content, shape = CircleShape) { Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun editorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink, unfocusedBorderColor = Hairline, focusedContainerColor = Canvas,
    unfocusedContainerColor = Canvas, cursorColor = Accent
)

private fun String.isImageLike(): Boolean {
    val id = lowercase()
    return listOf(
        "image",
        "dall",
        "flux",
        "stable-diffusion",
        "sdxl",
        "ideogram",
        "recraft",
        "nai",
        "novelai",
        "diffusion"
    ).any(id::contains)
}
