package com.adong.adchat.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adong.adchat.ui.theme.MutedInk
import com.adong.adchat.ui.theme.Canvas
import com.adong.adchat.ui.theme.SurfaceInset
import com.adong.adchat.ui.theme.LocalAsterMotionEnabled
import com.adong.adchat.ui.theme.AsterEase
import java.util.concurrent.atomic.AtomicLong

internal typealias HelperHost = com.adong.adchat.data.rp.helper.HelperHost

@Composable
internal fun OfflineArtifactPreview(
    document: String,
    modifier: Modifier,
    allowScripts: Boolean,
    baseUrl: String? = null,
    panelActions: ((String) -> Unit)? = null,
    onHeight: ((Int) -> Unit)? = null,
    helper: HelperHost? = null,
    remoteMedia: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failedDocument by remember { mutableStateOf<String?>(null) }
    var ready by remember(document, allowScripts, baseUrl, remoteMedia, helper != null, panelActions != null, onHeight != null) {
        mutableStateOf(false)
    }
    if (failedDocument == document) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("预览无法显示，可保存后用其他应用打开", color = MutedInk)
        }
    } else {
        Box(modifier) {
            AndroidView(
                factory = { OfflineArtifactView(it) },
                modifier = Modifier.matchParentSize(),
                update = {
                    it.render(document, allowScripts, baseUrl, panelActions, onHeight, helper, lifecycle, remoteMedia,
                        onReady = { ready = true }) { failedDocument = document }
                },
                onRelease = OfflineArtifactView::release,
            )
            // Keep Chromium drawing behind an opaque native cover until its first visual frame.
            // Only the cover fades; neither the WebView nor the list is resized by the animation.
            key(document, allowScripts, baseUrl, remoteMedia, helper != null, panelActions != null, onHeight != null) {
                HtmlPreviewCover(ready, Modifier.matchParentSize())
            }
        }
    }
}

@Composable
private fun HtmlPreviewCover(ready: Boolean, modifier: Modifier) {
    val motionEnabled = LocalAsterMotionEnabled.current
    val opacity = remember { Animatable(1f) }
    LaunchedEffect(ready, motionEnabled) {
        if (ready && motionEnabled) opacity.animateTo(0f, tween(180, easing = AsterEase))
        else opacity.snapTo(if (ready) 0f else 1f)
    }
    val visible by remember { derivedStateOf { opacity.value > 0f } }
    if (visible) HtmlPreviewPlaceholder(modifier.graphicsLayer { alpha = opacity.value }, showProgress = !ready)
}

/** Same native surface before document preparation, while queued, and until Chromium paints. */
@Composable
internal fun HtmlPreviewPlaceholder(modifier: Modifier, showProgress: Boolean = true) {
    Box(modifier.background(Canvas)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showProgress) AsterLoadingIndicator(size = 18.dp)
                Text("正在加载预览…", color = MutedInk, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.fillMaxWidth(0.84f).height(10.dp).background(SurfaceInset, RoundedCornerShape(5.dp)))
            Spacer(Modifier.fillMaxWidth(0.66f).height(10.dp).background(SurfaceInset, RoundedCornerShape(5.dp)))
            Spacer(Modifier.fillMaxWidth(0.74f).height(10.dp).background(SurfaceInset, RoundedCornerShape(5.dp)))
        }
    }
}

/** Bridge methods run on Chromium's thread. Deliver only bounded actions on the main thread. */
internal class OfflinePanelActionBridge {
    private val handler = Handler(Looper.getMainLooper())
    private val revision = AtomicLong()
    // Main-thread only: collapse height bursts before they can repeatedly remeasure the lazy list.
    private var pendingHeight: Int? = null
    private var deliveredHeight: Int? = null
    private var heightDispatch: Runnable? = null
    var onAction: ((String) -> Unit)? = null
    var onHeight: ((Int) -> Unit)? = null
    var helper: HelperHost? = null
    /** Evaluates JS on the owning page (main thread); used to resolve async calls. */
    var evaluate: ((String) -> Unit)? = null

    fun invalidate() {
        revision.incrementAndGet()
        heightDispatch?.let(handler::removeCallbacks)
        heightDispatch = null
        pendingHeight = null
        deliveredHeight = null
        onAction = null
        onHeight = null
        helper = null
        evaluate = null
    }

