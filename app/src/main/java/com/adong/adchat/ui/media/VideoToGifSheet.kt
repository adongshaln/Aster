package com.adong.adchat.ui.media

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.adong.adchat.media.gif.GifPreset
import com.adong.adchat.ui.components.AdModalDialog
import com.adong.adchat.ui.components.AsterLoadingIndicator
import com.adong.adchat.ui.theme.Accent
import com.adong.adchat.ui.theme.AccentSoft
import com.adong.adchat.ui.theme.Canvas
import com.adong.adchat.ui.theme.Hairline
import com.adong.adchat.ui.theme.MutedInk
import com.adong.adchat.ui.theme.Night
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun VideoToGifSheet(
    vm: VideoToGifViewModel,
    onShare: (MediaDownloadRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AdModalDialog(
        title = "转为 GIF",
        subtitle = state.source?.title,
        onDismiss = onDismiss,
        actions = {
            when (state.phase) {
                VideoToGifState.Phase.Idle -> {
                    OutlinedButton(
                        onClick = { vm.dismiss(); onDismiss() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("取消") }
                    Button(
                        onClick = { vm.convert() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Night)
                    ) { Text("开始转换") }
                }
                VideoToGifState.Phase.Converting -> Unit
                VideoToGifState.Phase.Done -> {
                    Button(
                        onClick = {
                            scope.launch {
                                val uri = vm.saveToGallery()
                                if (uri != null) Toast.makeText(context, "已保存到相册 Pictures/Aster", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Night)
                    ) { Text("保存到相册") }
                    Button(
                        onClick = {
                            state.result?.let { record ->
                                vm.dismiss()
                                onShare(record)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Night)
                    ) { Text("分享") }
                }
                VideoToGifState.Phase.Failed -> {
                    OutlinedButton(
                        onClick = { vm.dismiss(); onDismiss() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("关闭") }
                    Button(
                        onClick = { vm.convert() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Night)
                    ) { Text("重试") }
                }
            }
        }
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
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { vm.update(preset = preset) }
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(preset.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "宽 ${preset.widthPx}px · ${preset.fps}fps · ≤${preset.maxDurationMs / 1000}s",
                                    color = MutedInk, fontSize = 12.sp
                                )
                            }
                            Text(
                                preset.estimateLabel,
                                color = if (selected) Accent else MutedInk,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "整个视频（超上限取前 ${state.preset.maxDurationMs / 1000} 秒）",
                    color = MutedInk, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
                if (state.error != null && state.phase != VideoToGifState.Phase.Failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.error ?: "", color = MutedInk, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                }
            }
            VideoToGifState.Phase.Converting -> {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(6.dp))
                    AsterLoadingIndicator(size = 30.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${(state.progress * 100).toInt()}%",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = Accent,
                        trackColor = AccentSoft
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { vm.cancel() }) { Text("取消转换", color = MutedInk) }
                }
            }
            VideoToGifState.Phase.Done -> {
                val result = state.result
                if (result != null) {
                    Text(
                        "${result.width}×${result.height} · ${result.frameCount} 帧 · ${result.durationMs / 1000}s · ${formatGifBytes(result.fileSize)}",
                        color = MutedInk, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (state.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.error ?: "", color = MutedInk, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                }
            }
            VideoToGifState.Phase.Failed -> {
                Text(
                    state.error ?: "转换失败",
                    color = MutedInk, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun formatGifBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble(); var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit += 1 }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else String.format(Locale.US, "%.1f %s", value, units[unit])
}
