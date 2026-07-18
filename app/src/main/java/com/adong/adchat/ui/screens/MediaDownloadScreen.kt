package com.adong.adchat.ui.screens

import android.Manifest
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.adong.adchat.media.*
import com.adong.adchat.ui.components.AdConfirmDialog
import com.adong.adchat.ui.media.*
import com.adong.adchat.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaDownloadScreen(vm: MediaDownloadViewModel, onOpenDrawer: () -> Unit) {
    val state by vm.state
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteCandidate by remember { mutableStateOf<MediaDownloadRecord?>(null) }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.onGalleryPermissionResult(it)
    }

    LaunchedEffect(state.notice) {
        state.notice?.let { snackbarHostState.showSnackbar(it); vm.consumeNotice() }
    }
    LaunchedEffect(state.galleryPermissionRecordId) {
        if (state.galleryPermissionRecordId != null) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                galleryPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                vm.onGalleryPermissionResult(true)
            }
        }
    }
    BackHandler(enabled = state.phase == MediaWorkspacePhase.Verification) { vm.cancelResolve() }

    Scaffold(
        containerColor = Canvas,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(data, containerColor = Night, contentColor = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, scaffoldPadding.calculateBottomPadding() + 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { MediaHeader(onOpenDrawer) }
            item {
                LinkInputCard(
                    value = state.input,
                    detectedPlatform = state.detectedPlatform,
                    busy = state.phase in setOf(MediaWorkspacePhase.Resolving, MediaWorkspacePhase.Verification, MediaWorkspacePhase.Downloading),
                    onValueChange = vm::updateInput,
                    onResolve = vm::resolve
                )
            }
            item {
                AnimatedContent(
                    targetState = state.phase,
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInVertically(tween(230, easing = FastOutSlowInEasing)) { it / 14 }) togetherWith
                            (fadeOut(tween(110)) + slideOutVertically(tween(150)) { -it / 18 })
                    },
                    label = "media-workspace"
                ) { phase ->
                    when (phase) {
                        MediaWorkspacePhase.Idle -> CompactCapabilities()
                        MediaWorkspacePhase.Resolving, MediaWorkspacePhase.Verification -> {
                            state.resolveRequest?.let { request ->
                                DouyinResolverCard(
                                    request, phase == MediaWorkspacePhase.Verification, state.resolverMessage,
                                    vm::onResolverProgress, vm::onVerificationRequired, vm::onResolved,
                                    vm::onResolveFailed, vm::cancelResolve
                                )
                            } ?: RemoteResolverCard(state.detectedPlatform, state.resolverMessage, vm::cancelResolve)
                        }
                        MediaWorkspacePhase.Ready, MediaWorkspacePhase.Downloading -> state.resolvedMedia?.let { media ->
                            val record = state.downloads.firstOrNull {
                                it.videoId == media.videoId && it.platform == media.platform &&
                                    (it.qualityLabel == media.qualityLabel || media.availableVariants.size <= 1)
                            }
                            ResolvedMediaCard(
                                media, phase == MediaWorkspacePhase.Downloading, state.savingToGallery, state.downloadProgress, record,
                                vm::selectVariant, vm::startDownload, vm::openDownload, vm::cancelDownload
                            )
                        }
                        MediaWorkspacePhase.Error -> ErrorCard(state.errorMessage, state.input.isNotBlank(), vm::resolve)
                    }
                }
            }
            item { DownloadSectionHeader(state.downloads.size) }
            if (state.downloads.isEmpty()) item { EmptyDownloadHistory() }
            else items(state.downloads, key = MediaDownloadRecord::id) { record ->
                DownloadHistoryItem(record, { vm.openDownload(record) }, { vm.shareDownload(record) }, { deleteCandidate = record })
            }
        }
    }

    deleteCandidate?.let { record ->
        AdConfirmDialog(
            title = "删除视频？",
            message = if (record.galleryUri.isBlank()) "应用内文件也会被删除。" else "应用内文件会被删除，相册中的副本会保留。",
            confirmLabel = "删除", dismissLabel = "保留",
            icon = Icons.Rounded.DeleteOutline, destructive = true,
            onConfirm = { vm.removeDownload(record.id, true); deleteCandidate = null },
            onDismiss = { deleteCandidate = null }
        )
    }
}

