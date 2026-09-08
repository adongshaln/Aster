package com.adong.adchat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.data.Conversation
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.components.*
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
import com.adong.adchat.ui.story.StoryViewModel
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var pendingMediaShare by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingMediaShare = extractSharedMediaText(intent)
        enableEdgeToEdge()
        setContent {
            AsterTheme {
                AsterApp(
                    vm = viewModel,
                    incomingMediaText = pendingMediaShare,
                    onMediaTextConsumed = { pendingMediaShare = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMediaShare = extractSharedMediaText(intent)
    }

    private fun extractSharedMediaText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }
}

internal enum class AppPage(val label: String, val icon: ImageVector) {
    Chat("对话", Icons.Rounded.Forum),
    Story("故事", Icons.Rounded.AutoStories),
    Draw("创作", Icons.Rounded.AutoAwesome),
    Media("下载", Icons.Rounded.DownloadForOffline),
    Settings("设置", Icons.Rounded.Tune)
}

@Composable
private fun AsterApp(vm: MainViewModel, incomingMediaText: String?, onMediaTextConsumed: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(AppPage.Chat) }
    val pageStates = rememberSaveableStateHolder()
    val mediaVm: MediaDownloadViewModel = viewModel()
    val storyVm: StoryViewModel = viewModel()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showStoryCreator by rememberSaveable { mutableStateOf(false) }
    var storyTitle by rememberSaveable { mutableStateOf("") }
    val openDrawer = { scope.launch { drawerState.open() }; Unit }
    fun navigate(target: AppPage) {
        page = target
        scope.launch { drawerState.close() }
    }
    fun requestStoryCreation() {
        storyTitle = ""
        showStoryCreator = true
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = page != AppPage.Chat && drawerState.isClosed) { navigate(AppPage.Chat) }

    LaunchedEffect(incomingMediaText) {
        incomingMediaText?.takeIf { it.isNotBlank() }?.let { sharedText ->
            mediaVm.acceptSharedText(sharedText)
            page = AppPage.Media
            onMediaTextConsumed()
        }
    }

    LaunchedEffect(vm.notice) {
        val message = vm.notice ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        if (vm.notice == message) vm.dismissNotice()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = page != AppPage.Settings || drawerState.isOpen,
        drawerContent = {
            AppDrawer(
                vm = vm,
                currentPage = page,
                onNewChat = { vm.newConversation(); navigate(AppPage.Chat) },
                onNewStory = ::requestStoryCreation,
                onConversation = { vm.selectConversation(it); navigate(AppPage.Chat) },
                onNavigate = ::navigate,
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(containerColor = Canvas) { padding ->
            val edgeToEdgePage = page == AppPage.Chat || page == AppPage.Story
            val pageBottomPadding = if (edgeToEdgePage) 0.dp else padding.calculateBottomPadding()
            Box(Modifier.fillMaxSize().padding(bottom = pageBottomPadding), contentAlignment = Alignment.TopCenter) {
                AnimatedContent(
                    targetState = page,
                    modifier = Modifier.widthIn(max = 900.dp).fillMaxSize(),
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val enterOffset: (Int) -> Int = { width -> if (forward) width / 7 else -width / 7 }
                        val exitOffset: (Int) -> Int = { width -> if (forward) -width / 10 else width / 10 }
                        (fadeIn(tween(210)) + slideInHorizontally(tween(260), initialOffsetX = enterOffset) + scaleIn(tween(260), initialScale = 0.985f)) togetherWith
                            (fadeOut(tween(150)) + slideOutHorizontally(tween(210), targetOffsetX = exitOffset) + scaleOut(tween(210), targetScale = 0.992f))
                    },
                    label = "page-transition"
                ) { target ->
                    pageStates.SaveableStateProvider(target.name) {
                        when (target) {
                            AppPage.Chat -> ChatScreen(vm, onOpenDrawer = openDrawer, onOpenSettings = { navigate(AppPage.Settings) })
                            AppPage.Story -> StoryScreen(
                                mainVm = vm,
                                storyVm = storyVm,
                                onOpenDrawer = openDrawer,
                                onCreateStory = ::requestStoryCreation,
                                onOpenSettings = { navigate(AppPage.Settings) }
                            )
                            AppPage.Draw -> DrawScreen(vm, onOpenDrawer = openDrawer, onOpenSettings = { navigate(AppPage.Settings) })
                            AppPage.Media -> MediaDownloadScreen(mediaVm, onOpenDrawer = openDrawer)
                            AppPage.Settings -> SettingsScreen(vm, onOpenDrawer = openDrawer)
                        }
                    }
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                        .padding(top = 82.dp, start = 16.dp, end = 16.dp).widthIn(max = 640.dp)
                ) { data ->
                    Snackbar(
                        containerColor = Night,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().clickable { data.dismiss() }
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(data.visuals.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.Rounded.Close, "关闭提示", Modifier.size(18.dp), tint = Color.White.copy(alpha = .82f))
                        }
                    }
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
                }
            },
            actions = {
                OutlinedButton(
                    onClick = { showStoryCreator = false },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("取消") }
                Button(
                    onClick = {
                        storyVm.createStory(storyTitle, vm.chatProfile) { page = AppPage.Story }
                        showStoryCreator = false
                    },
                    enabled = storyTitle.isNotBlank() && vm.chatProfile.chatModel.isNotBlank(),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("创建", fontWeight = FontWeight.Bold) }
            }
        )
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
    onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<Conversation?>(null) }
    var renameCandidate by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showNewActions by remember { mutableStateOf(false) }
    val visibleConversations = vm.conversations.filter { conversation ->
        query.isBlank() || conversation.title.contains(query.trim(), ignoreCase = true) ||
            conversation.messages.any { it.content.contains(query.trim(), ignoreCase = true) }
    }.sortedByDescending { it.updatedAt }
    val today = java.time.LocalDate.now()
    val groupedConversations = visibleConversations.groupBy { conversation ->
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
                AsterIconButton(Icons.Rounded.EditSquare, "新建", { showNewActions = true }, enabled = !vm.isChatLoading)
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
                placeholder = { Text("搜索对话", style = MaterialTheme.typography.bodyMedium) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppPage.entries.filter { it != AppPage.Settings }.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { item ->
                            val selected = currentPage == item
                            Surface(onClick = { onNavigate(item) }, modifier = Modifier.weight(1f),
                                color = if (selected) AccentSoft else Color.Transparent,
                                contentColor = if (selected) Accent else MutedInk, shape = RoundedCornerShape(12.dp)) {
                                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(item.icon, null, Modifier.size(18.dp))
                                    Text(item.label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = Hairline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (query.isBlank()) "对话记录" else "搜索结果", color = Ink, style = MaterialTheme.typography.labelLarge)
                Text("${visibleConversations.size}", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (visibleConversations.isEmpty()) {
                    item {
                        AsterEmptyState(
                            Icons.Rounded.ChatBubbleOutline,
                            if (query.isBlank()) "从一段对话开始" else "没有找到对话",
                            if (query.isBlank()) "你的想法，会在这里留下记录" else "试试其他关键词"
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
                            selected = currentPage == AppPage.Chat && vm.activeConversationId == conversation.id,
                            generating = vm.isChatLoading && vm.activeConversationId == conversation.id,
                            onClick = { onConversation(conversation.id) },
                            onRename = { renameCandidate = conversation; renameText = conversation.title },
                            onDelete = { deleteCandidate = conversation }
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Hairline)
            Surface(
                onClick = { onNavigate(AppPage.Settings) },
                color = if (currentPage == AppPage.Settings) AccentSoft else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(21.dp), tint = MutedInk)
                    Text("设置", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
                    Text("Aster ${BuildConfig.VERSION_NAME}", color = MutedInk, style = MaterialTheme.typography.labelSmall)
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
                AdActionOption("story", "故事创作", "讨论设定与正式正文分开保存", Icons.Rounded.AutoStories)
            ),
            onAction = { action ->
                showNewActions = false
                if (action.id == "story") onNewStory() else onNewChat()
            },
            onDismiss = { showNewActions = false },
            headerIcon = Icons.Rounded.Add
        )
    }

    deleteCandidate?.let { conversation ->
        AdConfirmDialog(
            title = "删除这段对话？",
            message = "“${conversation.title}”及其中的全部消息将被永久删除。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            icon = Icons.Rounded.DeleteOutline,
            destructive = true,
            onConfirm = { vm.deleteConversation(conversation.id); deleteCandidate = null },
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
                OutlinedButton(
                    onClick = { renameCandidate = null },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
                ) { Text("取消", fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { vm.renameConversation(conversation.id, renameText); renameCandidate = null },
                    enabled = renameText.isNotBlank(),
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text("保存", fontWeight = FontWeight.Bold) }
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
    onDelete: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    val preview = remember(conversation.messages, generating) {
        if (generating) "正在生成…" else conversation.messages.lastOrNull { it.content.isNotBlank() }
            ?.content?.take(180)?.replace(Regex("\\s+"), " ")?.trim().orEmpty().ifBlank { "还没有消息" }
    }
    Surface(onClick = onClick, color = if (selected) Surface else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(1.dp, Hairline) else null,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(26.dp).clip(RoundedCornerShape(2.dp))
                .background(if (selected) Accent else Color.Transparent))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(conversation.title.ifBlank { "未命名对话" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (generating) Accent else MutedInk, style = MaterialTheme.typography.labelMedium)
            }
            if (generating) CircularProgressIndicator(Modifier.padding(horizontal = 4.dp).size(12.dp), color = Accent, strokeWidth = 1.5.dp)
            AsterIconButton(Icons.Rounded.MoreHoriz, "更多：${conversation.title}", { showActions = true })
        }
    }
    if (showActions) {
        AdActionSheet(
            title = conversation.title,
            subtitle = if (generating) "当前任务正在生成" else "管理这个历史任务",
            actions = listOf(
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
                if (action.id == "rename") onRename() else onDelete()
            },
            onDismiss = { showActions = false },
            headerIcon = Icons.Rounded.ChatBubbleOutline
        )
    }
}
