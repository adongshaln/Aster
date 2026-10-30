package com.adong.adchat.ui.screens

import com.adong.adchat.BuildConfig
import com.adong.adchat.island.IslandPermissions
import com.adong.adchat.island.IslandService
import com.adong.adchat.island.IslandTouchService

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.API_FORMAT_ANTHROPIC
import com.adong.adchat.data.API_FORMAT_OPENAI
import com.adong.adchat.data.ApiModel
import com.adong.adchat.data.ApiProfile
import com.adong.adchat.data.DEFAULT_FONT_WEIGHT
import com.adong.adchat.data.FONT_WEIGHT_MAX
import com.adong.adchat.data.FONT_WEIGHT_MIN
import com.adong.adchat.data.THEME_MODE_ASTER
import com.adong.adchat.data.THEME_MODE_DARK
import com.adong.adchat.data.THEME_MODE_SYSTEM
import com.adong.adchat.data.IMAGE_API_MODE_AUTO
import com.adong.adchat.data.IMAGE_API_MODE_GEMINI
import com.adong.adchat.data.IMAGE_API_MODE_OPENAI
import com.adong.adchat.data.hasValidBaseUrl
import com.adong.adchat.data.invalidExtraHeaderLines
import com.adong.adchat.data.usesAnthropicMessages
import com.adong.adchat.data.usesResponses
import com.adong.adchat.data.resolveConversationRoute
import com.adong.adchat.data.normalized
import com.adong.adchat.data.isStepModel
import com.adong.adchat.data.OfficialModelPrices
import com.adong.adchat.data.ModelUsageTotal
import com.adong.adchat.data.aggregateConversationUsage
import com.adong.adchat.data.mergeUsageTotals
import com.adong.adchat.data.story.StoryUsageModelTotal
import com.adong.adchat.data.story.StoryUsageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.adong.adchat.ui.ConnectionPhase
import com.adong.adchat.ui.ConnectionUiState
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.components.AdActionOption
import com.adong.adchat.ui.components.AdActionSheet
import com.adong.adchat.ui.components.AdConfirmDialog
import com.adong.adchat.ui.components.UserNamePromptDialog
import com.adong.adchat.ui.components.UserNamePromptMode
import com.adong.adchat.ui.components.AdModalDialog
import com.adong.adchat.ui.components.AdToggleCard
import com.adong.adchat.ui.components.AdChoiceOption
import com.adong.adchat.ui.components.AdSelectionSheet
import com.adong.adchat.ui.components.RouteKind
import com.adong.adchat.ui.components.*
import com.adong.adchat.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onOpenDrawer: () -> Unit,
    openIslandSettings: Boolean = false,
    onIslandSettingsConsumed: () -> Unit = {}
) {
    var editing by remember { mutableStateOf<ApiProfile?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    val homeState = rememberSaveableStateHolder()
    LaunchedEffect(openIslandSettings) { if (openIslandSettings) editing = null }
    AnimatedContent(
        targetState = editing,
        contentKey = { it?.id },
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val forward = targetState != null
            (fadeIn(AsterSpring.editorial()) + slideInHorizontally(AsterSpring.spatial()) { if (forward) it / 10 else -it / 14 }) togetherWith
                (fadeOut(AsterSpring.editorial()) + slideOutHorizontally(AsterSpring.snappy()) { if (forward) -it / 14 else it / 10 })
        },
        label = "settings-editor"
    ) { profile ->
        if (profile != null) {
            ProfileEditor(
                initial = profile,
                isNew = editingIsNew,
                isActive = editing?.id == profile.id,
                models = vm.modelsFor(profile.id),
                state = vm.connectionFor(profile.id),
                searchBackendConfigured = vm.searchProfile.searchModel.isNotBlank(),
                keyNeedsReentry = profile.id in vm.profilesWithUndecryptableKeys,
                onBack = { vm.discardProfileDraft(profile.id); editing = null },
                onTest = { draft, onUpdated -> vm.testProfile(draft, onUpdated) },
                onSave = { vm.saveProfile(it); editing = null }
            )
        } else {
            homeState.SaveableStateProvider("settings-home") {
                SettingsHome(vm = vm, isActive = editing == null, onOpenDrawer = onOpenDrawer,
                    openIslandSettings = openIslandSettings, onIslandSettingsConsumed = onIslandSettingsConsumed,
                    onEdit = { editingIsNew = false; editing = it },
                    onCreate = { editingIsNew = true; editing = it })
            }
        }
    }
}

