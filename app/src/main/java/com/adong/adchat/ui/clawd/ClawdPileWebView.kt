package com.adong.adchat.ui.clawd

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 原版小 Clawd 宿主 WebView：全屏透明覆盖层，跑未经修改的原版 clawd-pile.js。
 *
 * - 定位：JS 侧 #send_form 镜像真实输入框在本 WebView 视口内的像素矩形（[formAnchor]
 *   是 Window 坐标，减去 WebView 自身的 Window 位置换算），原版
 *   .clawd-pile{bottom:100%} 使爪子精确落在输入框上端；position:absolute 覆盖层
 *   允许浮在整个页面上
 * - 触摸：JS 每 60ms 推送爪子物理像素矩形，覆写 dispatchTouchEvent 做命中过滤——
 *   命中爪子才把事件交给 WebView（原版戳/拖拽生效），空白处穿透给 Compose
 * - 位置记忆：domStorageEnabled，原版 localStorage 直接生效
 */
@Composable
fun ClawdPileWebView(
    formAnchor: Rect?,
    night: Boolean,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var webView by remember { mutableStateOf<ClawdHostWebView?>(null) }
    // WebView 自身在 Window 里的位置：把 formAnchor（Window 坐标）换算进 WebView 视口
    var viewPos by remember { mutableStateOf(Offset.Zero) }

    val relRect = formAnchor?.let { f ->
        Rect(
            f.left - viewPos.x,
            f.top - viewPos.y,
            f.right - viewPos.x,
            f.bottom - viewPos.y,
        )
    }

    LaunchedEffect(relRect, webView) {
        val view = webView ?: return@LaunchedEffect
        val rect = relRect ?: return@LaunchedEffect
        view.applyFormRect(rect)
    }

    LaunchedEffect(night, webView) {
        val view = webView ?: return@LaunchedEffect
        val js = "window.__clawdNight && window.__clawdNight(${if (night) "true" else "false"})"
        view.evaluateJavascript(js, null)
    }

    DisposableEffect(lifecycle, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { ctx ->
            ClawdHostWebView(ctx).also { view ->
                webView = view
                configureClawdWebView(view)
            }
        },
        modifier = modifier.onGloballyPositioned { viewPos = it.positionInWindow() },
        onRelease = { view ->
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.removeAllViews()
            view.destroy()
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureClawdWebView(view: ClawdHostWebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        setAllowFileAccessFromFileURLs(false)
        @Suppress("DEPRECATION")
        setAllowUniversalAccessFromFileURLs(false)
        // file:///android_asset 不受 allowFileAccess 影响，照常可加载；网络全禁
        blockNetworkLoads = true
        databaseEnabled = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(true)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = true
        cacheMode = WebSettings.LOAD_NO_CACHE
    }
    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    view.isClickable = false
    view.isFocusable = false
    view.isFocusableInTouchMode = false
    view.addJavascriptInterface(view, "AsterClawd")
    view.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            Log.d("ClawdHost", "${msg.line()}: ${msg.message()}")
            return true
        }
    }
    view.webViewClient = object : WebViewClient() {
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean = true

        // 页面加载完成前发出的布局会丢失（window.__clawdLayout 还不存在）：这里补发
        override fun onPageFinished(view: WebView?, url: String?) {
            (view as? ClawdHostWebView)?.resendFormRect()
        }
    }
    view.loadUrl("file:///android_asset/clawd/clawd_host.html")
}

/** 触摸命中过滤的 WebView：事件起点落在爪子矩形内才自己消化，否则穿透给 Compose */
private class ClawdHostWebView(context: Context) : WebView(context) {

    private var lastRect: Rect? = null

    /** JS 推来的爪子物理像素矩形 [left, top, right, bottom] */
    @Volatile
    private var clawdRects: Array<FloatArray> = emptyArray()

    /** 拖拽进行中：DOWN 命中了爪子，整个手势期间事件都归 WebView（原版 pointer 捕获） */
    private var gestureClaimed = false

    fun applyFormRect(rect: Rect) {
        lastRect = rect
        val js = "window.__clawdLayout && window.__clawdLayout(${rect.left}, ${rect.top}, ${rect.width}, ${rect.height})"
        evaluateJavascript(js, null)
    }

    fun resendFormRect() {
        lastRect?.let { applyFormRect(it) }
    }

    @JavascriptInterface
    fun pushRects(json: String) {
        val cleaned = json.trim()
        val out = mutableListOf<FloatArray>()
        if (cleaned.startsWith("[") && cleaned.length > 2) {
            try {
                val arr = org.json.JSONArray(cleaned)
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONArray(i)
                    out.add(floatArrayOf(
                        r.getDouble(0).toFloat(),
                        r.getDouble(1).toFloat(),
                        r.getDouble(2).toFloat(),
                        r.getDouble(3).toFloat(),
                    ))
                }
            } catch (_: org.json.JSONException) {
            }
        }
        clawdRects = out.toTypedArray()
    }

    private fun hit(x: Float, y: Float): Boolean {
        for (r in clawdRects) {
            if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return true
        }
        return false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureClaimed = hit(event.x, event.y)
                if (!gestureClaimed) return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!gestureClaimed) return false
                gestureClaimed = false
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
