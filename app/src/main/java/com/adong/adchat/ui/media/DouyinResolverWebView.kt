package com.adong.adchat.ui.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.adong.adchat.media.DouyinMediaSourceSelector
import com.adong.adchat.media.DouyinResolveRequest
import com.adong.adchat.media.DouyinUrlParser
import com.adong.adchat.media.ResolvedMedia
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DouyinResolverWebView(
    request: DouyinResolveRequest,
    modifier: Modifier = Modifier,
    onProgress: (String) -> Unit,
    onVerificationRequired: (String) -> Unit,
    onResolved: (ResolvedMedia) -> Unit,
    onFailure: (String) -> Unit
) {
    val progressCallback = rememberUpdatedState(onProgress)
    val verificationCallback = rememberUpdatedState(onVerificationRequired)
    val resolvedCallback = rememberUpdatedState(onResolved)
    val failureCallback = rememberUpdatedState(onFailure)
    val session = remember(request.token) {
        DouyinResolverSession(
            request = request,
            onProgress = { progressCallback.value(it) },
            onVerificationRequired = { verificationCallback.value(it) },
            onResolved = { resolvedCallback.value(it) },
            onFailure = { failureCallback.value(it) }
        )
    }

    key(request.token) {
        AndroidView(
            modifier = modifier,
            factory = { context -> session.createWebView(context) },
            update = { session.ensureStarted(it) }
        )
    }

    DisposableEffect(session) {
        onDispose { session.destroy() }
    }
}

