package com.adong.adchat.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Used by fenced HTML in both workspaces and by create_file attachments. */
@Composable
internal fun HtmlArtifactCard(
    code: String,
    filename: String = "document.html",
    ready: Boolean = true,
    allowScripts: Boolean = true,
    actions: Boolean = true,
    panelActions: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val source = pendingExport ?: code
        pendingExport = null
        if (uri != null) scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use {
                    it.write(source.toByteArray(Charsets.UTF_8))
                }
            } }
            Toast.makeText(context, if (result.isSuccess) "已保存 $filename" else "保存失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }
    val canPreview = ready && code.length <= HtmlPreviewDocument.MAX_PREVIEW_CHARS
    Column(Modifier.fillMaxWidth()) {
        if (canPreview && !fullscreen) {
            HtmlPreview(code, Modifier.fillMaxWidth().height(340.dp), allowScripts, panelActions)
        } else if (!fullscreen) {
            // 只在真的看不到预览时才占位：能预览时那一行本来就只是重复信息。
            Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (ready) "文件较大，保存后可用其他应用打开" else "正在生成预览…",
                    color = MutedInk, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        if (actions) Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(filename, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MutedInk)
            IconButton(onClick = { fullscreen = true }, enabled = canPreview) {
                Icon(Icons.Rounded.OpenInFull, "全屏预览 HTML", tint = MutedInk)
            }
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(filename, code))
            }) { Icon(Icons.Rounded.ContentCopy, "复制 HTML 代码", tint = MutedInk, modifier = Modifier.size(18.dp)) }
            TextButton(
                onClick = { pendingExport = code; export.launch(filename) },
                enabled = ready,
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp), tint = Accent)
                Spacer(Modifier.width(4.dp))
                Text("下载 HTML", color = Accent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (fullscreen && canPreview) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val motionEnabled = LocalAsterMotionEnabled.current
        var entered by remember { mutableStateOf(!motionEnabled) }
        LaunchedEffect(Unit) { entered = true }
        val scaleX by animateFloatAsState(
            targetValue = if (entered) 1f else 0.92f,
            animationSpec = if (motionEnabled) AsterSpring.releaseHorizontal() else snap(),
            label = "html-fullscreen-scale-x"
        )
        val scaleY by animateFloatAsState(
            targetValue = if (entered) 1f else 0.85f,
            animationSpec = if (motionEnabled) AsterSpring.releaseVertical() else snap(),
            label = "html-fullscreen-scale-y"
        )
        val alpha by animateFloatAsState(
            targetValue = if (entered) 1f else 0f,
            animationSpec = if (motionEnabled) AsterSpring.editorial() else snap(),
            label = "html-fullscreen-alpha"
        )
        Surface(
            color = Canvas,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = if (motionEnabled) alpha.coerceIn(0f, 1f) else 1f
                    this.scaleX = if (motionEnabled) scaleX else 1f
                    this.scaleY = if (motionEnabled) scaleY else 1f
                    transformOrigin = TransformOrigin.Center
                }
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsterIconButton(Icons.Rounded.Close, "关闭 HTML 预览", { fullscreen = false })
                    Text(
                        filename,
                        Modifier.weight(1f).padding(start = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AsterPillButton(
                        text = "收起",
                        compact = true,
                        onClick = { fullscreen = false }
                    )
                }
                HorizontalDivider(color = Hairline)
                HtmlPreview(code, Modifier.fillMaxWidth().weight(1f), allowScripts, panelActions)
            }
        }
    }
}

private data class HtmlPreviewInput(val source: String, val scripts: Boolean, val panelBridge: Boolean)
private data class PreparedHtmlPreview(val input: HtmlPreviewInput, val html: String)

@Composable
private fun HtmlPreview(
    source: String,
    modifier: Modifier,
    allowScripts: Boolean,
    panelActions: ((String) -> Unit)? = null
) {
    val panelBridge = panelActions != null
    val input = remember(source, allowScripts, panelBridge) { HtmlPreviewInput(source, allowScripts, panelBridge) }
    val document by produceState<PreparedHtmlPreview?>(null, input) {
        val html = withContext(Dispatchers.Default) {
            HtmlPreviewDocument.wrap(input.source, input.scripts, input.panelBridge)
        }
        value = PreparedHtmlPreview(input, html)
    }
    val prepared = document
    if (prepared == null) Box(modifier, contentAlignment = Alignment.Center) {
        AsterLoadingIndicator(size = 24.dp)
    } else OfflineArtifactPreview(prepared.html, modifier, allowScripts, panelActions = panelActions?.let { action ->
        { text ->
            // produceState retains the old document while preparing the new source.
            // Its still-visible buttons must not invoke the new document's callback.
            if (prepared.input == input) action(text)
        }
    })
}
