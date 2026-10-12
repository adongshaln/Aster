package com.adong.adchat.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.webkit.*
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adong.adchat.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/** Used by fenced HTML in both workspaces and by create_file attachments. */
@Composable
internal fun HtmlArtifactCard(
    code: String,
    filename: String = "document.html",
    ready: Boolean = true,
    allowScripts: Boolean = true,
    actions: Boolean = true
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
            HtmlPreview(code, Modifier.fillMaxWidth().height(340.dp), allowScripts)
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
        Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { fullscreen = false }) { Icon(Icons.Rounded.Close, "关闭 HTML 预览") }
                    Text(filename, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("预览", Modifier.padding(16.dp), color = MutedInk)
                }
                HtmlPreview(code, Modifier.fillMaxWidth().weight(1f), allowScripts)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreview(source: String, modifier: Modifier, allowScripts: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(source, allowScripts) { mutableStateOf(false) }
    val view = remember(source, allowScripts) { runCatching {
        WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = allowScripts
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
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // DOM completion precedes Chromium's first drawable frame.
                    view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            view.invalidate()
                            view.rootView.postInvalidateOnAnimation()
                        }
                    })
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse =
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    failed = true
                    return true
                }
            }
        }
    }.getOrNull() }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) view?.onResume()
            if (event == Lifecycle.Event.ON_PAUSE) view?.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }
    if (view == null || failed) Box(modifier, contentAlignment = Alignment.Center) {
        Text("预览无法显示，可保存后用其他应用打开", color = MutedInk)
    } else AndroidView(
        factory = {
            view.apply {
                // Let the surrounding Compose window submit its first frame before
                // Chromium starts drawing; otherwise a new dialog's toolbar can stay blank.
                postOnAnimation {
                    post { loadDataWithBaseURL(null, HtmlPreviewDocument.wrap(source, allowScripts), "text/html", "utf-8", null) }
                }
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
