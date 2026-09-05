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
import com.adong.adchat.ui.media.MediaDownloadViewModel
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

    private fun extractSharedMediaText(intent: Intent?): String? {
        return when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.trim()?.takeIf { it.isNotBlank() }
    }
}

private enum class AppPage(val label: String, val icon: ImageVector) {
    Chat("对话", Icons.Rounded.Forum),
    Draw("创作", Icons.Rounded.AutoAwesome),
    Media("下载", Icons.Rounded.DownloadForOffline),
    Settings("设置", Icons.Rounded.Tune)
}

@Composable
private fun AsterApp(
    vm: MainViewModel,
    incomingMediaText: String?,
    onMediaTextConsumed: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(AppPage.Chat) }
    val pageStates = rememberSaveableStateHolder()
    val mediaVm: MediaDownloadViewModel = viewModel()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val openDrawer = { scope.launch { drawerState.open() }; Unit }
    fun navigate(target: AppPage) {
        page = target
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
        vm.notice?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissNotice()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = page != AppPage.Settings || drawerState.isOpen,
        drawerContent = {
            AppDrawer(
                vm = vm,
                currentPage = page,
                onNewChat = { vm.newConversation(); navigate(AppPage.Chat) },
                onConversation = { vm.selectConversation(it); navigate(AppPage.Chat) },
                onNavigate = ::navigate,
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            containerColor = Canvas,
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = Night,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        ) { padding ->
            val pageBottomPadding = if (page == AppPage.Chat) 0.dp else padding.calculateBottomPadding()
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
                        AppPage.Draw -> DrawScreen(vm, onOpenDrawer = openDrawer, onOpenSettings = { navigate(AppPage.Settings) })
                        AppPage.Media -> MediaDownloadScreen(mediaVm, onOpenDrawer = openDrawer)
                        AppPage.Settings -> SettingsScreen(vm, onOpenDrawer = openDrawer)
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppDrawer(
    vm: MainViewModel,
    currentPage: AppPage,
    onNewChat: () -> Unit,
    onConversation: (String) -> Unit,
    onNavigate: (AppPage) -> Unit,
    onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<Conversation?>(null) }
    var renameCandidate by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    val visibleConversations = vm.conversations.filter { conversation ->
        query.isBlank() || conversation.title.contains(query.trim(), ignoreCase = true) ||
            conversation.messages.any { it.content.contains(query.trim(), ignoreCase = true) }
    }.sortedByDescending { it.updatedAt }
    val today = java.time.LocalDate.now()
    val groupedConversations = visibleConversations.groupBy { conversation ->
        val day = java.time.Instant.ofEpochMilli(conversation.updatedAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
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
        modifier = Modifier.widthIn(max = 360.dp).fillMaxHeight()
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsterMark(Modifier.size(46.dp))
                AsterWordmark()
                Spacer(Modifier.weight(1f))
                AsterIconButton(Icons.Rounded.Close, "关闭侧栏", onClose)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppPage.entries.filter { it != AppPage.Settings }.forEach { item ->
                    val selected = currentPage == item
                    Surface(onClick = { onNavigate(item) }, modifier = Modifier.weight(1f),
                        color = if (selected) Night else Surface,
                        contentColor = if (selected) WarmWhite else MutedInk,
                        shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(item.icon, null, Modifier.size(21.dp))
                            Spacer(Modifier.height(7.dp))
                            Text(item.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = onNewChat, enabled = !vm.isChatLoading,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = WarmWhite)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text(if (vm.isChatLoading) "当前对话正在生成" else "新建对话")
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(19.dp)) },
                trailingIcon = { if (query.isNotEmpty()) AsterIconButton(Icons.Rounded.Close, "清空搜索", { query = "" }) },
                placeholder = { Text("搜索对话", style = MaterialTheme.typography.bodyMedium) },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface, focusedBorderColor = Accent,
                    unfocusedBorderColor = Hairline)
            )
            LazyColumn(modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (visibleConversations.isEmpty()) {
                    item { AsterEmptyState(Icons.Rounded.ChatBubbleOutline,
                        if (query.isBlank()) "从一段对话开始" else "没有找到对话",
                        if (query.isBlank()) "你的想法，会在这里留下记录" else "试试其他关键词") }
                }
                groupedConversations.forEach { (period, conversations) ->
                    item(key = "period-$period") {
                        Text(period, color = MutedInk, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp))
                    }
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationRow(conversation,
                            selected = currentPage == AppPage.Chat && vm.activeConversationId == conversation.id,
                            generating = vm.isChatLoading && vm.activeConversationId == conversation.id,
                            onClick = { onConversation(conversation.id) },
                            onRename = { renameCandidate = conversation; renameText = conversation.title },
                            onDelete = { deleteCandidate = conversation })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = Hairline)
            Surface(onClick = { onNavigate(AppPage.Settings) },
                color = if (currentPage == AppPage.Settings) AccentSoft else Color.Transparent,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(21.dp), tint = MutedInk)
                    Text("设置", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
                    Text("Aster ${BuildConfig.VERSION_NAME}", color = MutedInk, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
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
    val format = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) AccentSoft else Color.Transparent)
            .clickable(onClick = onClick).padding(start = 12.dp, end = 5.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) Color.White.copy(alpha = .72f) else Canvas),
            contentAlignment = Alignment.Center
        ) {
            if (generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    color = Accent,
                    trackColor = Accent.copy(alpha = .18f),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Rounded.ChatBubbleOutline, null, tint = if (selected) Accent else MutedInk, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            Text(
                if (generating) "\u6b63\u5728\u751f\u6210 \u00b7 ${conversation.messages.count { it.role == "user" }} \u6761\u63d0\u95ee"
                else "${conversation.messages.count { it.role == "user" }} \u6761\u63d0\u95ee \u00b7 ${format.format(Date(conversation.updatedAt))}",
                color = if (generating) Accent else MutedInk,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (generating) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        IconButton(
            onClick = { showActions = true },
            colors = IconButtonDefaults.iconButtonColors(containerColor = if (selected) Color.White.copy(alpha = .72f) else Color.Transparent, contentColor = MutedInk),
            modifier = Modifier.size(36.dp)
        ) { Icon(Icons.Rounded.MoreHoriz, "更多", Modifier.size(19.dp)) }
    }
    if (showActions) {
        AdActionSheet(
            title = conversation.title,
            subtitle = if (generating) "\u5f53\u524d\u4efb\u52a1\u6b63\u5728\u751f\u6210" else "\u7ba1\u7406\u8fd9\u4e2a\u5386\u53f2\u4efb\u52a1",
            actions = listOf(
                AdActionOption("rename", "\u91cd\u547d\u540d", "\u4fee\u6539\u4efb\u52a1\u5728\u4fa7\u680f\u4e2d\u7684\u540d\u79f0", Icons.Rounded.Edit),
                AdActionOption(
                    "delete",
                    "\u5220\u9664\u5bf9\u8bdd",
                    if (generating) "\u8bf7\u5148\u505c\u6b62\u5f53\u524d\u751f\u6210" else "\u6c38\u4e45\u5220\u9664\u5168\u90e8\u6d88\u606f",
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