private class DouyinResolverSession(
    private val request: DouyinResolveRequest,
    private val onProgress: (String) -> Unit,
    private val onVerificationRequired: (String) -> Unit,
    private val onResolved: (ResolvedMedia) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()
    private var webView: WebView? = null
    private var started = false
    private var finished = false
    private var pollScheduled = false
    private var pollInFlight = false
    private var verificationRaised = false
    private var canonicalized = request.link.videoId != null
    private var lastPageUrl = request.link.resolverUrl
    private var pollCount = 0

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView {
        val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        return WebView(context).apply {
            webView = this
            setBackgroundColor(Color.WHITE)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = WebSettings.getDefaultUserAgent(context)
            }
            cookieManager.setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (finished) return
                    when {
                        newProgress < 35 -> onProgress("正在连接抖音页面")
                        newProgress < 75 -> onProgress("页面正在完成安全验证")
                        else -> onProgress("正在寻找清晰的视频源")
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    lastPageUrl = url.orEmpty().ifBlank { lastPageUrl }
                    onProgress("正在载入媒体页面")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (finished) return
                    lastPageUrl = url.orEmpty().ifBlank { view.url.orEmpty() }.ifBlank { lastPageUrl }
                    CookieManager.getInstance().flush()

                    val discoveredId = DouyinUrlParser.extractVideoId(lastPageUrl)
                    if (!canonicalized && discoveredId != null) {
                        canonicalized = true
                        onProgress("已识别视频，正在读取页面媒体")
                        view.loadUrl("https://www.douyin.com/jingxuan?modal_id=$discoveredId")
                        return
                    }
                    schedulePoll(220L)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return shouldBlockNavigation(request.url)
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return shouldBlockNavigation(Uri.parse(url))
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame || finished) return
                    onProgress("页面连接波动，正在自动重试")
                    schedulePoll(700L)
                }
            }
            setDownloadListener { url, _, _, _, _ ->
                if (!finished && isLikelyVideoUrl(url)) {
                    finishWithUrl(url, this)
                }
            }
            ensureStarted(this)
        }
    }

    fun ensureStarted(view: WebView) {
        if (started || finished) return
        started = true
        onProgress(request.message)
        view.loadUrl(request.link.resolverUrl)
        schedulePoll(1_200L)
    }

    fun destroy() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        webView?.let { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.clearHistory()
            view.removeAllViews()
            view.destroy()
        }
        webView = null
    }

    private fun schedulePoll(delayMs: Long) {
        if (finished || pollScheduled) return
        pollScheduled = true
        handler.postDelayed({
            pollScheduled = false
            poll()
        }, delayMs)
    }

    private fun poll() {
        val view = webView ?: return
        if (finished || pollInFlight) return
        pollInFlight = true
        pollCount += 1
        view.evaluateJavascript(DOUYIN_MEDIA_PROBE) { raw ->
            pollInFlight = false
            if (finished) return@evaluateJavascript
            val payload = decodeJavascriptResult(raw)
            val candidate = payload?.optString("url").orEmpty()
            if (isLikelyVideoUrl(candidate)) {
                finishWithPayload(candidate, payload ?: JSONObject(), view)
                return@evaluateJavascript
            }

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val challengeVisible = payload?.optBoolean("challenge", false) == true
            if (!verificationRaised && (challengeVisible || elapsed >= VERIFICATION_REVEAL_MS)) {
                verificationRaised = true
                onVerificationRequired(
                    if (challengeVisible) "请在下方完成抖音安全验证，完成后会自动继续"
                    else "页面需要一次可见加载；无需保存 Cookie，解析成功后会自动收起"
                )
            } else if (!verificationRaised) {
                onProgress(
                    when (pollCount % 3) {
                        0 -> "正在读取播放器资源"
                        1 -> "正在等待页面生成临时凭证"
                        else -> "正在筛选可直接保存的视频源"
                    }
                )
            }

            if (elapsed >= RESOLVE_TIMEOUT_MS) {
                finished = true
                onFailure("页面已经打开，但没有发现可下载的视频。请确认该作品是公开的视频内容")
            } else {
                schedulePoll(if (verificationRaised) 650L else 420L)
            }
        }
    }

    private fun finishWithUrl(url: String, view: WebView) {
        finishWithPayload(url, JSONObject(), view)
    }

    private fun finishWithPayload(url: String, payload: JSONObject, view: WebView) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        val currentUrl = payload.optString("pageUrl").ifBlank { view.url.orEmpty() }.ifBlank { lastPageUrl }
        val videoId = payload.optString("videoId")
            .ifBlank { request.link.videoId.orEmpty() }
            .ifBlank { DouyinUrlParser.extractVideoId(currentUrl).orEmpty() }
            .ifBlank { System.currentTimeMillis().toString() }
        val title = payload.optString("title")
            .substringBefore(" - 抖音")
            .substringBefore("_抖音")
            .trim()
            .ifBlank { "抖音视频 $videoId" }
        val cookieHeader = CookieManager.getInstance().getCookie("https://www.douyin.com/").orEmpty()
        val selectedSource = DouyinMediaSourceSelector.preferWatermarkFree(normalizeCandidateUrl(url))
        val thumbnailCandidates = payload.optJSONArray("thumbnailCandidates")
        val thumbnailUrl = normalizeCandidateUrl(payload.optString("thumbnail")).takeIf { it.startsWith("http") }.orEmpty()
        val thumbnailFallbackUrl = buildList {
            if (thumbnailCandidates != null) {
                for (index in 0 until thumbnailCandidates.length()) {
                    val candidate = normalizeCandidateUrl(thumbnailCandidates.optString(index))
                    if (candidate.startsWith("http") && candidate != thumbnailUrl) add(candidate)
                }
            }
        }.firstOrNull().orEmpty()
        onProgress(if (selectedSource.watermarkFreePreferred) "已找到无水印视频源" else "已找到视频源")
        onResolved(
            ResolvedMedia(
                videoId = videoId,
                title = title,
                author = payload.optString("author").trim(),
                thumbnailUrl = thumbnailUrl,
                thumbnailFallbackUrl = thumbnailFallbackUrl,
                mediaUrl = selectedSource.primaryUrl,
                sourceUrl = request.link.sourceUrl,
                referer = currentUrl.ifBlank { "https://www.douyin.com/" },
                userAgent = view.settings.userAgentString.orEmpty(),
                cookieHeader = cookieHeader,
                compatibilityMediaUrl = selectedSource.compatibilityUrl,
                prefersWatermarkFree = selectedSource.watermarkFreePreferred
            )
        )
    }

    private fun shouldBlockNavigation(uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return true
        val host = uri?.host?.lowercase().orEmpty()
        return NAVIGATION_HOST_SUFFIXES.none { suffix -> host == suffix || host.endsWith(".$suffix") }
    }

    private fun decodeJavascriptResult(raw: String?): JSONObject? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return runCatching {
            val decoded = if (raw.startsWith('"')) JSONArray("[$raw]").getString(0) else raw
            JSONObject(decoded)
        }.getOrNull()
    }

    private fun isLikelyVideoUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = normalizeCandidateUrl(value).lowercase()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
        if (IMAGE_MARKERS.any(normalized::contains)) return false
        if (normalized.contains(".m3u8") || normalized.contains("aweme/detail")) return false
        return VIDEO_MARKERS.any(normalized::contains)
    }

    private fun normalizeCandidateUrl(value: String): String = value
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .trim()
        .let { normalized -> if (normalized.startsWith("http%", ignoreCase = true)) Uri.decode(normalized) else normalized }

    private companion object {
        const val VERIFICATION_REVEAL_MS = 7_500L
        const val RESOLVE_TIMEOUT_MS = 55_000L

        val IMAGE_MARKERS = listOf(".jpeg", ".jpg", ".png", ".webp", "avatar", "cover", "poster")
        val VIDEO_MARKERS = listOf(
            ".mp4",
            "douyinvod",
            "/video/tos/",
            "/video/play",
            "video_id=",
            "mime_type=video"
        )
        val NAVIGATION_HOST_SUFFIXES = listOf(
            "douyin.com",
            "iesdouyin.com",
            "zijieapi.com",
            "snssdk.com",
            "toutiao.com",
            "bytedance.com",
            "bytedance.net"
        )

        val DOUYIN_MEDIA_PROBE = """
            (function () {
              function text(value) {
                return typeof value === 'string' ? value : '';
              }
              function normalize(value) {
                var result = text(value).trim();
                if (!result) return '';
                result = result.replace(/\\u002f/ig, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&');
                if (/^http%/i.test(result)) {
                  try { result = decodeURIComponent(result); } catch (error) {}
                }
                return result;
              }
              function isImage(url) {
                return /(?:\.jpe?g|\.png|\.webp|avatar|cover|poster)/i.test(url);
              }
              function scoreUrl(value, originScore) {
                var url = normalize(value);
                if (!/^https?:\/\//i.test(url) || isImage(url) || /\.m3u8(?:\?|$)/i.test(url)) return 0;
                var fromMediaElement = (originScore || 0) >= 1000;
                var strongMarker = /douyinvod|\/video\/tos\/|\/video\/play|\.mp4(?:\?|$)|video_id=|mime_type=video/i.test(url);
                if (!fromMediaElement && !strongMarker) return 0;
                var score = originScore || 0;
                if (/douyinvod/i.test(url)) score += 480;
                if (/\/video\/tos\//i.test(url)) score += 420;
                if (/\.mp4(?:\?|$)/i.test(url)) score += 360;
                if (/video_id=|mime_type=video/i.test(url)) score += 240;
                if (/\/video\/play|playwm/i.test(url)) score += 180;
                return score;
              }

              var candidates = [];
              var seen = {};
              function add(value, originScore) {
                var url = normalize(value);
                var score = scoreUrl(url, originScore);
                if (!score || seen[url]) return;
                seen[url] = true;
                candidates.push({ url: url, score: score });
              }

              try {
                Array.prototype.slice.call(document.querySelectorAll('video')).forEach(function (node) {
                  add(node.currentSrc, 1200);
                  add(node.src, 1100);
                  add(node.getAttribute('src'), 1050);
                  if (!window.__adchatPoster && node.poster) window.__adchatPoster = node.poster;
                });
                Array.prototype.slice.call(document.querySelectorAll('video source')).forEach(function (node) {
                  add(node.src || node.getAttribute('src'), 1000);
                });
              } catch (error) {}

              try {
                performance.getEntriesByType('resource').forEach(function (entry) {
                  add(entry.name, 520);
                });
              } catch (error) {}

              var foundTitle = '';
              var foundAuthor = '';
              var thumbnailCandidates = [];
              var thumbnailSeen = {};
              function addThumbnail(value, depth) {
                if (depth > 5 || value === null || typeof value === 'undefined') return;
                if (typeof value === 'string') {
                  var imageUrl = normalize(value);
                  if (!/^https?:\/\//i.test(imageUrl) || /avatar|user-avatar/i.test(imageUrl)) return;
                  if (!/(?:douyinpic|image|cover|poster|\.jpe?g|\.png|\.webp)/i.test(imageUrl)) return;
                  if (!thumbnailSeen[imageUrl]) {
                    thumbnailSeen[imageUrl] = true;
                    thumbnailCandidates.push(imageUrl);
                  }
                  return;
                }
                if (Array.isArray(value)) {
                  for (var ti = 0; ti < value.length && ti < 12; ti++) addThumbnail(value[ti], depth + 1);
                  return;
                }
                if (typeof value === 'object') {
                  var thumbnailKeys = Object.keys(value);
                  for (var tk = 0; tk < thumbnailKeys.length && tk < 24; tk++) addThumbnail(value[thumbnailKeys[tk]], depth + 1);
                }
              }
              addThumbnail(window.__adchatPoster || '', 0);
              var foundThumbnail = '';
              var budget = 18000;
              function walk(value, key, depth) {
                if (budget-- <= 0 || depth > 14 || value === null || typeof value === 'undefined') return;
                if (typeof value === 'string') {
                  if (/url|src|addr|play/i.test(key || '')) add(value, 650);
                  if (!foundTitle && /^(?:desc|title|caption)$/i.test(key || '') && value.length < 300) foundTitle = value;
                  if (!foundAuthor && /nickname|authorName|userName/i.test(key || '') && value.length < 100) foundAuthor = value;
                  if (/cover|poster|thumbnail|url_list/i.test(key || '')) addThumbnail(value, 0);
                  return;
                }
                if (Array.isArray(value)) {
                  for (var i = 0; i < value.length && i < 80; i++) walk(value[i], key, depth + 1);
                  return;
                }
                if (typeof value === 'object') {
                  if (value.video && typeof value.video === 'object') {
                    addThumbnail(value.video.origin_cover, 0);
                    addThumbnail(value.video.cover, 0);
                    addThumbnail(value.video.dynamic_cover, 0);
                    addThumbnail(value.video.animated_cover, 0);
                  }
                  var keys = Object.keys(value);
                  for (var j = 0; j < keys.length && j < 120; j++) {
                    var childKey = keys[j];
                    if (/cover|poster|thumbnail/i.test(childKey)) addThumbnail(value[childKey], 0);
                    walk(value[childKey], childKey, depth + 1);
                  }
                }
              }

              try {
                Array.prototype.slice.call(document.scripts || []).forEach(function (script) {
                  var id = script.id || '';
                  var raw = script.textContent || '';
                  if (!raw || !(/RENDER_DATA|UNIVERSAL|SIGI_STATE/i.test(id) || /videoDetail|aweme_detail|bitRateList/.test(raw))) return;
                  var variants = [raw];
                  try { variants.push(decodeURIComponent(raw)); } catch (error) {}
                  for (var i = 0; i < variants.length; i++) {
                    try { walk(JSON.parse(variants[i]), '', 0); } catch (error) {}
                  }
                });
              } catch (error) {}

              try {
                [window._SSR_DATA, window._ROUTER_DATA, window.__INITIAL_STATE__, window.__NEXT_DATA__].forEach(function (state) {
                  if (state) walk(state, '', 0);
                });
              } catch (error) {}

              candidates.sort(function (a, b) { return b.score - a.score; });
              function meta(selector) {
                var node = document.querySelector(selector);
                return node ? (node.content || node.getAttribute('content') || '') : '';
              }
              addThumbnail(meta('meta[property="og:image"]'), 0);
              addThumbnail(meta('meta[name="twitter:image"]'), 0);
              foundThumbnail = thumbnailCandidates.length ? thumbnailCandidates[0] : '';
              var pageText = text(document.body && document.body.innerText).slice(0, 1200);
              var challenge = /安全验证|完成验证|拖动滑块|验证码|captcha|verify/i.test(pageText) ||
                !!document.querySelector('[class*="captcha"], iframe[src*="verify"], [id*="captcha"]');
              var pageUrl = location.href || '';
              var idMatch = pageUrl.match(/(?:\/video\/|[?&](?:modal_id|aweme_id)=)(\d{8,})/);
              return JSON.stringify({
                url: candidates.length ? candidates[0].url : '',
                title: foundTitle || meta('meta[property="og:title"]') || document.title || '',
                author: foundAuthor || meta('meta[name="author"]') || '',
                thumbnail: foundThumbnail || '',
                thumbnailCandidates: thumbnailCandidates.slice(0, 4),
                pageUrl: pageUrl,
                videoId: idMatch ? idMatch[1] : '',
                challenge: challenge,
                candidateCount: candidates.length
              });
            })();
        """.trimIndent()
    }
}