@Composable
private fun MediaHeader(onOpenDrawer: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(42.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = Surface, contentColor = Ink)) {
            Icon(Icons.Rounded.Menu, "打开侧栏")
        }
        Spacer(Modifier.width(11.dp))
        Text("下载", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MediaPlatform.entries.forEach { PlatformDot(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkInputCard(value: String, detectedPlatform: MediaPlatform?, busy: Boolean, onValueChange: (String) -> Unit, onResolve: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    Surface(color = Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Hairline), shadowElevation = 1.dp) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).heightIn(min = 58.dp),
                    placeholder = { Text("粘贴视频链接", color = MutedInk) },
                    leadingIcon = { PlatformIcon(detectedPlatform, Modifier.size(19.dp), platformColor(detectedPlatform)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            clipboard.getText()?.text?.takeIf(String::isNotBlank)?.let(onValueChange)
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }, enabled = !busy) { Icon(Icons.Rounded.ContentPaste, "粘贴", Modifier.size(20.dp), tint = MutedInk) }
                    },
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (value.isNotBlank() && !busy) { focusManager.clearFocus(); onResolve() }
                    }),
                    shape = RoundedCornerShape(17.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Canvas, unfocusedContainerColor = Canvas, disabledContainerColor = Canvas,
                        focusedBorderColor = Accent.copy(alpha = .55f), unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(9.dp))
                RoundResolveButton(value.isNotBlank() && !busy, busy) {
                    focusManager.clearFocus()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onResolve()
                }
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("自动识别", color = MutedInk, fontSize = 12.sp, modifier = Modifier.weight(1f))
                MediaPlatform.entries.forEach { PlatformMiniLabel(it, it == detectedPlatform) }
            }
        }
    }
}

@Composable
private fun RoundResolveButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .92f else 1f, spring(dampingRatio = .72f, stiffness = 680f), label = "resolve-button")
    IconButton(
        onClick = onClick, enabled = enabled, interactionSource = interaction,
        modifier = Modifier.size(52.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Accent, contentColor = Color.White,
            disabledContainerColor = AccentSoft, disabledContentColor = Accent.copy(alpha = .4f)
        )
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(21.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "解析", Modifier.size(27.dp))
    }
}

@Composable
private fun CompactCapabilities() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Sage, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text("无水印优先 · 多路加速 · 断点续传", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RemoteResolverCard(platform: MediaPlatform?, message: String, onCancel: () -> Unit) {
    Surface(color = Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Hairline)) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(platformSoftColor(platform)), contentAlignment = Alignment.Center) {
                    PlatformIcon(platform, Modifier.size(21.dp), platformColor(platform))
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(platform?.displayName ?: "视频", fontWeight = FontWeight.Bold)
                    Text(message.ifBlank { "正在解析" }, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Close, "取消", tint = MutedInk, modifier = Modifier.size(19.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape), color = platformColor(platform), trackColor = platformSoftColor(platform))
        }
    }
}

