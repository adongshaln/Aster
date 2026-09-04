package com.adong.adchat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
            ADChatTheme {
                ADChatApp(
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
    Draw("视觉创作", Icons.Rounded.AutoAwesome),
    Media("媒体下载", Icons.Rounded.DownloadForOffline),
    Settings("API 与设置", Icons.Rounded.Tune)
}

@Composable
private fun ADChatApp(
    vm: MainViewModel,
    incomingMediaText: String?,
    onMediaTextConsumed: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(AppPage.Chat) }
    val mediaVm: MediaDownloadViewModel = viewModel()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val openDrawer = { scope.launch { drawerState.open() }; Unit }
    fun navigate(target: AppPage) {
        page = target
        scope.launch { drawerState.close() }
    }

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
            Box(Modifier.fillMaxSize().padding(bottom = pageBottomPadding)) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val enterOffset: (Int) -> Int = { width -> if (forward) width / 7 else -width / 7 }
                        val exitOffset: (Int) -> Int = { width -> if (forward) -width / 10 else width / 10 }
                        (fadeIn(tween(210)) + slideInHorizontally(tween(260), initialOffsetX = enterOffset) + scaleIn(tween(260), initialScale = 0.985f)) togetherWith
                            (fadeOut(tween(150)) + slideOutHorizontally(tween(210), targetOffsetX = exitOffset) + scaleOut(tween(210), targetScale = 0.992f))
                    },
                    label = "page-transition"
                ) { target ->
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
    val visibleConversations = remember(vm.conversations.size, query, vm.conversations.firstOrNull()?.updatedAt) {
        if (query.isBlank()) vm.conversations.toList()
        else vm.conversations.filter { it.title.contains(query.trim(), ignoreCase = true) || it.messages.any { message -> message.content.contains(query.trim(), ignoreCase = true) } }
    }

    ModalDrawerSheet(
        drawerContainerColor = Surface,
        drawerContentColor = Ink,
        modifier = Modifier.widthIn(max = 340.dp).fillMaxHeight()
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Night), contentAlignment = Alignment.Center) {
                    Text("A", color = Color.White, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("ADChat", style = MaterialTheme.typography.titleLarge)
                    Text("你的 AI 工作台", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭侧栏") }
            }

            Button(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (vm.isChatLoading) Night else Accent)
            ) {
                if (vm.isChatLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(19.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = .24f),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Rounded.AddComment, null, Modifier.size(19.dp))
                }
                Spacer(Modifier.width(9.dp))
                Text(if (vm.isChatLoading) "\u5f53\u524d\u4efb\u52a1\u751f\u6210\u4e2d" else "\u65b0\u5efa\u5bf9\u8bdd", fontWeight = FontWeight.Bold)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(19.dp)) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清空搜索", Modifier.size(18.dp)) }
                },
                placeholder = { Text("搜索历史对话") },
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Canvas,
                    unfocusedContainerColor = Canvas,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("历史任务", color = MutedInk, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("${visibleConversations.size}", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (visibleConversations.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (query.isBlank()) Icons.Rounded.History else Icons.Rounded.SearchOff, null, tint = MutedInk)
                            Spacer(Modifier.height(8.dp))
                            Text(if (query.isBlank()) "还没有历史对话" else "没有匹配的对话", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(visibleConversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = currentPage == AppPage.Chat && vm.activeConversationId == conversation.id,
                        generating = vm.isChatLoading && vm.activeConversationId == conversation.id,
                        onClick = { onConversation(conversation.id) },
                        onRename = { renameCandidate = conversation; renameText = conversation.title },
                        onDelete = { deleteCandidate = conversation }
                    )
                }
            }

            HorizontalDivider(color = Hairline)
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                AppPage.entries.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        icon = { Icon(item.icon, null) },
                        selected = currentPage == item,
                        onClick = { onNavigate(item) },
                        shape = RoundedCornerShape(14.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = AccentSoft,
                            selectedIconColor = Accent,
                            selectedTextColor = Ink
                        )
                    )
                }
            }
            Text("ADChat ${BuildConfig.VERSION_NAME}", color = MutedInk, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
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
