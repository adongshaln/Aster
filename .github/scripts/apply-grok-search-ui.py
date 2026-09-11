from pathlib import Path

ROOT = Path('.')

def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'anchor not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

# MainViewModel: allow Chat search + create_file together when delegated backend exists.
path = 'app/src/main/java/com/adong/adchat/ui/MainViewModel.kt'
replace_once(path,
'''    fun setChatWebSearchEnabled(enabled: Boolean) {
        val profile = chatProfile
        updateProfile(profile.id) {
            it.copy(
                webSearchEnabled = enabled,
                fileCreationEnabled = if (enabled && !profile.usesResponses()) false else it.fileCreationEnabled
            )
        }
        persist()
    }

    fun setChatFileCreationEnabled(enabled: Boolean) {
        val profile = chatProfile
        updateProfile(profile.id) {
            it.copy(
                fileCreationEnabled = enabled,
                webSearchEnabled = if (enabled && !profile.usesResponses()) false else it.webSearchEnabled
            )
        }
        persist()
    }''',
'''    fun setChatWebSearchEnabled(enabled: Boolean) {
        val profile = chatProfile
        val delegated = !profile.usesResponses() && searchBackendConfig() != null
        updateProfile(profile.id) {
            it.copy(
                webSearchEnabled = enabled,
                fileCreationEnabled = if (enabled && !profile.usesResponses() && !delegated) false else it.fileCreationEnabled
            )
        }
        persist()
    }

    fun setChatFileCreationEnabled(enabled: Boolean) {
        val profile = chatProfile
        val delegated = !profile.usesResponses() && searchBackendConfig() != null
        updateProfile(profile.id) {
            it.copy(
                fileCreationEnabled = enabled,
                webSearchEnabled = if (enabled && !profile.usesResponses() && !delegated) false else it.webSearchEnabled
            )
        }
        persist()
    }''')

# SettingsScreen: pass backend status into editor.
path = 'app/src/main/java/com/adong/adchat/ui/screens/SettingsScreen.kt'
replace_once(path,
'''            state = vm.connectionFor(editing!!.id),
            onBack = {''',
'''            state = vm.connectionFor(editing!!.id),
            searchBackendConfigured = vm.searchProfile.searchModel.isNotBlank(),
            onBack = {''')
replace_once(path,
'''    models: List<ApiModel>,
    state: ConnectionUiState,
    onBack: () -> Unit,''',
'''    models: List<ApiModel>,
    state: ConnectionUiState,
    searchBackendConfigured: Boolean,
    onBack: () -> Unit,''')

# Add global search backend card under default model routes.
replace_once(path,
'''            item {
                SettingsDisclosure("助手偏好", "角色、语气与回答习惯", Icons.Rounded.EditNote) {''',
'''            item {
                SettingsDisclosure(
                    "联网搜索后端",
                    vm.searchProfile.searchModel.takeIf(String::isNotBlank)?.let { "${vm.searchProfile.name} · $it" } ?: "未配置 · Chat 模型将使用自身兼容能力",
                    Icons.Rounded.TravelExplore
                ) {
                    SearchBackendCard(
                        profile = vm.searchProfile,
                        profiles = vm.profiles,
                        models = vm.modelsFor(vm.searchProfile.id),
                        allowXSearch = vm.allowXSearch,
                        onProfile = vm::selectSearchProfile,
                        onModel = { vm.selectSearchModel(vm.searchProfile.id, it) },
                        onAllowX = vm::setAllowXSearch
                    )
                }
            }
            item {
                SettingsDisclosure("助手偏好", "角色、语气与回答习惯", Icons.Rounded.EditNote) {''')

# Search backend composable before RouteValueButton.
replace_once(path,
'''@Composable
private fun RouteValueButton(label: String, value: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {''',
'''@Composable
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
                    Text("Chat 模型需要实时资料时，由此 Responses 模型在后台检索", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
            }
            RouteValueButton("API", profile.name, Icons.Rounded.Dns, Accent) { showProfileSheet = true }
            RouteValueButton("搜索模型", profile.searchModel.ifBlank { "未选择" }, Icons.Rounded.TravelExplore, Accent) { showModelSheet = true }
            SettingSwitch(
                title = "允许 X Search",
                subtitle = "允许搜索模型检索公开 X 帖子；普通网页搜索始终可用",
                checked = allowXSearch,
                onCheckedChange = onAllowX
            )
            Text(
                if (profile.searchModel.isBlank()) {
                    "选择支持 Responses + web_search 的模型后，Gemini、Claude 等 Chat 模型即可通过 Aster 委托联网。"
                } else {
                    "已配置 ${profile.searchModel}。Aster 只向搜索后端发送模型生成的独立查询，不转发整段聊天记录。"
                },
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showProfileSheet) {
        AdSelectionSheet(
            title = "选择联网搜索 API",
            subtitle = "搜索模型固定通过该配置的 Responses 路径调用",
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
private fun RouteValueButton(label: String, value: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {''')

# Profile tool switches understand delegated backend coexistence.
replace_once(path,
'''                        subtitle = if (draft.usesResponses()) {
                            "使用 Responses API 的 web_search 工具"
                        } else {
                            "通过 Chat Completions 的 web_search_options，需模型与网关支持"
                        },''',
'''                        subtitle = if (draft.usesResponses()) {
                            "使用 Responses API 的 web_search 工具"
                        } else if (searchBackendConfigured) {
                            "由 Aster 委托已配置的 Responses 搜索模型执行，可与 Skill / 创建文件共存"
                        } else {
                            "未配置委托后端时尝试 Chat Completions 的 web_search_options"
                        },''')
replace_once(path,
'''                                webSearchEnabled = enabled,
                                fileCreationEnabled = if (enabled && !draft.usesResponses()) false else draft.fileCreationEnabled''',
'''                                webSearchEnabled = enabled,
                                fileCreationEnabled = if (enabled && !draft.usesResponses() && !searchBackendConfigured) false else draft.fileCreationEnabled''')
replace_once(path,
'''                                fileCreationEnabled = enabled,
                                webSearchEnabled = if (enabled && !draft.usesResponses()) false else draft.webSearchEnabled''',
'''                                fileCreationEnabled = enabled,
                                webSearchEnabled = if (enabled && !draft.usesResponses() && !searchBackendConfigured) false else draft.webSearchEnabled''')
replace_once(path,
'''                    if (!draft.usesResponses()) {
                        Text(
                            "Chat 协议下联网搜索与自定义文件工具互斥；Responses 协议可同时使用。",''',
'''                    if (!draft.usesResponses()) {
                        Text(
                            if (searchBackendConfigured) "已配置 Aster 委托搜索：Chat 协议可同时使用联网、Skill 与创建文件。" else "未配置委托搜索时，Chat 原生联网与自定义文件工具仍按兼容模式互斥。",''')

print('Applied delegated search settings UI migration')
