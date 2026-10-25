package com.adong.adchat.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Renders fenced ```mermaid code blocks with the bundled assets/mermaid.min.js, fully offline. */
@Composable
internal fun MermaidArtifactCard(code: String, ready: Boolean = true) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        if (ready) MermaidPreview(code, Modifier.fillMaxWidth().height(320.dp))
        else Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
            Text("图表生成中…", color = MutedInk, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("图表", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MutedInk)
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("mermaid", code))
            }) { Icon(Icons.Rounded.ContentCopy, "复制 Mermaid 代码", tint = MutedInk, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun MermaidPreview(code: String, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    var failed by remember(code) { mutableStateOf(false) }
    val html by produceState<String?>(null, code, context) {
        try {
            val script = MermaidScriptCache.load(context)
            value = withContext(Dispatchers.Default) { MermaidPreviewDocument.wrap(code, script) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val document = html
    when {
        failed -> Box(modifier, contentAlignment = Alignment.Center) { Text("预览无法显示", color = MutedInk) }
        document == null -> Box(modifier, contentAlignment = Alignment.Center) { AsterLoadingIndicator(size = 24.dp) }
        else -> OfflineArtifactPreview(document, modifier, allowScripts = true, baseUrl = "https://mermaid.local/")
    }
}

private object MermaidScriptCache {
    private var script: String? = null
    private val mutex = Mutex()

    suspend fun load(context: android.content.Context): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            script ?: context.assets.open("mermaid.min.js").bufferedReader().use { it.readText() }
                .let(MermaidPreviewDocument::sanitizeScript).also { script = it }
        }
    }
}
