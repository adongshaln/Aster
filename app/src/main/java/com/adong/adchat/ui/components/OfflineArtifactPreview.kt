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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adong.adchat.ui.theme.MutedInk
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

@Composable
internal fun OfflineArtifactPreview(
    document: String,
    modifier: Modifier,
    allowScripts: Boolean,
    baseUrl: String? = null,
    panelActions: ((String) -> Unit)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failedDocument by remember { mutableStateOf<String?>(null) }
    if (failedDocument == document) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("预览无法显示，可保存后用其他应用打开", color = MutedInk)
        }
    } else {
        AndroidView(
            factory = { OfflineArtifactView(it) },
            modifier = modifier,
            update = {
                it.render(document, allowScripts, baseUrl, panelActions, lifecycle) {
                    failedDocument = document
                }
            },
            onRelease = OfflineArtifactView::release,
        )
    }
}

/** Bridge methods run on Chromium's thread. Deliver only bounded actions on the main thread. */
internal class OfflinePanelActionBridge {
    private val handler = Handler(Looper.getMainLooper())
    private val revision = AtomicLong()
    var onAction: ((String) -> Unit)? = null

    fun invalidate() {
        revision.incrementAndGet()
        onAction = null
    }

    @JavascriptInterface
    fun onPanelAction(text: String) {
        if (text.isBlank() || text.length > 200) return
        val submittedRevision = revision.get()
        handler.post { if (revision.get() == submittedRevision) onAction?.invoke(text) }
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
    private var bridge = OfflinePanelActionBridge()
    private val observer = LifecycleEventObserver { _, _ -> syncLifecycle() }

    private data class Document(val html: String, val scripts: Boolean, val baseUrl: String?, val bridge: Boolean)

    init {
        engine = runCatching {
            WebView(context).apply {
                settings.apply {
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    setAllowFileAccessFromFileURLs(false)
                    @Suppress("DEPRECATION")
                    setAllowUniversalAccessFromFileURLs(false)
                    blockNetworkLoads = true
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
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (released) return
                        view.postVisualStateCallback(revision, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                if (released || requestId != revision) return
                                view.invalidate()
                                view.rootView.postInvalidateOnAnimation()
                            }
                        })
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
        lifecycle: Lifecycle,
        onFailure: () -> Unit,
    ) {
        this.onFailure = onFailure
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
        val bridgeEnabled = allowScripts && panelActions != null
        val document = Document(html, allowScripts, baseUrl, bridgeEnabled)
        if (loaded == document) {
            bridge.onAction = panelActions.takeIf { bridgeEnabled }
            return
        }
        bridge.invalidate()
        bridge = OfflinePanelActionBridge().apply { onAction = panelActions.takeIf { bridgeEnabled } }
        loaded = document
        val currentRevision = ++revision
        view.stopLoading()
        view.settings.javaScriptEnabled = allowScripts
        if (bridgeEnabled) view.addJavascriptInterface(bridge, "Aster") else view.removeJavascriptInterface("Aster")
        // Preserve the dialog's first Compose frame before starting Chromium drawing.
        view.postOnAnimation {
            view.post {
                if (!released && revision == currentRevision) {
                    view.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                }
            }
        }
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
        bridge.invalidate()
        onFailure = null
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
