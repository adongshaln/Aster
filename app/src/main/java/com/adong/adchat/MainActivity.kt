package com.adong.adchat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import com.adong.adchat.data.Conversation
import com.adong.adchat.data.THEME_MODE_SYSTEM
import com.adong.adchat.island.IslandService
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.components.*
import com.adong.adchat.ui.components.UserNamePromptDialog
import com.adong.adchat.ui.components.UserNamePromptMode
import com.adong.adchat.ui.components.AdActionOption
import com.adong.adchat.ui.components.AdActionSheet
import com.adong.adchat.ui.components.AdConfirmDialog
import com.adong.adchat.ui.components.AdModalDialog
import com.adong.adchat.ui.screens.ChatScreen
import com.adong.adchat.ui.screens.DrawScreen
import com.adong.adchat.ui.screens.MediaDownloadScreen
import com.adong.adchat.ui.screens.SettingsScreen
import com.adong.adchat.ui.screens.StoryScreen
import com.adong.adchat.ui.media.MediaDownloadViewModel
import com.adong.adchat.ui.rp.RpScreen
import com.adong.adchat.ui.rp.RpViewModel
import com.adong.adchat.ui.story.StoryViewModel
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingMediaShare by mutableStateOf<String?>(null)
    private var pendingIslandSettings by mutableStateOf(false)
    private var pendingIslandChatHandoff by mutableStateOf<Triple<String, String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingMediaShare = extractSharedMediaText(intent)
        pendingIslandSettings = intent?.action == IslandService.ACTION_OPEN_SETTINGS
        pendingIslandChatHandoff = extractIslandChatHandoff(intent)
        enableEdgeToEdge()
        if (com.adong.adchat.island.IslandPermissions.isEnabled(this) &&
            com.adong.adchat.island.IslandPermissions.isReady(this)
        ) com.adong.adchat.island.IslandService.start(this)
        setContent {
            val themeMode = viewModel.appConfig.themeMode
            val dark = com.adong.adchat.ui.theme.themeIsDark(themeMode)
            // Re-sync when the mode changes even if its current effective colour stays the same
            // (for example, fixed dark -> system while the device is already dark). The island
            // keeps an explicit override, so it must receive the mode transition itself.
            LaunchedEffect(dark, themeMode) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                                     else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                                         else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                )
                IslandService.syncTheme(
                    this@MainActivity,
                    dark,
                    followSystem = themeMode == THEME_MODE_SYSTEM
                )
            }
            AsterTheme(themeMode = themeMode) {
                CompositionLocalProvider(LocalAsterMotionEnabled provides rememberAsterMotionEnabled()) {
                    AsterApp(
                        vm = viewModel,
                        incomingMediaText = pendingMediaShare,
                        onMediaTextConsumed = { pendingMediaShare = null },
                        openIslandSettings = pendingIslandSettings,
                        onIslandSettingsConsumed = {
                            pendingIslandSettings = false
                            if (intent?.action == IslandService.ACTION_OPEN_SETTINGS) intent.action = null
                        },
                        islandChatHandoff = pendingIslandChatHandoff,
                        onIslandChatHandoffConsumed = {
                            pendingIslandChatHandoff = null
                            if (intent?.action == IslandService.ACTION_CONTINUE_IN_CHAT) intent.action = null
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMediaShare = extractSharedMediaText(intent)
        pendingIslandSettings = intent.action == IslandService.ACTION_OPEN_SETTINGS
        pendingIslandChatHandoff = extractIslandChatHandoff(intent)
    }

    private fun extractSharedMediaText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }

    private fun extractIslandChatHandoff(intent: Intent?): Triple<String, String, String>? {
        if (intent?.action != IslandService.ACTION_CONTINUE_IN_CHAT) return null
        val source = intent.getStringExtra(IslandService.EXTRA_CHAT_SOURCE).orEmpty().trim()
        if (source.isBlank()) return null
        val result = intent.getStringExtra(IslandService.EXTRA_CHAT_RESULT).orEmpty().trim()
        val actionLabel = intent.getStringExtra(IslandService.EXTRA_CHAT_ACTION).orEmpty().trim()
        return Triple(source, result, actionLabel)
    }
}

internal enum class AppPage(val label: String, val icon: ImageVector) {
    Chat("对话", Icons.Rounded.Forum),
    Rp("剧情", Icons.Rounded.TheaterComedy),
    Story("故事", Icons.Rounded.AutoStories),
    Draw("创作", Icons.Rounded.AutoAwesome),
    Media("下载", Icons.Rounded.DownloadForOffline),
    Settings("设置", Icons.Rounded.Tune)
}

@Composable
private fun AsterApp(
    vm: MainViewModel,
    incomingMediaText: String?,
    onMediaTextConsumed: () -> Unit,
    openIslandSettings: Boolean,
    onIslandSettingsConsumed: () -> Unit,
    islandChatHandoff: Triple<String, String, String>? = null,
    onIslandChatHandoffConsumed: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(AppPage.Chat) }
    if (!vm.appConfig.userNamePromptDone) {
        UserNamePromptDialog(
            mode = UserNamePromptMode.Onboarding,
            currentName = vm.appConfig.userName,
            onConfirm = vm::setUserName,
            onSkip = vm::skipUserNamePrompt
        )
    }
    val pageStates = rememberSaveableStateHolder()
    val mediaVm: MediaDownloadViewModel = viewModel()
    val storyVm: StoryViewModel = viewModel()
    val rpVm: RpViewModel = viewModel()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showStoryCreator by rememberSaveable { mutableStateOf(false) }
    var storyTitle by rememberSaveable { mutableStateOf("") }
    val openDrawer = { focusManager.clearFocus(); scope.launch { drawerState.open() }; Unit }
    fun navigate(target: AppPage) {
        focusManager.clearFocus()
        page = target
        scope.launch { drawerState.close() }
    }
    fun requestStoryCreation() {
        storyTitle = ""
        showStoryCreator = true
        scope.launch { drawerState.close() }
    }
    val cardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            showStoryCreator = false
            storyVm.importCharacterCard(uri, vm.chatProfile) { page = AppPage.Story }
        }
    }
    LaunchedEffect(storyVm.cardImportError) {
        val message = storyVm.cardImportError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        storyVm.clearCardImportError()
    }

    BackHandler(enabled = page != AppPage.Chat && drawerState.isClosed) { navigate(AppPage.Chat) }

    LaunchedEffect(incomingMediaText) {
        incomingMediaText?.takeIf { it.isNotBlank() }?.let { sharedText ->
            mediaVm.acceptSharedText(sharedText)
            page = AppPage.Media
            onMediaTextConsumed()
        }
    }

    LaunchedEffect(openIslandSettings) {
        if (openIslandSettings) {
            navigate(AppPage.Settings)
        }
    }

    LaunchedEffect(islandChatHandoff) {
        val handoff = islandChatHandoff ?: return@LaunchedEffect
        vm.startConversationFromStarIsland(handoff.first, handoff.second, handoff.third)
        navigate(AppPage.Chat)
        onIslandChatHandoffConsumed()
    }

    LaunchedEffect(vm.notice) {
        val message = vm.notice ?: return@LaunchedEffect
        // 删除会话走长时长 + 撤销动作；其余提示保持短时长。
        val action = if (message == "已删除对话" && vm.recentlyDeletedConversation != null) "撤销" else null
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = action,
            duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDeleteConversation()
        if (vm.notice == message) vm.dismissNotice()
    }

    LaunchedEffect(vm.keyReentryWarning) {
        val message = vm.keyReentryWarning ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
        vm.consumeKeyReentryWarning()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Opening by swipe anywhere on screen is too trigger-happy; a narrow
        // edge strip below gates it instead, closing keeps the built-in gesture.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            AppDrawer(
                vm = vm,
                currentPage = page,
                onNewChat = { vm.newConversation(); navigate(AppPage.Chat) },
                onNewStory = ::requestStoryCreation,
                onConversation = { vm.selectConversation(it); navigate(AppPage.Chat) },
                onNavigate = ::navigate,
                onClose = { focusManager.clearFocus(); scope.launch { drawerState.close() } },
                history = if (page == AppPage.Rp) DrawerHistory.rp(rpVm, onSelected = {
                    scope.launch { drawerState.close() }
                }) else DrawerHistory.chat(vm)
            )
        }
    ) {
        Scaffold(containerColor = Canvas) { padding ->
            val edgeToEdgePage = page == AppPage.Chat || page == AppPage.Story || page == AppPage.Rp
            val pageBottomPadding = if (edgeToEdgePage) 0.dp else padding.calculateBottomPadding()
            val drawerOpen: () -> Unit = openDrawer
            Box(
                Modifier.fillMaxSize().padding(bottom = pageBottomPadding)
                    .consumeWindowInsets(PaddingValues(bottom = pageBottomPadding))
                    .background(Brush.verticalGradient(listOf(WarmWhite, Canvas, Canvas)))
                    .drawerEdgeSwipe(drawerOpen),
                contentAlignment = Alignment.TopCenter
            ) {
                val motionEnabled = LocalAsterMotionEnabled.current
                val pageTravelPx = with(LocalDensity.current) { 18.dp.roundToPx() }
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier.widthIn(max = 900.dp).fillMaxSize(),
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        val enter = if (motionEnabled) {
                            fadeIn(tween(220, delayMillis = 40, easing = AsterEase)) +
                                slideInHorizontally(tween(260, easing = AsterEase)) { width ->
                                    minOf(width / 14, pageTravelPx) * direction
                                }
                        } else EnterTransition.None
                        val exit = if (motionEnabled) {
                            fadeOut(tween(100, easing = AsterEase)) +
                                slideOutHorizontally(tween(140, easing = AsterEase)) { width ->
                                    -minOf(width / 32, pageTravelPx / 2) * direction
                                }
                        } else ExitTransition.None
                        // Short translation + fade keeps long text and WebViews at their native scale.
                        (enter togetherWith exit).using(null)
                    },
                    label = "page-transition"
                ) { target ->
                    pageStates.SaveableStateProvider(target.name) {
                        when (target) {
                            AppPage.Chat -> ChatScreen(vm, onOpenDrawer = openDrawer, onOpenSettings = { navigate(AppPage.Settings) })
                            AppPage.Rp -> RpScreen(rpVm, vm, onOpenDrawer = openDrawer, onOpenSettings = { navigate(AppPage.Settings) })
                            AppPage.Story -> StoryScreen(
                                mainVm = vm,
                                storyVm = storyVm,
                                onOpenDrawer = openDrawer,
                                onCreateStory = ::requestStoryCreation,
                                onOpenSettings = { navigate(AppPage.Settings) }
                            )
                            AppPage.Draw -> DrawScreen(vm, onOpenDrawer = openDrawer, onOpenSettings = { navigate(AppPage.Settings) })
                            AppPage.Media -> MediaDownloadScreen(mediaVm, onOpenDrawer = openDrawer)
                            AppPage.Settings -> SettingsScreen(
                                vm,
                                onOpenDrawer = openDrawer,
                                openIslandSettings = openIslandSettings,
                                onIslandSettingsConsumed = onIslandSettingsConsumed
                            )
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                        .padding(top = 64.dp, start = 16.dp, end = 16.dp).widthIn(max = 520.dp)
                ) { data ->
                    AsterPaperSnackbar(
                        message = data.visuals.message,
                        actionLabel = data.visuals.actionLabel,
                        onAction = { data.performAction() },
                        onDismiss = { data.dismiss() }
                    )
                }
            }
        }
    }

    if (showStoryCreator) {
        AdModalDialog(
            title = "新建故事",
            subtitle = "只需要一个名字，设定可以之后慢慢讨论",
            icon = Icons.Rounded.AutoStories,
            onDismiss = { showStoryCreator = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = storyTitle,
                        onValueChange = { if (it.length <= 60) storyTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("故事名称") },
                        placeholder = { Text("例如：庄园物语") },
                        supportingText = { Text("${storyTitle.length}/60") },
                        shape = RoundedCornerShape(17.dp)
                    )
                    if (vm.chatProfile.chatModel.isBlank()) {
                        Text("当前没有可用的对话模型，请先在设置中完成服务配置。", color = Danger, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("使用 ${vm.chatProfile.name} · ${vm.chatProfile.chatModel}", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                    }
                    AsterPillButton(
                        text = "导入角色卡",
                        icon = Icons.Rounded.FileUpload,
                        enabled = vm.chatProfile.chatModel.isNotBlank(),
                        onClick = {
                            cardPicker.launch(arrayOf("image/png", "application/json", "application/zip", "text/plain", "application/octet-stream"))
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    Text("导入后不进入讨论。开场白会去掉网页和样式，只留下正文。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                }
            },
            actions = {
                AsterPillButton(
                    text = "取消",
                    onClick = { showStoryCreator = false },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
                AsterPillButton(
                    text = "创建",
                    primary = true,
                    enabled = storyTitle.isNotBlank() && vm.chatProfile.chatModel.isNotBlank(),
                    onClick = {
                        storyVm.createStory(storyTitle, vm.chatProfile) { page = AppPage.Story }
                        showStoryCreator = false
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
            }
        )
    }
}

/** Only a deliberate inward drag from the starting edge opens the drawer. */
private fun Modifier.drawerEdgeSwipe(onOpen: () -> Unit): Modifier = composed {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnOpen by rememberUpdatedState(onOpen)
    val edgePx = with(density) { 24.dp.toPx() }
    val triggerPx = with(density) { 72.dp.toPx() }
    pointerInput(edgePx, triggerPx, rtl) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val beganAtEdge = if (rtl) down.position.x >= size.width - edgePx else down.position.x <= edgePx
            if (!beganAtEdge) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.isConsumed) break
                dx += change.positionChange().x
                dy += change.positionChange().y
                val inwardTravel = if (rtl) -dx else dx
                if (inwardTravel < -viewConfiguration.touchSlop ||
                    (abs(dy) > viewConfiguration.touchSlop && abs(dy) >= abs(dx))) break
                if (inwardTravel > abs(dy) * 2f && inwardTravel >= triggerPx) {
                    change.consume()
                    currentOnOpen()
                    break
                }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

@Composable
internal fun AppDrawer(
    vm: MainViewModel,
    currentPage: AppPage,
    onNewChat: () -> Unit,
    onNewStory: () -> Unit,
    onConversation: (String) -> Unit,
    onNavigate: (AppPage) -> Unit,
    onClose: () -> Unit,
    history: DrawerHistory = DrawerHistory.chat(vm)
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<Conversation?>(null) }
    var renameCandidate by remember { mutableStateOf<Conversation?>(null) }
    var tagsCandidate by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showNewActions by remember { mutableStateOf(false) }
    var conversationFilter by rememberSaveable { mutableStateOf("all") }
    val focusManager = LocalFocusManager.current
    val historyState = rememberLazyListState()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val motionEnabled = LocalAsterMotionEnabled.current
    val normalizedQuery = query.trim()
    val conversationSnapshot = history.items()
    val visibleConversations = remember(conversationSnapshot, normalizedQuery, conversationFilter) {
        conversationSnapshot.filter { conversation ->
            val matches = normalizedQuery.isBlank() || conversation.title.contains(normalizedQuery, ignoreCase = true) ||
                conversation.tags.any { it.contains(normalizedQuery, ignoreCase = true) } ||
                conversation.messages.any { it.content.contains(normalizedQuery, ignoreCase = true) }
            matches && when (conversationFilter) {
                "favorites" -> conversation.favorite && !conversation.archived
                "archived" -> conversation.archived
                else -> !conversation.archived
            }
        }.sortedByDescending { it.updatedAt }
    }
    LaunchedEffect(normalizedQuery, conversationFilter) { historyState.scrollToItem(0) }
    val pinnedConversations = visibleConversations.filter { it.pinned && conversationFilter != "archived" }
    val unpinnedConversations = if (conversationFilter == "archived") visibleConversations
        else visibleConversations.filterNot { it.pinned }
    val today = java.time.LocalDate.now()
    val groupedConversations = unpinnedConversations.groupBy { conversation ->
        val day = java.time.Instant.ofEpochMilli(conversation.updatedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        when {
            day == today -> "今天"
            day == today.minusDays(1) -> "昨天"
            day >= today.minusDays(7) -> "最近七天"
            else -> "更早"
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = Canvas,
        drawerContentColor = Ink,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxWidth(.90f).widthIn(max = 360.dp).fillMaxHeight()
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsterMark(Modifier.size(32.dp))
                Spacer(Modifier.width(4.dp))
                AsterWordmark(size = 24)
                Spacer(Modifier.weight(1f))
                AsterIconButton(Icons.Rounded.EditNote, history.newLabel, history.onNew ?: onNewChat, enabled = !history.busy())
                AsterIconButton(Icons.Rounded.Close, "关闭侧栏", onClose)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(19.dp)) },
                trailingIcon = { if (query.isNotEmpty()) AsterIconButton(Icons.Rounded.Close, "清空搜索", { query = "" }) },
                placeholder = { Text(history.searchHint, style = MaterialTheme.typography.bodyMedium) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            AnimatedVisibility(
                visible = normalizedQuery.isBlank() && !keyboardVisible,
                enter = if (motionEnabled) fadeIn(tween(140)) + expandVertically(AsterSpring.spatial()) else EnterTransition.None,
                exit = if (motionEnabled) fadeOut(tween(100)) + shrinkVertically(AsterSpring.snappy()) else ExitTransition.None
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(AppPage.Chat, AppPage.Rp, AppPage.Draw, AppPage.Media).forEach { item ->
                        val selected = currentPage == item
                        val interactionSource = remember(item) { MutableInteractionSource() }
                        val selectionBg by animateColorAsState(
                            if (selected) AccentSoft else Color.Transparent,
                            AsterSpring.editorial(), label = "nav-bg"
                        )
                        val selectionInk by animateColorAsState(
                            if (selected) OnAccentSoft else MutedInk,
                            AsterSpring.editorial(), label = "nav-ink"
                        )
                        Surface(
                            onClick = { onNavigate(item) },
                            modifier = Modifier.weight(1f).semantics { this.selected = selected },
                            interactionSource = interactionSource,
                            color = selectionBg,
                            contentColor = selectionInk,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                Modifier
                                    .heightIn(min = 64.dp)
                                    .appleSpringPressable(pressedScale = 0.96f, interactionSource = interactionSource)
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
                            ) {
                                Icon(item.icon, null, Modifier.size(20.dp))
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = if (keyboardVisible) 4.dp else 10.dp), color = Hairline)
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        normalizedQuery.isNotBlank() -> "搜索结果"
                        conversationFilter == "archived" -> "已归档"
                        conversationFilter == "favorites" -> "收藏"
                        else -> history.title
                    },
                    color = Ink,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.width(6.dp))
                Text("${visibleConversations.size}", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            }
            val filters = listOf("all", "favorites", "archived")
            AsterSegmentedControl(
                labels = listOf("全部", "收藏", "归档"),
                selectedIndex = filters.indexOf(conversationFilter).coerceAtLeast(0),
                onSelect = { conversationFilter = filters[it] },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = historyState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (visibleConversations.isEmpty()) {
                    item {
                        if (normalizedQuery.isNotBlank()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("没有找到对话", color = Ink, style = MaterialTheme.typography.titleSmall)
                                Text("试试其他关键词或标签", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                            }
                        } else AsterEmptyState(
                            Icons.Rounded.ChatBubbleOutline,
                            when {
                                normalizedQuery.isNotBlank() -> "没有找到对话"
                                conversationFilter == "favorites" -> "还没有收藏的对话"
                                conversationFilter == "archived" -> "这里还没有归档内容"
                                history.page == AppPage.Rp -> "还没有剧情存档"
                                else -> "从一段对话开始"
                            },
                            when {
                                normalizedQuery.isNotBlank() -> "试试其他关键词或标签"
                                conversationFilter == "favorites" -> "在对话菜单中点按“收藏”"
                                conversationFilter == "archived" -> "归档的对话会收纳在这里"
                                history.page == AppPage.Rp -> "点右上角新建一段剧情"
                                else -> "你的想法，会在这里留下记录"
                            }
                        )
                        if (normalizedQuery.isNotBlank() || conversationFilter != "all") {
                            TextButton(
                                onClick = {
                                    if (conversationFilter != "all") conversationFilter = "all"
                                    else { query = ""; focusManager.clearFocus() }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) {
                                Text(if (conversationFilter != "all") "查看全部对话" else "清除搜索", color = Ink)
                            }
                        }
                    }
                }
                if (pinnedConversations.isNotEmpty()) {
                    item(key = "pinned-header") {
                        Text("置顶", color = MutedInk, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp))
                    }
                    items(pinnedConversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            selected = currentPage == history.page && history.activeId() == conversation.id,
                            generating = history.busy() && history.activeId() == conversation.id,
                            onClick = { (history.onSelect ?: onConversation)(conversation.id) },
                            onRename = { renameCandidate = conversation; renameText = conversation.title },
                            onDelete = { deleteCandidate = conversation },
                            onTogglePin = { history.setPinned(conversation.id, !conversation.pinned) },
                            onToggleFavorite = { history.setFavorite(conversation.id, !conversation.favorite) },
                            onToggleArchive = { history.setArchived(conversation.id, !conversation.archived) },
                            onEditTags = { tagsCandidate = conversation }
                        )
                    }
                }
                groupedConversations.forEach { (period, conversations) ->
                    item(key = "period-$period") {
                        Text(period, color = MutedInk, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp))
                    }
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation,
                            selected = currentPage == history.page && history.activeId() == conversation.id,
                            generating = history.busy() && history.activeId() == conversation.id,
                            onClick = { (history.onSelect ?: onConversation)(conversation.id) },
                            onRename = { renameCandidate = conversation; renameText = conversation.title },
                            onDelete = { deleteCandidate = conversation },
                            onTogglePin = { history.setPinned(conversation.id, !conversation.pinned) },
                            onToggleFavorite = { history.setFavorite(conversation.id, !conversation.favorite) },
                            onToggleArchive = { history.setArchived(conversation.id, !conversation.archived) },
                            onEditTags = { tagsCandidate = conversation }
                        )
                    }
                }
            }
            if (!keyboardVisible) {
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Hairline)
                val settingsSelected = currentPage == AppPage.Settings
                val settingsInteraction = remember { MutableInteractionSource() }
                val settingsFill by animateColorAsState(
                    if (settingsSelected) AccentSoft else Color.Transparent,
                    AsterSpring.editorial(), label = "settings-nav-color"
                )
                Surface(
                    onClick = { onNavigate(AppPage.Settings) },
                    interactionSource = settingsInteraction,
                    color = settingsFill,
                    contentColor = if (settingsSelected) OnAccentSoft else Ink,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(12.dp).semantics { selected = settingsSelected }
                ) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .appleSpringPressable(pressedScale = 0.96f, interactionSource = settingsInteraction)
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tune, null, Modifier.size(21.dp))
                        Text("设置", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
                        Text("Aster ${BuildConfig.VERSION_NAME}", color = MutedInk, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (showNewActions) {
        AdActionSheet(
            title = "新建",
            subtitle = "选择接下来要开始的工作方式",
            actions = listOf(
                AdActionOption("chat", "普通对话", "像现在一样开始一段新的 AI 对话", Icons.Rounded.Forum),
                AdActionOption("rp", "剧情 RP", "角色卡或纯文本，统一单会话角色扮演", Icons.Rounded.TheaterComedy),
                AdActionOption("story", "故事创作", "讨论设定与正式正文分开保存", Icons.Rounded.AutoStories)
            ),
            onAction = { action ->
                showNewActions = false
                when (action.id) {
                    "story" -> onNewStory()
                    "rp" -> onNavigate(AppPage.Rp)
                    else -> onNewChat()
                }
            },
            onDismiss = { showNewActions = false },
            headerIcon = Icons.Rounded.Add
        )
    }

    deleteCandidate?.let { conversation ->
        AdConfirmDialog(
            title = "删除这段对话？",
            // 撤销是真实存在的，别写成「永久删除」把人逼着急着点确认。
            message = "“${conversation.title}”及其中的全部消息将被删除，删除后底部会出现一次撤销机会。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            icon = Icons.Rounded.DeleteOutline,
            destructive = true,
            onConfirm = { history.delete(conversation.id); deleteCandidate = null },
            onDismiss = { deleteCandidate = null }
        )
    }

    renameCandidate?.let { conversation ->
        AdModalDialog(
            title = "重命名对话",
            subtitle = "让历史任务更容易查找",
            icon = Icons.Rounded.Edit,
            onDismiss = { renameCandidate = null },
            content = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { if (it.length <= 40) renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("输入新的任务名称") },
                    supportingText = { Text("${renameText.length}/40") },
                    shape = RoundedCornerShape(17.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Surface,
                        unfocusedContainerColor = Surface,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            },
            actions = {
                AsterPillButton(
                    text = "取消",
                    onClick = { renameCandidate = null },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
                AsterPillButton(
                    text = "保存",
                    primary = true,
                    enabled = renameText.isNotBlank(),
                    onClick = { history.rename(conversation.id, renameText); renameCandidate = null },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
            }
        )
    }

    tagsCandidate?.let { conversation ->
        var tagsText by remember(conversation.id) { mutableStateOf(conversation.tags.joinToString("，")) }
        AdModalDialog(
            title = "管理标签",
            subtitle = "用逗号分隔，最多保留 5 个标签",
            icon = Icons.Rounded.Label,
            onDismiss = { tagsCandidate = null },
            content = {
                OutlinedTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it.take(160) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("例如：灵感，待办，项目") },
                    shape = RoundedCornerShape(17.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Surface,
                        unfocusedContainerColor = Surface,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            },
            actions = {
                AsterPillButton(
                    text = "取消",
                    onClick = { tagsCandidate = null },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
                AsterPillButton(
                    text = "保存",
                    primary = true,
                    onClick = {
                        val tags = tagsText.split(Regex("[,，;；\\n]"))
                            .map { it.trim().take(24) }
                            .filter(String::isNotBlank)
                            .distinctBy { it.lowercase() }
                            .take(5)
                        history.setTags(conversation.id, tags)
                        tagsCandidate = null
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                )
            }
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    generating: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleArchive: () -> Unit,
    onEditTags: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        if (selected) Surface else Color.Transparent,
        AsterSpring.editorial(), label = "conversation-selection"
    )
    val preview = remember(conversation.messages, generating) {
        if (generating) "正在生成…" else conversation.messages.lastOrNull { it.content.isNotBlank() }
            ?.content?.take(180)?.replace(Regex("\\s+"), " ")?.trim().orEmpty().ifBlank { "还没有消息" }
    }
    Surface(onClick = onClick, color = fill, interactionSource = interactionSource,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(1.dp, Hairline) else null,
        modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .appleSpringPressable(pressedScale = 0.975f, interactionSource = interactionSource)
            .padding(start = 10.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(26.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected) Accent else Color.Transparent))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) Icon(Icons.Rounded.PushPin, "已置顶", Modifier.size(14.dp), tint = Accent)
                    if (conversation.favorite) Icon(Icons.Rounded.Star, "已收藏", Modifier.size(14.dp), tint = Accent)
                    Text(conversation.title.ifBlank { "未命名对话" },
                        Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (generating) Accent else MutedInk, style = MaterialTheme.typography.labelMedium)
                if (conversation.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        conversation.tags.take(2).forEach { tag ->
                            Text(
                                tag,
                                Modifier.clip(RoundedCornerShape(6.dp)).background(AccentSoft)
                                    .widthIn(max = 70.dp).padding(horizontal = 6.dp, vertical = 2.dp),
                                color = OnAccentSoft,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (generating) AsterLoadingIndicator(Modifier.padding(horizontal = 4.dp), size = 12.dp)
            AsterIconButton(Icons.Rounded.MoreHoriz, "更多：${conversation.title}", { showActions = true })
        }
    }
    if (showActions) {
        AdActionSheet(
            title = conversation.title,
            subtitle = if (generating) "当前任务正在生成" else "管理这个历史任务",
            actions = listOf(
                AdActionOption(
                    "pin", if (conversation.pinned) "取消置顶" else "置顶",
                    "将常用对话固定在列表顶部", Icons.Rounded.PushPin
                ),
                AdActionOption(
                    "favorite", if (conversation.favorite) "取消收藏" else "收藏",
                    "在收藏筛选中快速找到它", Icons.Rounded.Star
                ),
                AdActionOption("tags", "编辑标签", "标签可用于搜索", Icons.Rounded.Label),
                AdActionOption(
                    "archive", if (conversation.archived) "移出归档" else "归档",
                    if (conversation.archived) "恢复到全部对话" else "从主列表收起，可在归档筛选中找回",
                    Icons.Rounded.Archive
                ),
                AdActionOption("rename", "重命名", "修改任务在侧栏中的名称", Icons.Rounded.Edit),
                AdActionOption(
                    "delete",
                    "删除对话",
                    if (generating) "请先停止当前生成" else "永久删除全部消息",
                    Icons.Rounded.DeleteOutline,
                    destructive = true,
                    enabled = !generating
                )
            ),
            onAction = { action ->
                showActions = false
                when (action.id) {
                    "pin" -> onTogglePin()
                    "favorite" -> onToggleFavorite()
                    "tags" -> onEditTags()
                    "archive" -> onToggleArchive()
                    "rename" -> onRename()
                    "delete" -> onDelete()
                }
            },
            onDismiss = { showActions = false },
            headerIcon = Icons.Rounded.ChatBubbleOutline
        )
    }
}

/**
 * Which save list the drawer shows: chat conversations normally, RP save files on the 剧情 page.
 * Rows share the chat row UI (pin / favorite / archive / tags / rename / delete).
 */
internal class DrawerHistory(
    val page: AppPage,
    val title: String,
    val searchHint: String,
    val newLabel: String,
    val items: () -> List<Conversation>,
    val activeId: () -> String?,
    val busy: () -> Boolean,
    val setPinned: (String, Boolean) -> Unit,
    val setFavorite: (String, Boolean) -> Unit,
    val setArchived: (String, Boolean) -> Unit,
    val setTags: (String, List<String>) -> Unit,
    val rename: (String, String) -> Unit,
    val delete: (String) -> Unit,
    /** Null = use the drawer's default callbacks. */
    val onSelect: ((String) -> Unit)? = null,
    val onNew: (() -> Unit)? = null
) {
    companion object {
        fun chat(vm: MainViewModel) = DrawerHistory(
            page = AppPage.Chat, title = "对话记录", searchHint = "搜索对话或标签", newLabel = "新建对话",
            items = { vm.conversations.toList() }, activeId = { vm.activeConversationId }, busy = { vm.isChatLoading },
            setPinned = vm::setConversationPinned, setFavorite = vm::setConversationFavorite,
            setArchived = vm::setConversationArchived, setTags = vm::setConversationTags,
            rename = vm::renameConversation, delete = vm::deleteConversation
        )

        fun rp(vm: com.adong.adchat.ui.rp.RpViewModel, onSelected: () -> Unit) = DrawerHistory(
            page = AppPage.Rp, title = "剧情存档", searchHint = "搜索剧情或标签", newLabel = "新建剧情",
            items = { vm.drawerConversations() }, activeId = { vm.activeSessionId }, busy = { vm.generating },
            setPinned = vm::setSessionPinned, setFavorite = vm::setSessionFavorite,
            setArchived = vm::setSessionArchived, setTags = vm::setSessionTags,
            rename = vm::renameSession, delete = vm::deleteSession,
            onSelect = { vm.selectSession(it); vm.draft = ""; onSelected() },
            onNew = { vm.requestNewSession(); onSelected() }
        )
    }
}
