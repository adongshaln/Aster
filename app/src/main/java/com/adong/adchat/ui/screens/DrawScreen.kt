package com.adong.adchat.ui.screens

import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.adong.adchat.data.ArtworkCollection
import com.adong.adchat.data.GeneratedImage
import com.adong.adchat.data.ReferenceImageAttachment
import com.adong.adchat.data.groupArtworkCollections
import com.adong.adchat.ui.ImageGenerationPhase
import com.adong.adchat.ui.ImageWorkflow
import com.adong.adchat.ui.MainViewModel
import com.adong.adchat.ui.components.AdConfirmDialog
import com.adong.adchat.ui.components.QuickModelSwitcher
import com.adong.adchat.ui.components.RouteKind
import com.adong.adchat.ui.theme.*

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun DrawScreen(vm: MainViewModel, onOpenDrawer: () -> Unit, onOpenSettings: () -> Unit) {
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSwitcher by remember { mutableStateOf(false) }
    var selectedArtwork by remember { mutableStateOf<GeneratedImage?>(null) }
    var previewReference by remember { mutableStateOf<ReferenceImageAttachment?>(null) }
    var replacingReferenceId by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<GeneratedImage?>(null) }
    var observedImageCount by remember { mutableIntStateOf(vm.images.size) }
    val galleryCollections by remember { derivedStateOf { groupArtworkCollections(vm.images.toList()) } }
    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(2)) { uris ->
        if (uris.isNotEmpty()) vm.attachReferenceImages(uris)
    }
    val mangaReferencePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) vm.attachReferenceImages(uris)
    }
    val singleReferencePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val replacementId = replacingReferenceId
        replacingReferenceId = null
        if (uri != null) {
            if (replacementId == null) vm.addReferenceImage(uri) else vm.replaceReferenceImage(replacementId, uri)
        }
    }

    LaunchedEffect(vm.images.size) {
        val newArtworkAdded = vm.images.size > observedImageCount
        observedImageCount = vm.images.size
        if (newArtworkAdded) {
            delay(180)
            listState.scrollToItem(3)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "header") {
            DrawHeader(
                profileName = vm.imageProfile.name,
                model = vm.imageProfile.imageModel,
                onSwitch = { showSwitcher = true },
                onOpenDrawer = onOpenDrawer
            )
        }
        item(key = "manga-translation") {
            MangaTranslationToggle(
                active = vm.imageWorkflow == ImageWorkflow.MangaTranslation,
                locked = vm.isImageLoading,
                onToggle = { enabled ->
                    focus.clearFocus()
                    if (enabled) {
                        vm.activateMangaTranslation()
                        scope.launch { listState.animateScrollToItem(2) }
                    } else {
                        vm.exitMangaTranslation()
                    }
                }
            )
        }
        item(key = "studio") {
            PromptStudio(
                prompt = vm.drawPrompt,
                onPrompt = { vm.drawPrompt = it },
                size = vm.imageSize,
                onSize = { vm.imageSize = it },
                style = vm.imageStyle,
                onStyle = { vm.imageStyle = it },
                mangaTranslation = vm.imageWorkflow == ImageWorkflow.MangaTranslation,
                referenceImages = vm.referenceImages,
                referenceLoading = vm.isReferenceLoading,
                onPickReferences = {
                    if (vm.imageWorkflow == ImageWorkflow.MangaTranslation) {
                        mangaReferencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                },
                onAddReference = {
                    replacingReferenceId = null
                    singleReferencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPreviewReference = { previewReference = it },
                onReplaceReference = { id ->
                    replacingReferenceId = id
                    singleReferencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemoveReference = vm::removeReferenceImage,
                loading = vm.isImageLoading,
                generationPhase = vm.imageGenerationPhase,
                generationStartedAt = vm.imageGenerationStartedAt,
                batchCompleted = vm.imageBatchCompleted,
                batchTotal = vm.imageBatchTotal,
                error = vm.imageError,
                onDismissError = vm::dismissImageError,
                onGenerate = { focus.clearFocus(); vm.generateImage() },
                onStop = vm::stopImageGeneration
            )
        }
        if (vm.images.isEmpty()) {
            item(key = "empty-gallery") { EmptyGallery() }
        } else {
            item(key = "gallery-title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近创作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${vm.images.size} 张", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                }
            }
            items(
                items = galleryCollections,
                key = { it.key },
                contentType = { if (it.isSeries) "artwork-series" else "artwork" }
            ) { collection ->
                if (collection.isSeries) {
                    MangaArtworkSeriesCard(
                        collection = collection,
                        modifier = Modifier.animateItem(),
                        onPreview = { selectedArtwork = it },
                        onSave = vm::saveImageToGallery,
                        onReuse = { image ->
                            vm.reuseImagePrompt(image)
                            scope.launch { listState.animateScrollToItem(2) }
                        },
                        onDelete = { deleteCandidate = it }
                    )
                } else {
                    val image = collection.images.first()
                    ArtworkCard(
                        image = image,
                        modifier = Modifier.animateItem(),
                        onPreview = { selectedArtwork = image },
                        onSave = { vm.saveImageToGallery(image) },
                        onReuse = {
                            vm.reuseImagePrompt(image)
                            scope.launch { listState.animateScrollToItem(2) }
                        },
                        onDelete = { deleteCandidate = image }
                    )
                }
            }
        }
        item(key = "bottom-space") { Spacer(Modifier.height(18.dp)) }
    }
    if (showSwitcher) {
        QuickModelSwitcher(
            kind = RouteKind.Image,
            vm = vm,
            onDismiss = { showSwitcher = false },
            onManageApis = onOpenSettings
        )
    }
    selectedArtwork?.let { image ->
        ImageLightbox(
            source = image.source,
            title = "作品预览",
            subtitle = "${image.profileName} · ${image.model}",
            prompt = image.prompt,
            onDismiss = { selectedArtwork = null },
            onSave = { vm.saveImageToGallery(image) },
            onReuse = {
                vm.reuseImagePrompt(image)
                selectedArtwork = null
                scope.launch { listState.animateScrollToItem(2) }
            }
        )
    }
    previewReference?.let { reference ->
        ImageLightbox(
            source = reference.uri,
            title = "参考图预览",
            subtitle = listOf(reference.name, formatFileSize(reference.size)).joinToString(" · "),
            prompt = "可以双击放大，或用双指缩放与移动查看细节。",
            onDismiss = { previewReference = null }
        )
    }
    deleteCandidate?.let { image ->
        AdConfirmDialog(
            title = "删除这张作品？",
            message = "作品记录和本地缓存会被永久删除，系统相册中的副本不受影响。",
            confirmLabel = "删除",
            dismissLabel = "取消",
            icon = Icons.Rounded.DeleteOutline,
            destructive = true,
            onConfirm = { vm.deleteImage(image.id); deleteCandidate = null },
            onDismiss = { deleteCandidate = null }
        )
    }
}

@Composable
private fun DrawHeader(profileName: String, model: String, onSwitch: () -> Unit, onOpenDrawer: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.offset(x = (-8).dp, y = (-4).dp)) { Icon(Icons.Rounded.Menu, "\u6253\u5f00\u4fa7\u680f") }
            Column(Modifier.weight(1f)) {
                Text("视觉创作室", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text("独立绘图路由 · 内置漫画原位翻译", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(onClick = onSwitch, color = Surface, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Palette, null, Modifier.size(17.dp), tint = Accent)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(profileName, style = MaterialTheme.typography.labelLarge)
                    Text(model.ifBlank { "点击选择绘图模型" }, color = MutedInk, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("切换", color = Accent, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Rounded.UnfoldMore, null, tint = Accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MangaTranslationToggle(
    active: Boolean,
    locked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        color = if (active) AccentSoft else Surface,
        contentColor = Ink,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (active) Accent.copy(alpha = .22f) else Hairline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(if (active) Accent else Canvas),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Translate, null, tint = if (active) Color.White else Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("漫画翻译", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(if (active) "模式已开启" else "模式未开启", color = if (active) Accent else MutedInk, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = active,
                onCheckedChange = onToggle,
                enabled = !locked,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFD7D2CC),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun PromptStudio(
    prompt: String,
    onPrompt: (String) -> Unit,
    size: String,
    onSize: (String) -> Unit,
    style: String,
    onStyle: (String) -> Unit,
    mangaTranslation: Boolean,
    referenceImages: List<ReferenceImageAttachment>,
    referenceLoading: Boolean,
    onPickReferences: () -> Unit,
    onAddReference: () -> Unit,
    onPreviewReference: (ReferenceImageAttachment) -> Unit,
    onReplaceReference: (String) -> Unit,
    onRemoveReference: (String) -> Unit,
    loading: Boolean,
    generationPhase: ImageGenerationPhase,
    generationStartedAt: Long,
    batchCompleted: Int,
    batchTotal: Int,
    error: String?,
    onDismissError: () -> Unit,
    onGenerate: () -> Unit,
    onStop: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(loading, generationStartedAt) {
        while (loading && generationStartedAt > 0L) {
            elapsedMs = (SystemClock.elapsedRealtime() - generationStartedAt).coerceAtLeast(0L)
            delay(250)
        }
        if (!loading) elapsedMs = 0L
    }

    Surface(
        color = Surface,
        contentColor = Ink,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 2.dp,
        modifier = Modifier.animateContentSize(tween(220, easing = FastOutSlowInEasing))
    ) {
        Column(Modifier.padding(18.dp)) {
            if (!mangaTranslation) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(AccentSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(9.dp))
                    Text("创作提示", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (prompt.isNotBlank()) {
                        Text("${prompt.length} 字", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPrompt,
                    readOnly = loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp, max = 230.dp),
                    placeholder = { Text("描述主体、场景、光线、色彩和氛围…", color = Color(0xFF918C85)) },
                    minLines = 4,
                    maxLines = 10,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        focusedContainerColor = Canvas,
                        unfocusedContainerColor = Canvas,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (mangaTranslation) "漫画原图（必选）" else "参考图（可选）", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${referenceImages.size}/${if (mangaTranslation) 4 else 2}",
                    color = if (referenceImages.isNotEmpty()) Accent else MutedInk,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().animateContentSize(tween(190, easing = FastOutSlowInEasing)),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (referenceImages.isEmpty()) {
                    Surface(
                        onClick = {
                            if (!loading && !referenceLoading) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPickReferences()
                            }
                        },
                        enabled = !loading && !referenceLoading,
                        color = Canvas,
                        contentColor = Ink,
                        shape = RoundedCornerShape(17.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(AccentSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                if (referenceLoading) {
                                    CircularProgressIndicator(Modifier.size(21.dp), color = Accent, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.Collections, null, tint = Accent)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        referenceLoading -> "正在读取图片"
                                        mangaTranslation -> "添加 1–4 张漫画原图"
                                        else -> "添加 1–2 张参考图"
                                    },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    if (mangaTranslation) "可一次多选 · 每张最大 20 MB" else "可一次多选；每张最大 20 MB",
                                    color = MutedInk,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!referenceLoading) Icon(Icons.Rounded.ChevronRight, null, tint = MutedInk)
                        }
                    }
                } else {
                    referenceImages.forEachIndexed { index, reference ->
                        ReferenceImageRow(
                            reference = reference,
                            index = index,
                            locked = loading || referenceLoading,
                            onPreview = { onPreviewReference(reference) },
                            onReplace = { onReplaceReference(reference.id) },
                            onRemove = { onRemoveReference(reference.id) }
                        )
                    }
                    val maximum = if (mangaTranslation) 4 else 2
                    if (referenceImages.size < maximum) {
                        Surface(
                            onClick = {
                                if (!loading && !referenceLoading) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onAddReference()
                                }
                            },
                            enabled = !loading && !referenceLoading,
                            color = Color.Transparent,
                            contentColor = Accent,
                            shape = RoundedCornerShape(15.dp),
                            border = BorderStroke(1.dp, Accent.copy(alpha = .3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    if (mangaTranslation) "继续添加漫画页" else "添加第二张参考图",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                    Text(
                        if (mangaTranslation) {
                            "最多 4 张并发翻译，全部完成后按上传顺序返回。"
                        } else {
                            "多图能力取决于当前绘图模型与 API；上传顺序会被保留。"
                        },
                        color = MutedInk,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            if (!mangaTranslation) {
                StudioLabel("风格")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf(
                        Triple("原始", "忠于提示", Icons.Rounded.Tune),
                        Triple("摄影", "真实光影", Icons.Rounded.PhotoCamera),
                        Triple("插画", "精致绘制", Icons.Rounded.Brush),
                        Triple("电影", "戏剧氛围", Icons.Rounded.Movie),
                        Triple("动漫", "细腻线稿", Icons.Rounded.Animation)
                    ).forEach { (name, detail, icon) ->
                        StyleChoiceCard(
                            name,
                            detail,
                            icon,
                            selected = style == name,
                            onClick = {
                                if (!loading) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onStyle(name)
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(17.dp))
                StudioLabel("画布比例")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf("1024x1024" to "1:1", "1536x1024" to "3:2", "1024x1536" to "2:3").forEach { (value, label) ->
                        CanvasChoiceCard(
                            value,
                            label,
                            selected = size == value,
                            onClick = {
                                if (!loading) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSize(value)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            AnimatedVisibility(visible = error != null) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Surface(color = DangerSoft, contentColor = Danger, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = onDismissError, Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Close, "关闭", Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = loading) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    GenerationStatus(
                        generationPhase,
                        elapsedMs,
                        referenceImages.size,
                        mangaTranslation,
                        batchCompleted,
                        batchTotal
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    haptics.performHapticFeedback(if (loading) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove)
                    if (loading) onStop() else onGenerate()
                },
                enabled = loading || (prompt.isNotBlank() && !referenceLoading && (!mangaTranslation || referenceImages.size in 1..4)),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (loading) Night else Accent,
                    disabledContainerColor = Hairline
                )
            ) {
                AnimatedContent(
                    targetState = loading,
                    transitionSpec = {
                        (fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.78f)) togetherWith
                            (fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.78f))
                    },
                    label = "generate-stop"
                ) { isLoading ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isLoading) Icons.Rounded.Stop else if (mangaTranslation) Icons.Rounded.Translate else Icons.Rounded.AutoAwesome,
                            null,
                            Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (isLoading) "停止等待" else if (mangaTranslation) "开始翻译漫画" else "开始生成",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceImageRow(
    reference: ReferenceImageAttachment,
    index: Int,
    locked: Boolean,
    onPreview: () -> Unit,
    onReplace: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(color = Canvas, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AsyncImage(
                    model = imageModel(reference.uri),
                    contentDescription = "参考图 ${index + 1}",
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(13.dp)).background(Hairline)
                        .clickable(onClick = onPreview),
                    contentScale = ContentScale.Crop
                )
                Surface(
                    color = Night.copy(alpha = .88f),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = (-4).dp, y = (-4).dp)
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = !locked, onClick = onReplace)
                    .padding(vertical = 4.dp)
            ) {
                Text(reference.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(formatFileSize(reference.size), color = MutedInk, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(3.dp))
                Text("点击名称替换 · 点击图片预览", color = Accent, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onRemove, enabled = !locked, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.Close, "移除参考图 ${index + 1}", tint = if (locked) MutedInk else Danger, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun GenerationStatus(
    phase: ImageGenerationPhase,
    elapsedMs: Long,
    referenceCount: Int,
    mangaTranslation: Boolean,
    batchCompleted: Int,
    batchTotal: Int
) {
    val title = if (mangaTranslation) {
        when (phase) {
            ImageGenerationPhase.UploadingReference -> "正在并发翻译 ${batchCompleted.coerceAtMost(batchTotal)}/$batchTotal"
            ImageGenerationPhase.Saving -> "正在按上传顺序整理结果"
            else -> "正在准备漫画翻译"
        }
    } else {
        when (phase) {
            ImageGenerationPhase.UploadingReference -> if (referenceCount > 1) "正在上传 $referenceCount 张参考图并渲染" else "正在上传参考图并渲染"
            ImageGenerationPhase.Rendering -> "模型正在渲染画面"
            ImageGenerationPhase.Saving -> "正在整理并保存作品"
            ImageGenerationPhase.Idle -> if (referenceCount > 0) "参考图已就绪" else "准备生成"
        }
    }
    val seconds = elapsedMs / 1000
    val elapsed = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    Surface(color = AccentSoft, contentColor = Ink, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                    Icon(if (mangaTranslation) Icons.Rounded.Translate else Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = Accent)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (mangaTranslation) {
                            if (elapsedMs >= 3 * 60_000L) "已用时 $elapsed · 服务端仍在处理，请勿重复提交"
                            else "已用时 $elapsed · 复杂页面可能需要数分钟"
                        } else {
                            "已用时 $elapsed · 停止等待后服务端仍可能继续处理"
                        },
                        color = MutedInk,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            if (mangaTranslation && batchTotal > 0) {
                LinearProgressIndicator(
                    progress = { (batchCompleted.toFloat() / batchTotal.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = Accent,
                    trackColor = Color(0xFFF3CDC3)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = Accent,
                    trackColor = Color(0xFFF3CDC3)
                )
            }
        }
    }
}

private fun imageModel(source: String): Any = when {
    source.startsWith("file://") -> File(source.removePrefix("file://"))
    source.startsWith("content://") -> Uri.parse(source)
    source.startsWith("data:") -> Base64.decode(source.substringAfter("base64,"), Base64.DEFAULT)
    else -> source
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun StudioLabel(text: String) {
    Text(text, color = MutedInk, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun StyleChoiceCard(text: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Night else Canvas,
        contentColor = if (selected) Color.White else Ink,
        border = if (selected) BorderStroke(1.dp, Color(0xFF47433F)) else null,
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.width(112.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) Color(0xFF3A3835) else Surface), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = if (selected) Accent else MutedInk)
            }
            Spacer(Modifier.height(10.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, color = if (selected) Color(0xFFBDB8B2) else MutedInk, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CanvasChoiceCard(value: String, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        color = if (selected) AccentSoft else Canvas,
        contentColor = Ink,
        border = if (selected) BorderStroke(1.dp, Color(0xFFFFB9A7)) else null,
        shape = RoundedCornerShape(17.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(vertical = 11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.height(30.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val frame = when (label) {
                    "3:2" -> Modifier.width(38.dp).height(25.dp)
                    "2:3" -> Modifier.width(24.dp).height(34.dp)
                    else -> Modifier.size(30.dp)
                }
                Box(frame.clip(RoundedCornerShape(5.dp)).background(if (selected) Accent else Color(0xFFD8D3CC)))
                if (selected) Box(Modifier.align(Alignment.TopEnd).padding(end = 9.dp).size(17.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = Color.White) }
            }
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(value, color = MutedInk, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyGallery() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Collections, null, tint = Accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("第一张作品，等待你的想法", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("生成结果会保留在本次使用记录中", color = MutedInk, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MangaArtworkSeriesCard(
    collection: ArtworkCollection,
    modifier: Modifier = Modifier,
    onPreview: (GeneratedImage) -> Unit,
    onSave: (GeneratedImage) -> Unit,
    onReuse: (GeneratedImage) -> Unit,
    onDelete: (GeneratedImage) -> Unit
) {
    var expanded by rememberSaveable(collection.seriesId) { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "series-arrow")
    val first = collection.images.first()
    Column(modifier.animateContentSize(tween(260, easing = FastOutSlowInEasing))) {
        Surface(color = Surface, shape = RoundedCornerShape(24.dp), shadowElevation = 1.dp) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded }
                        .padding(horizontal = 15.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Translate, null, Modifier.size(21.dp), tint = Accent)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(collection.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "同批次系列 · 已完成 ${collection.images.size}/${collection.expectedTotal} 页",
                            color = MutedInk,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Canvas), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.ExpandMore,
                            if (expanded) "折叠系列" else "展开系列",
                            Modifier.size(20.dp).graphicsLayer { rotationZ = arrowRotation },
                            tint = MutedInk
                        )
                    }
                }
                AnimatedVisibility(visible = !expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(100))) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 13.dp)) {
                        Row(
                            Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(18.dp)),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            collection.images.take(4).forEach { image ->
                                Box(
                                    Modifier.weight(1f).fillMaxHeight().background(Color(0xFFE9E5DF))
                                        .clickable { onPreview(image) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = imageModel(image.source),
                                        contentDescription = "系列第 ${image.seriesIndex + 1} 页",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    Surface(
                                        color = Night.copy(alpha = .76f),
                                        contentColor = Color.White,
                                        shape = CircleShape,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(7.dp)
                                    ) {
                                        Text(
                                            "${image.seriesIndex + 1}",
                                            Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MetadataPill(first.profileName)
                            Spacer(Modifier.width(7.dp))
                            MetadataPill(first.model)
                            Spacer(Modifier.weight(1f))
                            Text("点击展开", color = Accent, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(190)) + scaleIn(tween(240), initialScale = .98f),
            exit = fadeOut(tween(110)) + scaleOut(tween(150), targetScale = .98f)
        ) {
            Column {
                collection.images.forEach { image ->
                    Spacer(Modifier.height(12.dp))
                    ArtworkCard(
                        image = image,
                        onPreview = { onPreview(image) },
                        onSave = { onSave(image) },
                        onReuse = { onReuse(image) },
                        onDelete = { onDelete(image) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtworkCard(
    image: GeneratedImage,
    modifier: Modifier = Modifier,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onReuse: () -> Unit,
    onDelete: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val ratio = when (image.size) { "1536x1024" -> 1.5f; "1024x1536" -> 0.667f; else -> 1f }
    var imageLoaded by remember(image.source) { mutableStateOf(false) }
    var imageFailed by remember(image.source) { mutableStateOf(false) }
    val imageAlpha by animateFloatAsState(if (imageLoaded) 1f else 0f, tween(240), label = "artwork-image")
    Surface(
        color = Surface,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color(0xFFE9E5DF)).clickable(onClick = onPreview),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imageModel(image.source),
                    contentDescription = image.prompt,
                    modifier = Modifier.fillMaxSize().alpha(imageAlpha),
                    contentScale = ContentScale.Crop,
                    onLoading = { imageLoaded = false; imageFailed = false },
                    onSuccess = { imageLoaded = true; imageFailed = false },
                    onError = { state ->
                        imageLoaded = false
                        imageFailed = true
                        Log.e("ADChatImage", "Artwork load failed: ${image.source.take(96)}", state.result.throwable)
                    }
                )
                if (!imageLoaded && !imageFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Accent)
                        Spacer(Modifier.height(8.dp))
                        Text("正在读取作品", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (imageFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.BrokenImage, null, Modifier.size(30.dp), tint = MutedInk)
                        Spacer(Modifier.height(7.dp))
                        Text("图片读取失败", color = MutedInk, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Surface(
                    color = Night.copy(alpha = 0.78f),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ZoomOutMap, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("查看", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
                Text(image.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    MetadataPill(image.profileName)
                    MetadataPill(image.style)
                    MetadataPill(when (image.size) { "1536x1024" -> "3:2"; "1024x1536" -> "2:3"; else -> "1:1" })
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ArtworkActionPill(
                        label = "复用",
                        icon = Icons.Rounded.Refresh,
                        color = Canvas,
                        contentColor = Ink,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onReuse()
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    ArtworkActionPill(
                        label = "保存",
                        icon = Icons.Rounded.Download,
                        color = AccentSoft,
                        contentColor = Accent,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSave()
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Canvas, contentColor = MutedInk)
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, "删除作品", Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtworkActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, color = color, contentColor = contentColor, shape = CircleShape) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ImageLightbox(
    source: String,
    title: String,
    subtitle: String,
    prompt: String,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)? = null,
    onReuse: (() -> Unit)? = null
) {
    var scale by remember(source) { mutableFloatStateOf(1f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var controlsVisible by remember { mutableStateOf(true) }
    var imageLoaded by remember(source) { mutableStateOf(false) }
    var imageFailed by remember(source) { mutableStateOf(false) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
        val maxPan = 520f * (nextScale - 1f)
        scale = nextScale
        offset = if (nextScale <= 1.01f) {
            Offset.Zero
        } else {
            Offset(
                (offset.x + panChange.x).coerceIn(-maxPan, maxPan),
                (offset.y + panChange.y).coerceIn(-maxPan, maxPan)
            )
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Night)) {
            AsyncImage(
                model = imageModel(source),
                contentDescription = title,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformState)
                    .pointerInput(source, scale) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    },
                contentScale = ContentScale.Fit,
                onLoading = { imageLoaded = false; imageFailed = false },
                onSuccess = { imageLoaded = true; imageFailed = false },
                onError = { state ->
                    imageLoaded = false
                    imageFailed = true
                    Log.e("ADChatImage", "Lightbox load failed: ${source.take(96)}", state.result.throwable)
                }
            )
            if (!imageLoaded && !imageFailed) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(34.dp), color = Accent, strokeWidth = 2.5.dp)
            }
            if (imageFailed) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.BrokenImage, null, Modifier.size(42.dp), tint = Color(0xFFBDB8B2))
                    Spacer(Modifier.height(10.dp))
                    Text("无法读取图片", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White)
                    ) {
                        Icon(Icons.Rounded.Close, "关闭预览")
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(subtitle, color = Color(0xFFBDB8B2), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Surface(color = Color.Black.copy(alpha = 0.45f), contentColor = Color.White, shape = CircleShape) {
                        Text("${String.format(Locale.ROOT, "%.1f", scale)}×", Modifier.padding(horizontal = 11.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.97f),
                exit = fadeOut(tween(120)) + scaleOut(tween(140), targetScale = 0.98f),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text(prompt, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(7.dp))
                        Text("双击缩放 · 双指移动 · 单击隐藏控件", color = Color(0xFFBDB8B2), style = MaterialTheme.typography.labelMedium)
                        if (onSave != null || onReuse != null) {
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (onReuse != null) {
                                    OutlinedButton(
                                        onClick = onReuse,
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                    ) {
                                        Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("复用参数")
                                    }
                                }
                                if (onSave != null) {
                                    Button(
                                        onClick = onSave,
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Accent)
                                    ) {
                                        Icon(Icons.Rounded.Download, null, Modifier.size(17.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("保存到相册")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataPill(text: String) {
    Surface(color = Canvas, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = MutedInk, style = MaterialTheme.typography.labelMedium)
    }
}









