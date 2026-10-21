package com.adong.adchat.ui.media

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.adong.adchat.ui.theme.Accent
import com.adong.adchat.ui.theme.AccentSoft
import com.adong.adchat.ui.theme.Canvas
import com.adong.adchat.ui.theme.Danger
import com.adong.adchat.ui.theme.Hairline
import com.adong.adchat.ui.theme.MutedInk
import com.adong.adchat.ui.theme.Night
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
    val actionColor = Night
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
        onDismiss = close,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (state.phase) {
                    VideoToGifState.Phase.Idle -> {
                        GifPreset.entries.forEach { preset ->
                            val selected = state.preset == preset
                            Surface(
                                color = if (selected) AccentSoft else Canvas,
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, if (selected) Accent else Hairline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { vm.update(preset = preset) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(preset.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "最长边 ${preset.widthPx}px · ${preset.fps} fps · 最长 ${preset.maxDurationMs / 1000}s",
                                            color = MutedInk,
                                            fontSize = 12.sp
                                        )
                                    }
                                    RadioButton(selected = selected, onClick = { vm.update(preset = preset) })
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
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
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

                    VideoToGifState.Phase.Done -> {
                        val info = state.convertInfo
                        if (info != null) {
                            Text(
                                "${info.width} × ${info.height} · ${info.frameCount} 帧 · ${formatGifDuration(info.durationMs)} · ${formatGifBytes(state.result?.fileSize ?: 0L)}",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                color = MutedInk,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text("GIF 已加入下载历史。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
                    }

                    VideoToGifState.Phase.Failed -> {
                        Text(
                            state.error ?: "转换失败",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            color = Danger,
                            style = MaterialTheme.typography.bodyMedium
                        )
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
                    OutlinedButton(
                        onClick = close,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("取消") }
                    Button(
                        onClick = { vm.convert() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = actionColor)
                    ) { Text("开始转换") }
                }

                VideoToGifState.Phase.Converting -> {
                    OutlinedButton(
                        onClick = { vm.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("取消转换") }
                }

                VideoToGifState.Phase.Done -> {
                    OutlinedButton(
                        onClick = {
                            val record = state.result ?: return@OutlinedButton
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
                        modifier = Modifier.weight(1f),
                        enabled = !state.savingToGallery && state.result?.galleryUri.isNullOrBlank(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            when {
                                state.savingToGallery -> "正在保存"
                                state.result?.galleryUri.isNullOrBlank() -> "保存到相册"
                                else -> "已保存"
                            }
                        )
                    }
                    Button(
                        onClick = { state.result?.let { record -> close(); onShare(record) } },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = actionColor)
                    ) { Text("分享") }
                }

                VideoToGifState.Phase.Failed -> {
                    OutlinedButton(
                        onClick = close,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("关闭") }
                    Button(
                        onClick = { vm.convert() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = actionColor)
                    ) { Text("重试") }
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