    @JavascriptInterface
    fun rpc(json: String): String {
        if (json.length > 200_000) return "null"
        return runCatching { helper?.rpc(json) ?: "null" }.getOrDefault("null")
    }

    @JavascriptInterface
    fun rpcAsync(json: String, callId: String) {
        if (json.length > 200_000 || callId.length > 120) return
        val host = helper ?: return
        val submittedRevision = revision.get()
        host.rpcAsync(json) { reply ->
            val payload = runCatching {
                org.json.JSONObject(reply).put("t", "r").put("id", callId).toString()
            }.getOrElse { org.json.JSONObject().put("t", "r").put("id", callId).put("error", "失败").toString() }
            handler.post {
                if (revision.get() == submittedRevision) evaluate?.invoke(com.adong.adchat.data.rp.helper.helperDeliverJs(payload))
            }
        }
    }

    @JavascriptInterface
    fun broadcast(json: String) {
        if (json.length > 200_000) return
        helper?.broadcast(json)
    }

    @JavascriptInterface
    fun onPanelAction(text: String) {
        if (text.isBlank() || text.length > 200) return
        val submittedRevision = revision.get()
        handler.post { if (revision.get() == submittedRevision) onAction?.invoke(text) }
    }

    @JavascriptInterface
    fun onHeight(height: Int) {
        if (height <= 0 || height > 20000) return
        val submittedRevision = revision.get()
        handler.post {
            if (revision.get() != submittedRevision) return@post
            pendingHeight = height
            if (heightDispatch == null) {
                heightDispatch = Runnable {
                    heightDispatch = null
                    if (revision.get() != submittedRevision) return@Runnable
                    val latest = pendingHeight ?: return@Runnable
                    pendingHeight = null
                    if (latest != deliveredHeight) {
                        deliveredHeight = latest
                        onHeight?.invoke(latest)
                    }
                }.also { handler.post(it) }
            }
        }
    }
}

/** Owns a single WebView for its AndroidView lifetime, including updates and teardown. */
@SuppressLint("SetJavaScriptEnabled")
internal class OfflineArtifactView(context: Context) : FrameLayout(context) {
    internal var engine: WebView? = null
        private set
    private var released = false
    private var revision = 0L
    private var loaded: Document? = null
    private var lifecycle: Lifecycle? = null
    private var onFailure: (() -> Unit)? = null
    private var onReady: (() -> Unit)? = null
    private var visualReady = false
    private var visualRequestRevision = -1L
    private var loadingRevision = -1L
    private var bridge = OfflinePanelActionBridge()
    private var helper: HelperHost? = null
    private var emitterToken: Any? = null
    @Volatile private var remoteMedia = false
    /** Native → page JS waits until the page has loaded; otherwise it would hit the previous document. */
    private var pageReady = false
    private val pendingJs = ArrayList<String>()
    private val observer = LifecycleEventObserver { _, _ -> syncLifecycle() }

    private data class Document(val html: String, val scripts: Boolean, val baseUrl: String?, val bridge: Boolean, val height: Boolean, val helper: Boolean, val remoteMedia: Boolean)

