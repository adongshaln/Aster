package com.adong.adchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.adong.adchat.data.ChatIllustration
import com.adong.adchat.data.IllustrationStatus
import com.adong.adchat.ui.theme.*

@Composable
fun ChatIllustrationCard(
    image: ChatIllustration,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onLayoutChanged: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var preview by remember(image.id) { mutableStateOf(false) }
    var loadFailed by remember(image.source) { mutableStateOf(false) }
    LaunchedEffect(image.status) { onLayoutChanged() }
    Surface(
        color = Surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(0.8.dp, Hairline),
        modifier = modifier.fillMaxWidth().padding(top = 14.dp).testTag("chat-illustration-${image.status.name}")
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Image, null, Modifier.size(16.dp), tint = Accent)
                Spacer(Modifier.width(7.dp))
                Text("剧情插图", style = MaterialTheme.typography.labelMedium, color = MutedInk, modifier = Modifier.weight(1f))
                if (image.status == IllustrationStatus.Complete) {
                    IconButton(onClick = onSave) { Icon(Icons.Rounded.Download, "保存插图", tint = MutedInk) }
                } else if (image.isRunning) {
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "停止等待插图", tint = MutedInk) }
                } else Spacer(Modifier.height(42.dp))
            }
            if (image.status == IllustrationStatus.Complete) {
                AsyncImage(
                    model = image.source,
                    contentDescription = image.focus.ifBlank { image.title.ifBlank { "剧情插图" } },
                    contentScale = ContentScale.Fit,
                    onError = { loadFailed = true },
                    onSuccess = { loadFailed = false },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(SurfaceInset)
                        .clickable(onClickLabel = "放大插图") { preview = true }
                )
                if (loadFailed) Text("无法读取本地图片", color = MutedInk, modifier = Modifier.padding(14.dp))
                Text(image.title.ifBlank { "剧情瞬间" }, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, color = Ink, modifier = Modifier.padding(14.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (image.isRunning) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        when (image.status) {
                            IllustrationStatus.Planning -> "正在提炼画面…"
                            IllustrationStatus.Queued -> "等待绘图…"
                            IllustrationStatus.Generating -> "正在生成插图…"
                            IllustrationStatus.Saving -> "正在保存插图…"
                            IllustrationStatus.Cancelled -> "已停止等待插图"
                            else -> "插图未完成"
                        },
                        color = Ink, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
                    )
                    if (!image.isRunning) TextButton(onClick = onRetry) { Text("重试", color = Accent) }
                }
                if (image.error.isNotBlank()) Text(image.error, style = MaterialTheme.typography.bodySmall, color = MutedInk,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp))
                else Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (preview) {
        Dialog(onDismissRequest = { preview = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val transform = rememberTransformableState { zoom, pan, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale <= 1f) Offset.Zero else offset + pan
            }
            Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.fillMaxSize().transformable(transform), contentAlignment = Alignment.Center) {
                        AsyncImage(model = image.source, contentDescription = image.title, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                            })
                    }
                    IconButton(onClick = { preview = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Rounded.Close, "关闭预览", tint = Color.White)
                    }
                    FilledTonalButton(onClick = onSave, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("保存到相册")
                    }
                }
            }
        }
    }
}