@Composable
private fun SettingsHome(
    vm: MainViewModel, isActive: Boolean, onOpenDrawer: () -> Unit,
    openIslandSettings: Boolean, onIslandSettingsConsumed: () -> Unit,
    onEdit: (ApiProfile) -> Unit, onCreate: (ApiProfile) -> Unit
) {
    var addMenu by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<ApiProfile?>(null) }
    var transferMode by remember { mutableStateOf<String?>(null) }
    var includeApiKeys by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.Home) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSkills by remember { mutableStateOf(false) }
    var storyUsageRows by remember { mutableStateOf<List<StoryUsageModelTotal>>(emptyList()) }
    var storyUsageLoaded by remember { mutableStateOf(false) }
    var storyUsageError by remember { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    LaunchedEffect(openIslandSettings, isActive) {
        if (openIslandSettings && isActive) {
            destination = SettingsDestination.Island
            onIslandSettingsConsumed()
        }
    }
    LaunchedEffect(destination) {
        if (destination == SettingsDestination.Usage) {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    StoryUsageStore(context.applicationContext).use { it.totalsByModel() }
                }
            }
            storyUsageRows = result.getOrDefault(emptyList())
            storyUsageError = result.isFailure
            storyUsageLoaded = true
        }
    }
    val usageRows = if (destination == SettingsDestination.Usage) {
        mergeUsageTotals(
            aggregateConversationUsage(vm.conversations),
            storyUsageRows.map { row ->
                ModelUsageTotal(
                    model = row.model.ifBlank { "模型未记录" }, requests = row.requests,
                    reportedRequests = row.reportedRequests, inputTokens = row.inputTokens,
                    cachedTokens = row.cachedTokens, cacheWriteTokens = row.cacheWriteTokens,
                    outputTokens = row.outputTokens
                )
            }
        )
    } else emptyList()
    fun navigate(target: SettingsDestination) {
        keyboard?.hide()
        if (target == SettingsDestination.Skills) showSkills = true else destination = target
    }
    BackHandler(isActive && destination != SettingsDestination.Home) { destination = destination.parent }
    if (showSkills) SkillLibrarySheet(null, onDismiss = { showSkills = false })
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (destination == SettingsDestination.Home) {
            AsterPageHeader("设置", onOpenDrawer, Modifier.padding(horizontal = 8.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsterIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回设置", { destination = destination.parent })
                Text(destination.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Ink)
            }
        }
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState.depth > initialState.depth
                (fadeIn(AsterSpring.editorial()) + slideInHorizontally(AsterSpring.spatial()) { if (forward) it / 10 else -it / 14 }) togetherWith
                    (fadeOut(AsterSpring.editorial()) + slideOutHorizontally(AsterSpring.snappy()) { if (forward) -it / 14 else it / 10 })
            },
            label = "settings-destination"
        ) { target ->
            pageStates.SaveableStateProvider(target.name) {
                LazyColumn(
                    Modifier.fillMaxSize().imePadding(),
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (target) {
                        SettingsDestination.Home -> {
                            item(key = "search") {
                                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                                    placeholder = { Text("搜索设置，如模型、外观、星岛") },
                                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清除搜索") } },
                                    singleLine = true, shape = RoundedCornerShape(20.dp), colors = editorFieldColors())
                            }
                            val matches = SettingsDestination.entries.filter { it != SettingsDestination.Home && it.matches(query) }
                            if (query.isNotBlank()) {
                                if (matches.isEmpty()) item { Text("没有匹配的设置，试试“模型”或“主题”。", color = MutedInk, modifier = Modifier.padding(12.dp)) }
                                items(matches, key = { it.name }) { item ->
                                    SettingsNavigationRow(item, settingsSummary(item, vm)) { navigate(item) }
                                }
                            } else {
                                item { SettingsGroupLabel("模型与连接") }
                                item { SettingsNavigationRow(SettingsDestination.Connections, settingsSummary(SettingsDestination.Connections, vm)) { navigate(SettingsDestination.Connections) } }
                                item { SettingsNavigationRow(SettingsDestination.Models, settingsSummary(SettingsDestination.Models, vm)) { navigate(SettingsDestination.Models) } }
                                item { SettingsGroupLabel("使用体验") }
                                item { SettingsNavigationRow(SettingsDestination.Appearance, settingsSummary(SettingsDestination.Appearance, vm)) { navigate(SettingsDestination.Appearance) } }
                                item { SettingsNavigationRow(SettingsDestination.Assistant, "称呼、角色与回答风格") { navigate(SettingsDestination.Assistant) } }
                                item { SettingsNavigationRow(SettingsDestination.Island, "文字处理、网页阅读与媒体下载") { navigate(SettingsDestination.Island) } }
                                item { SettingsNavigationRow(SettingsDestination.Skills, "安装与管理助手技能") { navigate(SettingsDestination.Skills) } }
                                item { SettingsGroupLabel("整理与用量") }
                                item { SettingsNavigationRow(SettingsDestination.Usage, settingsSummary(SettingsDestination.Usage, vm)) { navigate(SettingsDestination.Usage) } }
                                item { SettingsGroupLabel("应用管理") }
                                item { SettingsNavigationRow(SettingsDestination.Transfer, "导入、导出服务配置") { navigate(SettingsDestination.Transfer) } }
                                item { SettingsNavigationRow(SettingsDestination.Security, settingsSummary(SettingsDestination.Security, vm)) { navigate(SettingsDestination.Security) } }
                                item { Text("Aster ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(top = 12.dp),
                                    color = MutedInk, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                            }
                        }
                        SettingsDestination.Connections -> {
                            item { SettingsNavigationRow(SettingsDestination.Search, settingsSummary(SettingsDestination.Search, vm)) { navigate(SettingsDestination.Search) } }
                            item { SettingsNavigationRow(SettingsDestination.Suggestions, settingsSummary(SettingsDestination.Suggestions, vm)) { navigate(SettingsDestination.Suggestions) } }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("我的服务", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Ink)
                                    TextButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("添加") }
                                }
                            }
                            items(vm.profiles, key = { it.id }) { profile ->
                                ProfileCard(profile, vm.connectionFor(profile.id), vm.modelsFor(profile.id).size,
                                    profile.id == vm.defaultChatProfile.id, profile.id == vm.imageProfile.id,
                                    vm.profiles.size > 1, { vm.testProfile(profile) }, { onEdit(profile) },
                                    { vm.duplicateProfile(profile.id) }, { deleteCandidate = profile })
                            }
                        }
                        SettingsDestination.Models -> {
                            item { Text("默认对话模型用于新对话和星岛。已有对话的模型在对话页单独切换。", color = MutedInk, style = MaterialTheme.typography.bodyMedium) }
                            item { RouteAssignmentCard(RouteKind.Chat, vm.defaultChatProfile, vm.profiles,
                                vm.modelsFor(vm.defaultChatProfile.id), vm::selectDefaultChatProfile,
                                { vm.selectDefaultChatModel(vm.defaultChatProfile.id, it) }) }
                            item { RouteAssignmentCard(RouteKind.Image, vm.imageProfile, vm.profiles,
                                vm.modelsFor(vm.imageProfile.id), vm::selectImageProfile,
                                { vm.selectImageModel(vm.imageProfile.id, it) }) }
                        }
                        SettingsDestination.Search -> item {
                            SearchBackendCard(vm.searchProfile, vm.profiles, vm.modelsFor(vm.searchProfile.id), vm.allowXSearch,
                                vm::selectSearchProfile, { vm.selectSearchModel(vm.searchProfile.id, it) }, vm::setAllowXSearch)
                        }
                        SettingsDestination.Suggestions -> item {
                            InputSuggestionCard(vm.suggestionProfile, vm.profiles, vm.modelsFor(vm.suggestionProfile.id),
                                vm::selectSuggestionProfile, { vm.selectSuggestionModel(vm.suggestionProfile.id, it) })
                        }
                        SettingsDestination.Assistant -> item {
                            SettingsContentCard { AssistantSettings(vm) }
                        }
                        SettingsDestination.Appearance -> item {
                            SettingsContentCard { AppearanceSettings(vm) }
                        }
                        SettingsDestination.Island -> item {
                            SettingsContentCard { StarIslandSettingsCard() }
                        }
                        SettingsDestination.Usage -> {
                            item { UsageOverview(usageRows, storyUsageLoaded, storyUsageError) }
                            items(usageRows, key = { it.model }) { row ->
                                UsageModelRow(row)
                            }
                        }
                        SettingsDestination.Security -> item {
                            SettingsContentCard {
                                SettingSwitch("关闭证书校验", "允许自签名或不受信任的证书。仅在确认服务可信时开启。",
                                    vm.appConfig.allowInsecureCertificates, onCheckedChange = vm::setAllowInsecureCertificates)
                                Text(if (vm.appConfig.allowInsecureCertificates) "当前已关闭证书校验" else "当前使用正常的服务器证书校验", color = if (vm.appConfig.allowInsecureCertificates) Danger else MutedInk, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        SettingsDestination.Transfer -> {
                            item { Text("迁移服务地址、模型与接口参数。API Key 默认不包含在导出内容中。", color = MutedInk, style = MaterialTheme.typography.bodyMedium) }
                            item { SettingsActionRow("导入配置", "把 JSON 中的服务追加到当前配置", Icons.Rounded.FileUpload) { transferMode = "import"; importError = null } }
                            item { SettingsActionRow("导出配置", "备份当前服务，可选择是否包含密钥", Icons.Rounded.FileDownload) { transferMode = "export"; importError = null } }
                        }
                        SettingsDestination.Skills -> Unit // Opens the existing skill library directly.
                    }
                }
            }
        }
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
        // 删除会把历史对话改路由到另一个配置，这是用户该在点下去之前知道的事。
        val affected = vm.conversations.count { conversation ->
            resolveConversationRoute(conversation, vm.profiles, vm.appConfig.activeChatProfileId).profileId == profile.id
        }
        val fallback = vm.appConfig.profiles.firstOrNull { it.id != profile.id }
        AdConfirmDialog(
            title = "删除 ${profile.name}？",
            message = buildString {
                append("此操作会删除该 API 的 URL、Key、模型缓存，且无法撤销。")
                if (affected > 0 && fallback != null) {
                    append("其中 $affected 个历史对话正在使用这个 API，删除后会改用「${fallback.name}」· ${fallback.chatModel.ifBlank { "未选择模型" }} 继续。")
                }
            },
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

private enum class SettingsDestination(val title: String, val keywords: String = "", val depth: Int = 1) {
    Home("设置", depth = 0),
    Connections("服务与连接", "API 地址 URL Key 密钥 添加 编辑 连接 服务"),
    Models("默认模型", "默认 对话 绘图 图像 选择 模型"),
    Search("联网搜索", "联网 搜索 网页 X Search 后端", 2),
    Suggestions("输入建议", "输入 建议 补全 预测", 2),
    Appearance("外观与阅读", "主题 深色 浅色 系统 阅读 字重 字体"),
    Assistant("助手偏好", "称呼 姓名 角色 提示词 system prompt 风格"),
    Island("星岛", "星岛 悬浮窗 通知 翻译 润色 解释 网页 下载"),
    Skills("技能", "技能 Skill 安装 ZIP"),
    Usage("用量与成本", "用量 token 成本 价格 统计 模型 官方"),
    Transfer("备份与迁移", "导入 导出 备份 迁移 JSON"),
    Security("网络与安全", "安全 证书 SSL TLS 校验 自签名");

    val parent get() = if (this == Search || this == Suggestions) Connections else Home
    fun matches(query: String): Boolean = query.trim().split(Regex("\\s+"))
        .all { (title + " " + keywords).contains(it, ignoreCase = true) }
}

private fun settingsIcon(destination: SettingsDestination): ImageVector = when (destination) {
    SettingsDestination.Connections -> Icons.Rounded.Dns
    SettingsDestination.Models -> Icons.Rounded.Tune
    SettingsDestination.Search -> Icons.Rounded.TravelExplore
    SettingsDestination.Suggestions -> Icons.Rounded.AutoAwesome
    SettingsDestination.Appearance -> Icons.Rounded.Palette
    SettingsDestination.Assistant -> Icons.Rounded.EditNote
    SettingsDestination.Island -> Icons.Rounded.WorkspacePremium
    SettingsDestination.Skills -> Icons.Rounded.Extension
    SettingsDestination.Usage -> Icons.Rounded.DataUsage
    SettingsDestination.Transfer -> Icons.Rounded.ImportExport
    SettingsDestination.Security -> Icons.Rounded.Lock
    SettingsDestination.Home -> Icons.Rounded.Settings
}

private fun settingsSummary(destination: SettingsDestination, vm: MainViewModel): String = when (destination) {
    SettingsDestination.Connections -> "${vm.profiles.size} 个服务 · 联网搜索与输入建议"
    SettingsDestination.Models -> vm.defaultChatProfile.chatModel.ifBlank { "尚未选择默认对话模型" }
    SettingsDestination.Search -> vm.searchProfile.searchModel.ifBlank { "未配置独立搜索模型" }
    SettingsDestination.Suggestions -> vm.suggestionProfile.suggestionModel.ifBlank { "未配置输入建议模型" }
    SettingsDestination.Appearance -> when (vm.appConfig.themeMode) { THEME_MODE_DARK -> "深色"; THEME_MODE_SYSTEM -> "跟随系统"; else -> "浅色" } + " · 字重 ${vm.appConfig.fontWeight}"
    SettingsDestination.Security -> if (vm.appConfig.allowInsecureCertificates) "证书校验已关闭" else "正常校验服务器证书"
    SettingsDestination.Assistant -> "称呼、角色与回答风格"
    SettingsDestination.Island -> "文字处理、网页阅读与媒体下载"
    SettingsDestination.Skills -> "安装与管理助手技能"
    SettingsDestination.Usage -> {
        "按模型查看 Token 用量与官方价格估算"
    }
    SettingsDestination.Transfer -> "导入、导出服务配置"
    SettingsDestination.Home -> ""
}

@Composable
private fun UsageOverview(rows: List<ModelUsageTotal>, storyUsageLoaded: Boolean, storyUsageError: Boolean) {
    val estimates = rows.mapNotNull { OfficialModelPrices.estimate(it) }
    val low = estimates.sumOf { it.lowUsd }
    val high = estimates.sumOf { it.highUsd }
    val knownRequests = rows.sumOf { it.reportedRequests }
    val unknownRequests = rows.sumOf { it.unknownRequests }
    Surface(
        color = Surface,
        contentColor = Ink,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Hairline)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DataUsage, null, tint = Accent, modifier = Modifier.size(20.dp))
                Text("模型用量", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                Text("官方价格估算", color = MutedInk, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (estimates.isEmpty()) "暂无可计价用量" else formatUsageCost(low, high),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Accent
            )
            Text(
                "已统计 $knownRequests 次服务端报告用量${if (unknownRequests > 0) " · $unknownRequests 次用量未返回或未知" else ""}",
                color = MutedInk, style = MaterialTheme.typography.bodySmall
            )
            Text(
                "按模型当前官方标准价回算 USD；代理商账单、未知模型、长上下文、特殊档位和历史调价可能不同。价格数据核对于 ${OfficialModelPrices.CHECKED_ON}${if (storyUsageLoaded && !storyUsageError) "，包含故事调用" else ""}。",
                color = MutedInk, style = MaterialTheme.typography.bodySmall
            )
            if (storyUsageError) Text("故事用量暂时未能读取；上方统计仅包含普通对话。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UsageModelRow(row: ModelUsageTotal) {
    val estimate = OfficialModelPrices.estimate(row)
    val price = estimate?.price
    val uriHandler = LocalUriHandler.current
    val usageSummary = buildString {
        append("${row.requests} 条记录 · 输入 ${formatUsageTokens(row.inputTokens)}")
        if (row.cachedTokens > 0 || row.cacheWriteTokens > 0) {
            append("（缓存 ${formatUsageTokens(row.cachedTokens)} · 写入 ${formatUsageTokens(row.cacheWriteTokens)}）")
        }
        append(" · 输出 ${formatUsageTokens(row.outputTokens)} token")
        if (row.unknownRequests > 0) append(" · ${row.unknownRequests} 次用量未知")
    }
    Surface(
        color = Surface,
        contentColor = Ink,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Hairline)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(price?.name ?: row.model, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    estimate?.let { formatUsageCost(it.lowUsd, it.highUsd) } ?: "暂无官方价格",
                    color = if (estimate == null) MutedInk else Accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(usageSummary, color = MutedInk, style = MaterialTheme.typography.bodySmall)
            if (price != null) {
                Text(
                    "官方标准价：输入 ${formatRate(price.input)} / 输出 ${formatRate(price.output)} USD / 1M token",
                    color = MutedInk, style = MaterialTheme.typography.labelSmall
                )
                estimate?.notice?.let { Text(it, color = MutedInk, style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { runCatching { uriHandler.openUri(price.sourceUrl) } }, contentPadding = PaddingValues(0.dp)) {
                    Text("查看官方价格来源", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text("已保留 Token 统计；此模型未匹配内置官方价格目录。", color = MutedInk, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatUsageTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.2fM".format(java.util.Locale.US, tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fK".format(java.util.Locale.US, tokens / 1_000.0)
    else -> tokens.toString()
}

private fun formatUsageCost(low: Double, high: Double): String {
    fun value(amount: Double): String = if (amount in 0.0..<0.000001) "< $0.000001" else "$%.6f".format(java.util.Locale.US, amount)
    return if (kotlin.math.abs(high - low) < 0.0000005) value(low) else "${value(low)}–${value(high)}"
}

private fun formatRate(rate: com.adong.adchat.data.PriceRange): String {
    fun value(amount: Double) = "$${java.math.BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()}"
    return if (rate.high == rate.low) value(rate.low) else "${value(rate.low)}–${value(rate.high)}"
}

@Composable
private fun SettingsGroupLabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 8.dp), color = MutedInk, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SettingsNavigationRow(destination: SettingsDestination, summary: String, onClick: () -> Unit) =
    SettingsActionRow(destination.title, summary, settingsIcon(destination), onClick)

@Composable
private fun SettingsActionRow(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().appleSpringPressable(pressedScale = 0.985f),
        color = Surface, contentColor = Ink, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = OnAccentSoft)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, color = MutedInk, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MutedInk)
        }
    }
}

@Composable
private fun SettingsContentCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Surface, contentColor = Ink, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun ColumnScope.AssistantSettings(vm: MainViewModel) {
    var editingName by remember { mutableStateOf(false) }
    if (editingName) {
        UserNamePromptDialog(
            mode = UserNamePromptMode.Edit,
            currentName = vm.appConfig.userName,
            onConfirm = { vm.setUserName(it); editingName = false },
            onSkip = { editingName = false }
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("我的称呼", style = MaterialTheme.typography.titleSmall)
            Text(
                if (vm.appConfig.userName.isBlank()) "未设置；欢迎语会自然呈现" else "欢迎语可以使用「${vm.appConfig.userName}」",
                color = MutedInk, style = MaterialTheme.typography.bodySmall
            )
        }
        TextButton(onClick = { editingName = true }) {
            Text(if (vm.appConfig.userName.isBlank()) "设置" else "修改")
        }
    }
    Spacer(Modifier.height(14.dp))
    OutlinedTextField(
        value = vm.appConfig.systemPrompt, onValueChange = vm::updateSystemPrompt,
        modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8,
        placeholder = { Text("定义助手的角色与回答风格") },
        shape = RoundedCornerShape(15.dp), colors = editorFieldColors()
    )
    Text("修改自动保存，仅用于对话。", color = MutedInk, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun ColumnScope.AppearanceSettings(vm: MainViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ThemeModeCard("浅色", THEME_MODE_ASTER, AsterPaletteLight,
            vm.appConfig.themeMode, vm::setThemeMode, Modifier.weight(1f))
        ThemeModeCard("深色", THEME_MODE_DARK, AsterPaletteDark,
            vm.appConfig.themeMode, vm::setThemeMode, Modifier.weight(1f))
        ThemeModeCard("跟随系统", THEME_MODE_SYSTEM, null,
            vm.appConfig.themeMode, vm::setThemeMode, Modifier.weight(1f))
    }
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("阅读字重", style = MaterialTheme.typography.titleSmall)
            Text("拖动滑轨即时调整普通界面文字的粗细", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
        Text(vm.appConfig.fontWeight.toString(), style = MaterialTheme.typography.labelLarge, color = Accent)
    }
    Slider(
        value = vm.appConfig.fontWeight.toFloat(),
        onValueChange = { vm.setFontWeight(it.roundToInt(), save = false) },
        onValueChangeFinished = vm::commitFontWeight,
        valueRange = FONT_WEIGHT_MIN.toFloat()..FONT_WEIGHT_MAX.toFloat(),
        steps = FONT_WEIGHT_MAX - FONT_WEIGHT_MIN - 1,
        modifier = Modifier.fillMaxWidth().semantics {
            stateDescription = "阅读字重 ${vm.appConfig.fontWeight}"
        }
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("细", color = MutedInk, style = MaterialTheme.typography.labelSmall)
        Text("标准", color = MutedInk, style = MaterialTheme.typography.labelSmall)
        Text("粗", color = MutedInk, style = MaterialTheme.typography.labelSmall)
    }
    TextButton(
        onClick = { vm.setFontWeight(DEFAULT_FONT_WEIGHT) },
        modifier = Modifier.align(Alignment.End)
    ) { Text("恢复默认") }
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
        // 导入实际是追加，不是恢复：文案必须说清，否则用户以为迁移完路由却没生效。
        subtitle = if (exporting) "生成可跨设备迁移的 JSON" else "把 JSON 里的配置追加进来，不会覆盖现有配置",
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
                        contentColor = OnAccentSoft,
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
                colors = ButtonDefaults.buttonColors(containerColor = if (exporting) Ink else Accent, contentColor = if (exporting) OnInk else OnAccent)
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
    val accent = if (kind == RouteKind.Chat) OnAccentSoft else OnSageSoft
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
                    Text(if (kind == RouteKind.Chat) "用于新对话与星岛" else "用于图像创作与漫画翻译", color = MutedInk, style = MaterialTheme.typography.labelMedium)
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
private fun SearchBackendCard(
    profile: ApiProfile,
    profiles: List<ApiProfile>,
    models: List<ApiModel>,
    allowXSearch: Boolean,
    onProfile: (String) -> Unit,
    onModel: (String) -> Unit,
    onAllowX: (Boolean) -> Unit
) {
    var showProfileSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    val candidates = models.filterNot { it.id.isImageLike() }.sortedWith(
        compareByDescending<ApiModel> { it.id.contains("grok", ignoreCase = true) }
            .thenBy { it.id.lowercase() }
    )
    val displayModels = buildList {
        if (profile.searchModel.isNotBlank() && candidates.none { it.id == profile.searchModel }) add(ApiModel(profile.searchModel))
        addAll(candidates)
    }.distinctBy { it.id }

    Surface(color = Canvas, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Public, null, tint = Accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("委托搜索", style = MaterialTheme.typography.titleSmall)
                    Text("Chat 模型需要实时资料时，由此模型在后台检索", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
            }
            RouteValueButton("API", profile.name, Icons.Rounded.Dns, Accent) { showProfileSheet = true }
            RouteValueButton("搜索模型", profile.searchModel.ifBlank { "未选择" }, Icons.Rounded.TravelExplore, Accent) { showModelSheet = true }
            SettingSwitch(
                title = "允许 X Search",
                subtitle = if (profile.apiFormat == API_FORMAT_ANTHROPIC) "Anthropic 搜索后端只支持网页搜索" else "允许搜索模型检索公开 X 帖子；普通网页搜索始终可用",
                checked = allowXSearch && profile.apiFormat != API_FORMAT_ANTHROPIC,
                enabled = profile.apiFormat != API_FORMAT_ANTHROPIC,
                onCheckedChange = onAllowX
            )
            Text(
                when {
                    profile.searchModel.isStepModel() ->
                        "已配置 ${profile.searchModel}。Step 模型先调用 /v1/search，再经 Messages 整理笔记，不使用 Anthropic 的 web_search 工具。"
                    profile.apiFormat == API_FORMAT_ANTHROPIC && profile.searchModel.isNotBlank() ->
                        "已配置 ${profile.searchModel}。搜索走 Anthropic Messages 的 web_search，只发送独立查询。"
                    profile.searchModel.isBlank() ->
                        "选择 Responses 的 web_search，或把 API 格式设为 Anthropic 后选择 Claude。Chat 模型即可委托联网。"
                    else -> "已配置 ${profile.searchModel}。Aster 只向搜索后端发送模型生成的独立查询，不转发整段聊天记录。"
                },
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showProfileSheet) {
        AdSelectionSheet(
            title = "选择联网搜索 API",
            subtitle = if (profile.apiFormat == API_FORMAT_ANTHROPIC) "搜索模型通过该配置的 Anthropic Messages 路径调用" else "搜索模型通过该配置的 Responses 路径调用",
            options = profiles.map { item ->
                AdChoiceOption(item.id, item.name, item.baseUrl, Icons.Rounded.Dns, if (item.id == profile.id) "当前" else null)
            },
            selectedId = profile.id,
            onSelect = { onProfile(it.id); showProfileSheet = false },
            onDismiss = { showProfileSheet = false },
            searchPlaceholder = "搜索 API 配置",
            headerIcon = Icons.Rounded.TravelExplore
        )
    }
    if (showModelSheet) {
        AdSelectionSheet(
            title = "选择联网搜索模型",
            subtitle = if (displayModels.isEmpty()) "请先测试 API 并同步模型" else "Grok 模型优先显示，也可以选择其他兼容模型",
            options = displayModels.map { model ->
                AdChoiceOption(model.id, model.id, model.ownedBy.ifBlank { profile.name }, Icons.Rounded.TravelExplore,
                    if (model.id == profile.searchModel) "当前" else null)
            },
            selectedId = profile.searchModel,
            onSelect = { onModel(it.id); showModelSheet = false },
            onDismiss = { showModelSheet = false },
            searchPlaceholder = "搜索模型 ID",
            headerIcon = Icons.Rounded.TravelExplore
        )
    }
}

@Composable
private fun InputSuggestionCard(
    profile: ApiProfile,
    profiles: List<ApiProfile>,
    models: List<ApiModel>,
    onProfile: (String) -> Unit,
    onModel: (String) -> Unit
) {
    var showProfileSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    val candidates = models.filterNot { it.id.isImageLike() }.sortedWith(
        compareByDescending<ApiModel> { it.id.contains("grok", ignoreCase = true) }
            .thenBy { it.id.lowercase() }
    )
    val displayModels = buildList {
        if (profile.suggestionModel.isNotBlank() && candidates.none { it.id == profile.suggestionModel }) add(ApiModel(profile.suggestionModel))
        addAll(candidates)
    }.distinctBy { it.id }

    Surface(color = Canvas, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Accent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("输入建议", style = MaterialTheme.typography.titleSmall)
                    Text("有明确下一步时，在输入框内提供淡色建议", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
            }
            RouteValueButton("API", profile.name, Icons.Rounded.Dns, Accent) { showProfileSheet = true }
            RouteValueButton("建议模型", profile.suggestionModel.ifBlank { "已关闭" }, Icons.Rounded.AutoAwesome, Accent) { showModelSheet = true }
            Text(
                if (profile.suggestionModel.isBlank()) {
                    "选择一个响应快、成本低的模型。建议以淡色显示在空输入框中，点发送即可采纳，直接输入则忽略。也可以保持关闭。"
                } else {
                    "已配置 ${profile.suggestionModel}。思考强度自动使用该模型支持的最低档位；每次预测只携带最近两轮对话。"
                },
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showProfileSheet) {
        AdSelectionSheet(
            title = "选择输入建议 API",
            subtitle = "建议模型通过该配置的 API 路径调用",
            options = profiles.map { item ->
                AdChoiceOption(item.id, item.name, item.baseUrl, Icons.Rounded.Dns, if (item.id == profile.id) "当前" else null)
            },
            selectedId = profile.id,
            onSelect = { onProfile(it.id); showProfileSheet = false },
            onDismiss = { showProfileSheet = false },
            searchPlaceholder = "搜索 API 配置",
            headerIcon = Icons.Rounded.AutoAwesome
        )
    }
    if (showModelSheet) {
        AdSelectionSheet(
            title = "选择建议模型",
            subtitle = if (displayModels.isEmpty()) "请先测试 API 并同步模型" else "Grok 模型优先显示，也可以选择其他兼容模型",
            options = listOf(AdChoiceOption("", "关闭输入建议", "不再为输入建议发送模型请求", Icons.Rounded.DoNotDisturbOn)) + displayModels.map { model ->
                AdChoiceOption(model.id, model.id, model.ownedBy.ifBlank { profile.name }, Icons.Rounded.AutoAwesome,
                    if (model.id == profile.suggestionModel) "当前" else null)
            },
            selectedId = profile.suggestionModel,
            onSelect = { onModel(it.id); showModelSheet = false },
            onDismiss = { showModelSheet = false },
            searchPlaceholder = "搜索模型 ID",
            headerIcon = Icons.Rounded.AutoAwesome
        )
    }
}

@Composable
private fun RouteValueButton(label: String, value: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Canvas, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().appleSpringPressable(pressedScale = 0.98f)) {
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
    Surface(onClick = onEdit, color = Surface, contentColor = Ink,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().appleSpringPressable(pressedScale = 0.985f)) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(Canvas), contentAlignment = Alignment.Center) {
                if (state.phase == ConnectionPhase.Testing) AsterLoadingIndicator(size = 19.dp, tint = statusColor)
                else Icon(Icons.Rounded.Dns, null, tint = statusColor, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile.baseUrl, color = MutedInk, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.title + if (modelCount > 0) " · $modelCount 个模型" else "",
                    color = statusColor, style = MaterialTheme.typography.labelMedium)
                if (usedForChat || usedForImage) {
                    Text(listOfNotNull(if (usedForChat) "默认对话" else null, if (usedForImage) "默认绘图" else null).joinToString(" · "),
                        color = MutedInk, style = MaterialTheme.typography.labelSmall)
                }
                if (state.phase == ConnectionPhase.Error) {
                    Text(state.detail, color = Danger, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            AsterIconButton(Icons.Rounded.MoreHoriz, "更多", { showActions = true })
        }
    }
    if (showActions) {
        AdActionSheet(
            title = profile.name,
            subtitle = "管理这个 API 配置",
            actions = listOf(
                AdActionOption("edit", "编辑服务", "地址、密钥与模型参数", Icons.Rounded.Edit),
                AdActionOption("test", "测试并同步", "验证连接并刷新模型列表", Icons.Rounded.NetworkCheck, enabled = state.phase != ConnectionPhase.Testing),
                AdActionOption("duplicate", "复制配置", "保留当前参数创建副本", Icons.Rounded.ContentCopy),
                AdActionOption("delete", "删除配置", if (canDelete) "删除 URL、Key 和模型缓存" else "至少需要保留一个 API", Icons.Rounded.DeleteOutline, destructive = true, enabled = canDelete)
            ),
            onAction = { action ->
                showActions = false
                when (action.id) {
                    "edit" -> onEdit()
                    "test" -> onTest()
                    "duplicate" -> onDuplicate()
                    "delete" -> onDelete()
                }
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
        modifier = modifier.appleSpringPressable(pressedScale = 0.975f).semantics { this.selected = selected },
        color = if (selected) AccentSoft else Canvas,
        contentColor = if (selected) OnAccentSoft else Ink,
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
    isActive: Boolean,
    models: List<ApiModel>,
    state: ConnectionUiState,
    searchBackendConfigured: Boolean,
    keyNeedsReentry: Boolean,
    onBack: () -> Unit,
    onTest: (ApiProfile, (ApiProfile) -> Unit) -> Unit,
    onSave: (ApiProfile) -> Unit
) {
    var draft by remember { mutableStateOf(initial) }
    LaunchedEffect(initial) { draft = initial }
    var keyVisible by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var contextEditor by remember { mutableStateOf(false) }
    val normalizedDraft = draft.normalized()
    var testedConnection by remember { mutableStateOf(initial.normalized()) }
    val connectionChanged = !normalizedDraft.hasSameConnection(testedConnection)
    val availableModels = if (connectionChanged) emptyList() else models
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

    if (contextEditor) ModelContextDialog(draft.chatModel.trim(), draft.modelContexts[draft.chatModel.trim()],
        onDismiss = { contextEditor = false }, onApply = { limits ->
            val model = draft.chatModel.trim()
            draft = draft.copy(modelContexts = if (limits == null) draft.modelContexts - model else draft.modelContexts + (model to limits))
            contextEditor = false
        })
    BackHandler(isActive) { if (draft != initial) confirmDiscard = true else onBack() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (draft != initial) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(if (isNew) "添加 API" else "编辑 API", style = MaterialTheme.typography.titleLarge)
                Text("连接服务，选择默认模型", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            }
            AnimatedVisibility(draft != initial) {
                StatusBadge("未保存", AccentSoft, OnAccentSoft)
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
                            placeholder = { Text(if (keyNeedsReentry) "密钥已丢失，请重新填写" else "可留空用于本地服务") },
                            leadingIcon = { Icon(Icons.Outlined.Key, null) },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AnimatedVisibility(draft.apiKey.isNotEmpty()) {
                                        IconButton(onClick = { draft = draft.copy(apiKey = "", cachedModels = emptyList(), lastLatencyMs = null) }) {
                                            Icon(Icons.Outlined.Close, "全部删除")
                                        }
                                    }
                                    IconButton(onClick = { keyVisible = !keyVisible }) { Icon(if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (keyVisible) "隐藏密钥" else "显示密钥") }
                                }
                            },
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            singleLine = true, shape = RoundedCornerShape(15.dp), colors = editorFieldColors()
                        )
                        if (keyNeedsReentry) {
                            Text(
                                "此配置的密钥在重装应用或签名变化后已无法解密，原密钥无法恢复，请重新填写后保存。",
                                color = Danger, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text("接口格式", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ImageProtocolOption("OpenAI", draft.apiFormat != API_FORMAT_ANTHROPIC, Modifier.weight(1f)) {
                            draft = draft.copy(apiFormat = API_FORMAT_OPENAI, cachedModels = emptyList(), lastLatencyMs = null)
                        }
                        ImageProtocolOption("Anthropic", draft.apiFormat == API_FORMAT_ANTHROPIC, Modifier.weight(1f)) {
                            draft = draft.copy(apiFormat = API_FORMAT_ANTHROPIC, cachedModels = emptyList(), lastLatencyMs = null)
                        }
                    }
                    Text(
                        if (draft.apiFormat == API_FORMAT_ANTHROPIC) {
                            "非 GPT/Grok 模型使用 Messages；GPT/Grok 仍使用 Responses。"
                        } else {
                            "GPT/Grok 使用 Responses，其他模型使用 Chat Completions。"
                        },
                        color = MutedInk, style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                ConnectionEditorCard(
                    state = visibleState, canTest = testDisabledReason == null,
                    disabledReason = testDisabledReason,
                    onTest = {
                        val tested = draft.normalized()
                        testedConnection = tested
                        onTest(tested) { updated ->
                            // A slow connection test must not overwrite edits made while it ran.
                            if (draft.normalized().hasSameConnection(tested)) {
                                draft = draft.copy(
                                    cachedModels = updated.cachedModels,
                                    lastLatencyMs = updated.lastLatencyMs,
                                    chatModel = if (draft.chatModel == tested.chatModel) updated.chatModel else draft.chatModel,
                                    imageModel = if (draft.imageModel == tested.imageModel) updated.imageModel else draft.imageModel,
                                    mangaAnalysisModel = if (draft.mangaAnalysisModel == tested.mangaAnalysisModel) updated.mangaAnalysisModel else draft.mangaAnalysisModel
                                )
                            }
                        }
                    }
                )
            }
            item {
                EditorSection("默认模型", "切换到此 API 时，会恢复这里保存的模型。") {
                    Text(
                        when {
                            draft.usesResponses() -> "GPT / Grok · Responses"
                            draft.usesAnthropicMessages() -> "Claude 等 · Anthropic Messages"
                            else -> "其他模型 · Chat Completions"
                        },
                        color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    EditorModelField("对话模型", draft.chatModel, availableModels.filterNot { it.id.isImageLike() }.ifEmpty { availableModels }, { draft = draft.copy(chatModel = it) }, Icons.Outlined.Forum)
                    val limits = draft.modelContexts[draft.chatModel.trim()]
                    OutlinedButton(onClick = { contextEditor = true }, enabled = draft.chatModel.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(limits?.let { "上下文 ${it.windowTokens} · 输出 ${it.outputTokens} Token" } ?: "设置此模型的上下文长度")
                    }
                    Text("按模型独立保存，普通对话与故事共用。切换上方模型可编辑其他模型。", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    EditorModelField("绘图模型（可选）", draft.imageModel, availableModels.filter { it.id.isImageLike() }.ifEmpty { availableModels }, { draft = draft.copy(imageModel = it) }, Icons.Outlined.Palette)
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
                        } else if (searchBackendConfigured) {
                            "由 Aster 委托已配置的 Responses 搜索模型执行，可与 Skill / 创建文件共存"
                        } else {
                            "未配置委托后端时尝试 Chat Completions 的 web_search_options"
                        },
                        checked = draft.webSearchEnabled,
                        onCheckedChange = { enabled ->
                            draft = draft.copy(
                                webSearchEnabled = enabled,
                                fileCreationEnabled = if (enabled && !draft.usesResponses() && !searchBackendConfigured) false else draft.fileCreationEnabled
                            )
                        }
                    )
                    SettingSwitch(
                        title = "创建文件",
                        subtitle = "允许模型创建 PDF、Word、Excel、PowerPoint、HTML 和文本文件",
                        checked = draft.fileCreationEnabled,
                        onCheckedChange = { enabled ->
                            draft = draft.copy(
                                fileCreationEnabled = enabled,
                                webSearchEnabled = if (enabled && !draft.usesResponses() && !searchBackendConfigured) false else draft.webSearchEnabled
                            )
                        }
                    )
                    if (!draft.usesResponses()) {
                        Text(
                            if (searchBackendConfigured) "已配置 Aster 委托搜索：Chat 协议可同时使用联网、Skill 与创建文件。" else "未配置委托搜索时，Chat 原生联网与自定义文件工具仍按兼容模式互斥。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                SettingsDisclosure("高级设置", "绘图协议、接口路径与请求头", Icons.Rounded.Code) {
                    Text("GPT 与 Grok 模型固定使用 Responses API，其他模型使用 Chat Completions；对话框右上角可查看当前请求使用的协议。",
                        color = MutedInk, style = MaterialTheme.typography.bodySmall)
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
                    if (draft.apiFormat == API_FORMAT_ANTHROPIC) {
                        EditorField("Messages 路径", draft.messagesPath, { draft = draft.copy(messagesPath = it) }, "/v1/messages", Icons.Outlined.Forum)
                    }
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
            Button(
                onClick = onTest,
                enabled = canTest && state.phase != ConnectionPhase.Testing,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = OnInk)
            ) {
                if (state.phase == ConnectionPhase.Testing) AsterLoadingIndicator(size = 16.dp, tint = LocalContentColor.current)
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
    val arrow by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 180f else 0f, AsterSpring.jelly(), label = "settings-disclosure-arrow")
    Surface(
        color = Surface,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Hairline.copy(alpha = .75f)),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(Modifier.fillMaxWidth().appleSpringPressable(pressedScale = 0.985f).clickable { expanded = !expanded }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(22.dp), tint = Accent)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MutedInk)
                }
                Icon(Icons.Rounded.ExpandMore, if (expanded) "收起" else "展开",
                    Modifier.rotate(arrow), tint = MutedInk)
            }
            AnimatedVisibility(expanded,
                enter = fadeIn(AsterSpring.editorial()) + expandVertically(AsterSpring.spatial()),
                exit = fadeOut(AsterSpring.editorial()) + shrinkVertically(AsterSpring.snappy())) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
            }
        }
    }
}

@Composable
private fun StarIslandSettingsCard() {
    val context = LocalContext.current
    val night = LocalThemeDark.current
    val themeMode = LocalThemeMode.current
    // Re-read readiness whenever we come back from a permission settings page.
    var permissionTick by remember { mutableIntStateOf(0) }
    var enabled by remember(permissionTick) { mutableStateOf(IslandPermissions.isEnabled(context)) }
    val touchHost by IslandTouchService.connectedHost.collectAsState()
    val touchCompatibilityEnabled = remember(permissionTick, touchHost) {
        IslandPermissions.isTouchCompatibilityEnabled(context)
    }
    val overlayReady = remember(permissionTick) { Settings.canDrawOverlays(context) }
    val notificationsReady = remember(permissionTick) { IslandPermissions.hasNotifications(context) }
    val ready = overlayReady && notificationsReady
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationRequest = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> permissionTick++ }
    fun requestMissingPermission() {
        when {
            !overlayReady -> context.startActivity(IslandPermissions.overlaySettingsIntent(context))
            !notificationsReady && Build.VERSION.SDK_INT >= 33 -> {
                if (notificationRequested) {
                    context.startActivity(IslandPermissions.notificationSettingsIntent(context))
                } else {
                    notificationRequested = true
                    notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        StarIslandUsageDemo()
        SettingSwitch(
            title = "启用星岛",
            subtitle = "点按处理复制的文字或链接；长按框选屏幕，在星岛内快捷提问。",
            checked = enabled,
            onCheckedChange = { want ->
                if (want) {
                    enabled = true
                    IslandPermissions.setEnabled(context, true)
                    requestMissingPermission()
                } else {
                    enabled = false
                    IslandPermissions.setEnabled(context, false)
                    IslandService.stop(context)
                }
            }
        )
        // Returning from the settings page with permissions now granted: start the service.
        LaunchedEffect(enabled, ready, permissionTick, night, themeMode) {
            if (enabled && ready) IslandService.start(
                context, night, followSystem = themeMode == THEME_MODE_SYSTEM
            ) else IslandService.stop(context)
        }
        if (enabled && !ready) {
            TextButton(onClick = ::requestMissingPermission) {
                Text(if (!overlayReady) "授权悬浮窗" else "授权通知，完成开启")
            }
        }
        SettingSwitch(
            title = "触摸兼容与屏幕提问",
            subtitle = "修复墨线圈点按，并支持长按截屏框选（Android 11 起）。只有发送时上传选区；不持续读取屏幕，不执行自动点击。",
            checked = touchCompatibilityEnabled,
            onCheckedChange = { want ->
                if (!want && touchHost != null) {
                    touchHost?.disableSelf()
                } else {
                    context.startActivity(IslandPermissions.touchCompatibilitySettingsIntent())
                }
            }
        )
        if (touchCompatibilityEnabled && touchHost == null) {
            TextButton(onClick = { context.startActivity(IslandPermissions.touchCompatibilitySettingsIntent()) }) {
                Text("等待系统连接，检查无障碍服务")
            }
        } else if (!touchCompatibilityEnabled) {
            Text("在系统无障碍设置中开启「Aster · 星岛触摸兼容」后生效。",
                color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedInk)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("填写模型 ID") },
            leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = MutedInk) },
            trailingIcon = {
                if (models.isNotEmpty()) IconButton(onClick = { showSheet = true }) {
                    Icon(Icons.Rounded.UnfoldMore, "选择已同步模型", tint = OnAccentSoft)
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

/** 外观三选：卡内是各主题输入条的缩微模型，「跟随系统」并排画明暗两半。 */
@Composable
private fun ThemeModeCard(
    label: String,
    mode: String,
    palette: com.adong.adchat.ui.theme.AsterPalette?,
    currentMode: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelected = currentMode == mode
    val haptics = LocalHapticFeedback.current
    Column(modifier) {
        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onSelect(mode)
            },
            color = Canvas,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, if (isSelected) Accent else Hairline.copy(alpha = .55f)),
            modifier = Modifier.fillMaxWidth().appleSpringPressable(pressedScale = 0.975f, enableHaptics = false).semantics {
                selected = isSelected
                stateDescription = if (isSelected) "已选择" else "未选择"
            }
        ) {
            if (palette == null) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniComposer(AsterPaletteLight, Modifier.weight(1f))
                    MiniComposer(AsterPaletteDark, Modifier.weight(1f))
                }
            } else {
                MiniComposer(palette, Modifier.fillMaxWidth().padding(8.dp))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) Accent else MutedInk,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
        )
    }
}

@Composable
private fun MiniComposer(palette: com.adong.adchat.ui.theme.AsterPalette, modifier: Modifier = Modifier) {
    Column(modifier.height(72.dp).clip(RoundedCornerShape(10.dp)).background(palette.canvas).padding(5.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.fillMaxWidth(.65f).height(5.dp).clip(CircleShape).background(palette.mutedInk.copy(alpha = .4f)))
        Box(Modifier.fillMaxWidth(.85f).height(5.dp).clip(CircleShape).background(palette.hairline))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(palette.surface).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(palette.mutedInk.copy(alpha = .45f)))
            Box(Modifier.size(9.dp).clip(CircleShape).background(palette.accent))
        }
    }
}

private fun ApiProfile.hasSameConnection(other: ApiProfile): Boolean =
    baseUrl == other.baseUrl && apiKey == other.apiKey && modelsPath == other.modelsPath &&
        extraHeaders == other.extraHeaders && apiFormat == other.apiFormat