    init {
        engine = runCatching {
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.apply {
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    setAllowFileAccessFromFileURLs(false)
                    @Suppress("DEPRECATION")
                    setAllowUniversalAccessFromFileURLs(false)
                    // Card frontends carry remote https images; shouldInterceptRequest only lets images,
                    // media and vendored assets through, and CSP blocks fetch/XHR on top.
                    blockNetworkLoads = false
                    domStorageEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(true)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    mediaPlaybackRequiresUserGesture = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true

                    // Card pages may only reach vendored assets (and https images); everything else is denied.
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        TavernHelperAssets.resolve(context, url)?.let { return it }
                        val accept = request.requestHeaders?.get("Accept").orEmpty()
                        if (!remoteMedia) return if (url.startsWith("data:") || url.startsWith("blob:")) null else TavernHelperAssets.blocked()
                        val image = url.startsWith("https://") && (accept.startsWith("image/") ||
                            Regex("\\.(png|jpe?g|gif|webp|svg|avif|bmp)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url))
                        val media = url.startsWith("https://") && Regex("\\.(mp3|ogg|wav|m4a|mp4|webm)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)
                        return if (image || media || url.startsWith("data:") || url.startsWith("blob:")) null else TavernHelperAssets.blocked()
                    }

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        // Reveal the first painted content without waiting for every remote image.
                        revealWhenPainted(view)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (released || loadingRevision != revision) return
                        pageReady = true
                        val queued = pendingJs.toList()
                        pendingJs.clear()
                        queued.forEach { view.evaluateJavascript(it, null) }
                        revealWhenPainted(view)
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        val failed = onFailure
                        release()
                        failed?.invoke()
                        return true
                    }
                }
            }
        }.getOrNull()?.also { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    }

    fun render(
        html: String,
        allowScripts: Boolean,
        baseUrl: String?,
        panelActions: ((String) -> Unit)?,
        onHeight: ((Int) -> Unit)?,
        helper: HelperHost?,
        lifecycle: Lifecycle,
        remoteMedia: Boolean = false,
        onReady: (() -> Unit)? = null,
        onFailure: () -> Unit,
    ) {
        this.onFailure = onFailure
        this.onReady = onReady
        val view = engine
        if (released || view == null) {
            onFailure()
            return
        }
        if (this.lifecycle !== lifecycle) {
            this.lifecycle?.removeObserver(observer)
            this.lifecycle = lifecycle
            lifecycle.addObserver(observer)
            syncLifecycle()
        }
        val bridgeEnabled = allowScripts && (panelActions != null || onHeight != null || helper != null)
        val document = Document(html, allowScripts, baseUrl, bridgeEnabled, onHeight != null, helper != null, remoteMedia)
        val evaluate: (String) -> Unit = { js ->
            if (!released) {
                if (pageReady) engine?.evaluateJavascript(js, null)
                else if (pendingJs.size < 256) pendingJs += js
            }
        }
        if (this.helper !== helper) {
            emitterToken?.let { this.helper?.removeEmitter(it) }
            emitterToken = null
            this.helper = helper
            if (helper != null) emitterToken = helper.addEmitter { js -> post { evaluate(js) } }
        }
        if (loaded == document) {
            bridge.onAction = panelActions.takeIf { bridgeEnabled }
            bridge.onHeight = onHeight
            bridge.helper = helper
            bridge.evaluate = evaluate
            if (visualReady && onReady != null) {
                val readyRevision = revision
                view.post { if (!released && visualReady && revision == readyRevision) this.onReady?.invoke() }
            }
            return
        }
        bridge.invalidate()
        bridge = OfflinePanelActionBridge().apply {
            onAction = panelActions.takeIf { bridgeEnabled }
            this.onHeight = onHeight
            this.helper = helper
            this.evaluate = evaluate
        }
        loaded = document
        this.remoteMedia = remoteMedia
        pageReady = false
        visualReady = false
        pendingJs.clear()
        val currentRevision = ++revision
        view.stopLoading()
        view.settings.javaScriptEnabled = allowScripts
        if (bridgeEnabled) view.addJavascriptInterface(bridge, "Aster") else view.removeJavascriptInterface("Aster")
        // Preserve the dialog's first Compose frame before starting Chromium drawing.
        view.postOnAnimation {
            view.post {
                if (!released && revision == currentRevision) {
                    loadingRevision = currentRevision
                    view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                }
            }
        }
    }

    private fun revealWhenPainted(view: WebView) {
        if (released || loadingRevision != revision || visualRequestRevision == revision) return
        visualRequestRevision = revision
        view.postVisualStateCallback(revision, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (released || requestId != revision) return
                visualReady = true
                view.invalidate()
                view.rootView.postInvalidateOnAnimation()
                onReady?.invoke()
            }
        })
    }

    private fun syncLifecycle() {
        if (lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) engine?.onResume()
        else engine?.onPause()
    }

    fun release() {
        if (released) return
        released = true
        revision++
        lifecycle?.removeObserver(observer)
        lifecycle = null
        emitterToken?.let { helper?.removeEmitter(it) }
        emitterToken = null
        helper = null
        bridge.invalidate()
        onFailure = null
        onReady = null
        pendingJs.clear()
        engine?.let { view ->
            view.stopLoading()
            view.onPause()
            view.removeJavascriptInterface("Aster")
            view.webViewClient = WebViewClient()
            view.webChromeClient = null
            removeView(view)
            view.removeAllViews()
            view.destroy()
        }
        engine = null
        loaded = null
    }
}
