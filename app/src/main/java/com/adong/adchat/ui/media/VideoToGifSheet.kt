package com.adong.adchat.ui.media

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adong.adchat.media.MediaDownloadRecord
import com.adong.adchat.media.MediaGallerySaveResult
import com.adong.adchat.media.gif.GifPreset
import com.adong.adchat.ui.components.AdModalDialog
import com.adong.adchat.ui.components.AsterLoadingIndicator
import com.adong.adchat.ui.components.AsterPillButton
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun VideoToGifSheet(
    vm: VideoToGifViewModel,
    onCreated: (MediaDownloadRecord) -> Unit,
    onRecordUpdated: (MediaDownloadRecord) -> Unit,
    onShare: (MediaDownloadRecord) -> Unit,
    onRequestLegacyGalleryPermission: (MediaDownloadRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val close = {
        vm.dismiss()
        onDismiss()
    }

    LaunchedEffect(state.phase, state.result?.id) {
        if (state.phase == VideoToGifState.Phase.Done) {
            state.result?.let(onCreated)
        }
    }

    AdModalDialog(
        title = "转为 GIF",
        subtitle = state.source?.title,
        icon = Icons.Rounded.GifBox,
        onDismiss = close,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (state.phase) {
                    VideoToGifState.Phase.Idle -> {
                        GifPreset.entries.forEach { preset ->
                            val selected = state.preset == preset
                            val interactionSource = remember(preset) { MutableInteractionSource() }
                            val fill by animateColorAsState(
                                if (selected) AccentSoft else Surface,
                                AsterSpring.editorial(),
                                label = "gif-preset-fill"
                            )
                            val border by animateColorAsState(
                                if (selected) Accent.copy(alpha = 0.5f) else Hairline,
                                AsterSpring.editorial(),
                                label = "gif-preset-border"
                            )
                            Surface(
                                onClick = { vm.update(preset = preset) },
                                interactionSource = interactionSource,
                                color = fill,
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(1.dp, border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .appleSpringPressable(pressedScale = 0.97f, interactionSource = interactionSource)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            preset.label,
                                            color = if (selected) OnAccentSoft else Ink,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "最长边 ${preset.widthPx}px · ${preset.fps} fps · 最长 ${preset.maxDurationMs / 1000}s",
                                            color = MutedInk,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Box(
                                        Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(if (selected) Accent else SurfaceInset),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) {
                                            Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = OnAccent)
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "超过时长上限时只转换视频开头；原视频不会修改。",
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    VideoToGifState.Phase.Converting -> {
                        Surface(
                            color = Surface,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Hairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                AsterLoadingIndicator(size = 30.dp)
                                Spacer(Modifier.height(12.dp))
                                Text("正在逐帧转换 ${(state.progress * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(
                                    progress = { state.progress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                                    color = Accent,
                                    trackColor = AccentSoft
                                )
                            }
                        }
                    }

                    VideoToGifState.Phase.Done -> {
                        val info = state.convertInfo
                        Surface(
                            color = Surface,
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, Hairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (info != null) {
                                    Text(
                                        "${info.width} × ${info.height} · ${info.frameCount} 帧 · ${formatGifDuration(info.durationMs)} · ${formatGifBytes(state.result?.fileSize ?: 0L)}",
                                        color = Ink,
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                Text("GIF 已加入下载历史。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    VideoToGifState.Phase.Failed -> {
                        Surface(
                            color = DangerSoft,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Danger.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                state.error ?: "转换失败",
                                modifier = Modifier.padding(16.dp),
                                color = Danger,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                state.error?.takeIf { state.phase != VideoToGifState.Phase.Failed }?.let {
                    Text(it, color = Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        actions = {
            when (state.phase) {
                VideoToGifState.Phase.Idle -> {
                    AsterPillButton(
                        text = "取消",
                        onClick = close,
                        modifier = Modifier.weight(1f).height(48.dp)
                    )
                    AsterPillButton(
                        text = "开始转换",
                        primary = true,
                        onClick = { vm.convert() },
                        modifier = Modifier.weight(1f).height(48.dp)
                    )
                }

                VideoToGifState.Phase.Converting -> {
                    AsterPillButton(
                        text = "取消转换",
                        onClick = { vm.cancel() },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                }

                VideoToGifState.Phase.Done -> {
                    AsterPillButton(
                        text = when {
                            state.savingToGallery -> "正在保存"
                            state.result?.galleryUri.isNullOrBlank() -> "保存到相册"
                            else -> "已保存"
                        },
                        enabled = !state.savingToGallery && state.result?.galleryUri.isNullOrBlank(),
                        onClick = {
                            val record = state.result ?: return@AsterPillButton
                            scope.launch {
                                when (val result = vm.saveToGallery()) {
                                    is MediaGallerySaveResult.Saved -> {
                                        onRecordUpdated(record.copy(galleryUri = result.uri))
                                        Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                                    }
                                    MediaGallerySaveResult.PermissionRequired -> onRequestLegacyGalleryPermission(record)
                                    is MediaGallerySaveResult.Failed -> Toast.makeText(context, result.reason, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp)
                    )
                    AsterPillButton(
                        text = "分享",
                        primary = true,
                        onClick = { state.result?.let { record -> close(); onShare(record) } },
                        modifier = Modifier.weight(1f).height(48.dp)
                    )
                }

                VideoToGifState.Phase.Failed -> {
                    AsterPillButton(
                        text = "关闭",
                        onClick = close,
                        modifier = Modifier.weight(1f).height(48.dp)
                    )
                    AsterPillButton(
                        text = "重试",
                        primary = true,
                        onClick = { vm.convert() },
                        modifier = Modifier.weight(1f).height(48.dp)
                    )
                }
            }
        }
    )
}

private fun formatGifDuration(durationMs: Long): String =
    if (durationMs % 1000L == 0L) "${durationMs / 1000L}s" else String.format(Locale.ROOT, "%.1fs", durationMs / 1000.0)

private fun formatGifBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
