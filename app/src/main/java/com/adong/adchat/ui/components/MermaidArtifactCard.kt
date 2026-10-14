package com.adong.adchat.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
import com.adong.adchat.ui.theme.*
import java.io.ByteArrayInputStream

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

/** Escape mermaid source into a single-quoted JS string literal; '<' is escaped so a
 *  literal "</script>" in model output cannot terminate the host script element early. */
private fun jsStringLiteral(source: String): String = buildString(source.length + 2) {
    append('\'')
    for (ch in source) when (ch) {
        '\\' -> append("\\\\")
        '\'' -> append("\\'")
        '\n' -> append("\\n")
        '\r' -> {}
        '<' -> append("\\u003C")
        else -> append(ch)
    }
    append('\'')
}

/** A literal "</script" inside an inline script would close the element; escape it.
 *  In JS source this only ever appears inside strings or regexes where "\/" is legal. */
private fun sanitizeInlineScript(js: String): String = js.replace(Regex("</(?i:script)"), "<\\/script")

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidPreview(code: String, modifier: Modifier) {
    val context = LocalContext.current
    // Built with append() rather than string templates: minified JS contains '${'
    // sequences that Kotlin would otherwise parse as interpolation.
    val html = remember(code) { runCatching {
        val mermaidJs = sanitizeInlineScript(
            context.assets.open("mermaid.min.js").bufferedReader().use { it.readText() })
        buildString(mermaidJs.length + 1024) {
            append("<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<style>body{margin:0;padding:8px;background:#fff;display:flex;justify-content:center}")
            append("#g{max-width:100%;overflow-x:auto}</style>")
            append("</head><body><div id=\"g\"></div>")
            append("<script>").append(mermaidJs).append("</script>")
            append("<script>try {")
            append("mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'default'});")
            append("mermaid.render('m1', ").append(jsStringLiteral(code)).append(")")
            append(".then(r=>{document.getElementById('g').innerHTML=r.svg;});")
            append("} catch(e) { document.getElementById('g').textContent='图表渲染失败'; }</script>")
            append("</body></html>")
        }
    }.getOrNull() }
    if (html == null) Box(modifier, contentAlignment = Alignment.Center) {
        Text("预览无法显示", color = MutedInk)
    } else AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT)
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    setAllowFileAccessFromFileURLs(false)
                    @Suppress("DEPRECATION")
                    setAllowUniversalAccessFromFileURLs(false)
                    blockNetworkLoads = true
                    domStorageEnabled = false
                    databaseEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                loadDataWithBaseURL("https://mermaid.local/", html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
        onRelease = {
            it.stopLoading()
            it.webViewClient = WebViewClient()
            it.removeAllViews()
            it.destroy()
        }
    )
}