@Composable
private fun DouyinResolverCard(
    request: DouyinResolveRequest,
    verificationVisible: Boolean,
    message: String,
    onProgress: (String) -> Unit,
    onVerificationRequired: (String) -> Unit,
    onResolved: (ResolvedMedia) -> Unit,
    onFailure: (String) -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = Surface, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, Hairline),
        shadowElevation = if (verificationVisible) 5.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(if (verificationVisible) AccentSoft else SageSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (verificationVisible) Icons.Rounded.Security else Icons.Rounded.MusicNote, null, tint = if (verificationVisible) Accent else Sage, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (verificationVisible) "完成验证" else "抖音", fontWeight = FontWeight.Bold)
                    Text(message.ifBlank { request.message }, color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Close, "取消", tint = MutedInk, modifier = Modifier.size(19.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(if (verificationVisible) 500.dp else 150.dp).clip(RoundedCornerShape(17.dp)).background(Canvas)
            ) {
                DouyinResolverWebView(request, Modifier.fillMaxSize(), onProgress, onVerificationRequired, onResolved, onFailure)
                if (!verificationVisible) {
                    Column(Modifier.fillMaxSize().background(Canvas), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("正在读取视频信息", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResolvedMediaCard(
    media: ResolvedMedia,
    downloading: Boolean,
    savingToGallery: Boolean,
    progress: MediaDownloadProgress,
    downloadedRecord: MediaDownloadRecord?,
    onVariantSelected: (String) -> Unit,
    onDownload: () -> Unit,
    onOpenDownloaded: (MediaDownloadRecord) -> Unit,
    onCancel: () -> Unit
) {
    var previewVisible by remember(media.mediaUrl) { mutableStateOf(false) }
    val needsWatermarkUpgrade = downloadedRecord != null && media.prefersWatermarkFree && !downloadedRecord.watermarkFree
    val alreadyDownloaded = downloadedRecord != null && !needsWatermarkUpgrade
    BackHandler(enabled = previewVisible) { previewVisible = false }
    LaunchedEffect(downloading) { if (downloading) previewVisible = false }

    Surface(color = Surface, shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, Hairline), shadowElevation = 2.dp) {
        Column {
            AnimatedContent(
                targetState = previewVisible,
                transitionSpec = { (fadeIn(tween(190)) + scaleIn(tween(210), initialScale = .98f)) togetherWith fadeOut(tween(120)) },
                label = "preview"
            ) { showingPreview ->
                if (showingPreview) {
                    MediaPreviewPlayer(media, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { previewVisible = false }
                } else {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color(0xFFEDE9E3)).clickable { previewVisible = true },
                            contentAlignment = Alignment.Center
                        ) {
                            MediaThumbnail(
                                url = media.thumbnailUrl,
                                fallbackUrl = media.thumbnailFallbackUrl,
                                referer = media.referer,
                                userAgent = media.userAgent,
                                cookieHeader = media.cookieHeader,
                                videoSource = media.mediaUrl,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(color = Night.copy(alpha = .72f), shape = CircleShape) {
                                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "预览", tint = Color.White, modifier = Modifier.size(24.dp)) }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            PlatformBadge(media.platform)
                            Spacer(Modifier.height(7.dp))
                            Text(media.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, lineHeight = 20.sp)
                            if (media.author.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(media.author, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MutedInk, fontSize = 12.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                buildString {
                                    append(media.qualityLabel.ifBlank { "原始画质" })
                                    if (media.expectedFileSize > 0L) append(" · ${formatBytes(media.expectedFileSize)}")
                                    if (media.prefersWatermarkFree) append(" · 无水印")
                                },
                                color = platformColor(media.platform), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (media.availableVariants.size > 1 && !downloading) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    media.availableVariants.forEach { variant ->
                        val selected = variant.mediaUrl == media.mediaUrl
                        Surface(
                            color = if (selected) platformSoftColor(media.platform) else Canvas,
                            contentColor = if (selected) platformColor(media.platform) else MutedInk,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, if (selected) platformColor(media.platform).copy(alpha = .24f) else Hairline),
                            modifier = Modifier.clickable { onVariantSelected(variant.id) }
                        ) {
                            Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (selected) { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                                Text(variant.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                if (downloading) DownloadProgressBlock(progress, savingToGallery, onCancel)
                else Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(
                        onClick = { previewVisible = true }, modifier = Modifier.weight(.4f).height(46.dp),
                        shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, Hairline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("预览")
                    }
                    PrimaryButton(
                        if (alreadyDownloaded) "打开" else "下载",
                        if (alreadyDownloaded) Icons.AutoMirrored.Rounded.OpenInNew else Icons.Rounded.Download,
                        Modifier.weight(.6f)
                    ) { if (alreadyDownloaded) downloadedRecord?.let(onOpenDownloaded) else onDownload() }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 620f), label = "primary-scale")
    Button(
        onClick = onClick, interactionSource = interaction,
        modifier = modifier.height(46.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
    ) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DownloadProgressBlock(progress: MediaDownloadProgress, savingToGallery: Boolean, onCancel: () -> Unit) {
    val animatedProgress by animateFloatAsState(progress.fraction, spring(dampingRatio = .85f, stiffness = 420f), label = "download-progress")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        savingToGallery -> "正在保存到相册"
                        progress.totalBytes > 0L -> "${(animatedProgress * 100).toInt()}%"
                        else -> "正在连接"
                    },
                    fontWeight = FontWeight.Bold
                )
                Text(
                    buildString {
                        append(formatBytes(progress.downloadedBytes))
                        if (progress.totalBytes > 0L) append(" / ${formatBytes(progress.totalBytes)}")
                        if (progress.bytesPerSecond > 0L) append(" · ${formatSpeed(progress.bytesPerSecond)}")
                        if (progress.connectionCount > 1) append(" · ${progress.connectionCount} 路")
                    }, color = MutedInk, fontSize = 12.sp
                )
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(38.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = DangerSoft, contentColor = Danger)) {
                Icon(Icons.Rounded.Close, "取消", Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(9.dp))
        if (progress.totalBytes > 0L) {
            LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Accent, trackColor = AccentSoft)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Accent, trackColor = AccentSoft)
        }
    }
}

@Composable
private fun ErrorCard(message: String, canRetry: Boolean, onRetry: () -> Unit) {
    Surface(color = DangerSoft, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Danger.copy(alpha = .15f))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = Danger, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
            Text(message, color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (canRetry) IconButton(onClick = onRetry, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.Refresh, "重试", tint = Danger, modifier = Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun DownloadSectionHeader(count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp, start = 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.History, null, tint = MutedInk, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
        Text("最近", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text(count.toString(), color = MutedInk, fontSize = 12.sp)
    }
}

@Composable
private fun EmptyDownloadHistory() {
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Download, null, tint = MutedInk.copy(alpha = .55f), modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
        Text("暂无下载", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DownloadHistoryItem(record: MediaDownloadRecord, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = Surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Hairline),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(76.dp, 48.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFEDE9E3)), contentAlignment = Alignment.Center) {
                MediaThumbnail(
                    url = if (record.platform == MediaPlatform.Bilibili) BilibiliMediaPolicy.normalizeThumbnail(record.thumbnailUrl) else record.thumbnailUrl,
                    fallbackUrl = record.thumbnailFallbackUrl,
                    referer = record.sourceUrl,
                    userAgent = if (record.platform == MediaPlatform.Bilibili) BILIBILI_IMAGE_USER_AGENT else "",
                    cookieHeader = "",
                    videoSource = record.filePath,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(record.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(record.platform.displayName)
                        if (record.qualityLabel.isNotBlank()) append(" · ${record.qualityLabel}")
                        append(" · ${formatBytes(record.fileSize)} · ${formatDate(record.createdAt)}")
                    }, color = MutedInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Share, "分享", tint = MutedInk, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = MutedInk, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun MediaThumbnail(
    url: String,
    fallbackUrl: String = "",
    referer: String,
    userAgent: String,
    cookieHeader: String,
    videoSource: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val candidates = remember(url, fallbackUrl) {
        buildList {
            fun addCandidate(value: String) {
                if (value.isBlank() || value in this) return
                add(value)
                val original = value.substringBeforeLast('@', missingDelimiterValue = "")
                if (original.startsWith("http") && original !in this) add(original)
            }
            addCandidate(url)
            addCandidate(fallbackUrl)
        }
    }
    var candidateIndex by remember(url, fallbackUrl) { mutableIntStateOf(0) }
    var failed by remember(url, fallbackUrl) { mutableStateOf(candidates.isEmpty()) }
    val activeUrl = candidates.getOrNull(candidateIndex).orEmpty()
    val request = remember(activeUrl, referer, userAgent, cookieHeader) {
        if (activeUrl.isBlank()) null else ImageRequest.Builder(context)
            .data(activeUrl)
            .crossfade(180)
            .apply {
                if (userAgent.isNotBlank()) addHeader("User-Agent", userAgent)
                if (referer.isNotBlank()) addHeader("Referer", referer)
                if (cookieHeader.isNotBlank()) addHeader("Cookie", cookieHeader)
            }
            .build()
    }
    val frameSource = videoSource.takeIf { failed && it.isNotBlank() }.orEmpty()
    val frameBitmap by produceState<android.graphics.Bitmap?>(null, frameSource, referer, userAgent, cookieHeader) {
        if (frameSource.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    if (frameSource.startsWith("http://", true) || frameSource.startsWith("https://", true)) {
                        val headers = buildMap {
                            if (userAgent.isNotBlank()) put("User-Agent", userAgent)
                            if (referer.isNotBlank()) put("Referer", referer)
                            if (cookieHeader.isNotBlank()) put("Cookie", cookieHeader)
                        }
                        retriever.setDataSource(frameSource, headers)
                    } else {
                        retriever.setDataSource(frameSource)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                    } else {
                        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }
    if (request != null && !failed) {
        AsyncImage(
            model = request,
            contentDescription = "视频封面",
            modifier = modifier,
            contentScale = ContentScale.Crop,
            onSuccess = { failed = false },
            onError = {
                if (candidateIndex < candidates.lastIndex) candidateIndex += 1
                else failed = true
            }
        )
    }
    if ((request == null || failed) && frameBitmap != null) {
        Image(
            bitmap = frameBitmap!!.asImageBitmap(),
            contentDescription = "视频首帧",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else if (request == null || failed) {
        Box(modifier.background(Color(0xFFEDE9E3)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.VideoFile, null, tint = MutedInk.copy(alpha = .55f), modifier = Modifier.size(25.dp))
        }
    }
}

@Composable
private fun PlatformBadge(platform: MediaPlatform) {
    Row(Modifier.clip(CircleShape).background(platformSoftColor(platform)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PlatformIcon(platform, Modifier.size(13.dp), platformColor(platform)); Spacer(Modifier.width(4.dp))
        Text(platform.displayName, color = platformColor(platform), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlatformMiniLabel(platform: MediaPlatform, selected: Boolean) {
    val color = if (selected) platformColor(platform) else MutedInk
    Row(
        Modifier.clip(CircleShape).background(if (selected) platformSoftColor(platform) else Color.Transparent).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlatformIcon(platform, Modifier.size(12.dp), color); Spacer(Modifier.width(3.dp))
        Text(platform.displayName, color = color, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun PlatformDot(platform: MediaPlatform) {
    Box(Modifier.size(26.dp).clip(CircleShape).background(platformSoftColor(platform)), contentAlignment = Alignment.Center) {
        PlatformIcon(platform, Modifier.size(14.dp), platformColor(platform))
    }
}

@Composable
private fun PlatformIcon(platform: MediaPlatform?, modifier: Modifier, tint: Color) {
    Icon(
        when (platform) {
            MediaPlatform.Douyin -> Icons.Rounded.MusicNote
            MediaPlatform.Twitter -> Icons.Rounded.AlternateEmail
            MediaPlatform.Bilibili -> Icons.Rounded.SmartDisplay
            MediaPlatform.Direct -> Icons.Rounded.Link
            null -> Icons.Rounded.VideoFile
        }, null, modifier, tint
    )
}

private fun platformColor(platform: MediaPlatform?): Color = when (platform) {
    MediaPlatform.Douyin -> Color(0xFF2F6F62)
    MediaPlatform.Twitter -> Color(0xFF3D586E)
    MediaPlatform.Bilibili -> Color(0xFFD45C78)
    MediaPlatform.Direct -> Accent
    null -> MutedInk
}

private fun platformSoftColor(platform: MediaPlatform?): Color = when (platform) {
    MediaPlatform.Douyin -> Color(0xFFE4F1EC)
    MediaPlatform.Twitter -> Color(0xFFE8EEF3)
    MediaPlatform.Bilibili -> Color(0xFFF9E8ED)
    MediaPlatform.Direct -> AccentSoft
    null -> Color(0xFFF0EDE8)
}

private const val BILIBILI_IMAGE_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble(); var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit += 1 }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

private fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"
private fun formatDate(timestamp: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))



