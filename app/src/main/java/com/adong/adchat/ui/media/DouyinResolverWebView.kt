package com.adong.adchat.ui.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import com.adong.adchat.media.MediaVariant
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

internal class DouyinResolverSession(
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
    private var lastPageUrl = request.link.resolverUrl
    private var pollCount = 0
    private var detailFetchInjected = false
    private var lastSniffedUrl = ""

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
                userAgentString = if (request.link.isNote) DESKTOP_CHROME_UA else WebSettings.getDefaultUserAgent(context)
            }
            cookieManager.setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (finished) return
                    when {
                        newProgress < 35 -> onProgress("正在连接抖音页面")
                        newProgress < 75 -> onProgress("页面正在完成安全验证")
                        else -> onProgress("正在寻找清晰的媒体源")
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    val target = url.orEmpty()
                    if (redirectMobileNoteToDesktop(view, Uri.parse(target))) return
                    lastPageUrl = target.ifBlank { lastPageUrl }
                    onProgress("正在载入媒体页面")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (finished) return
                    lastPageUrl = url.orEmpty().ifBlank { view.url.orEmpty() }.ifBlank { lastPageUrl }
                    CookieManager.getInstance().flush()
                    injectDetailFetch(view)
                    schedulePoll(220L)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (redirectMobileNoteToDesktop(view, request.url)) return true
                    return shouldBlockNavigation(request.url)
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val parsed = Uri.parse(url)
                    if (redirectMobileNoteToDesktop(view, parsed)) return true
                    return shouldBlockNavigation(parsed)
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

    /**
     * 在页面上下文里调用官方 web detail 接口（同源免 CORS，Cookie/UA/Referer 由
     * WebView 自动携带）。yt-dlp 证实该端点对新鲜 Cookie 不要求 a_bogus 签名。
     * 结果写入 window.__asterDetail，由 DOUYIN_MEDIA_PROBE 读取后带回。
     */
    private fun injectDetailFetch(view: WebView) {
        if (finished || detailFetchInjected) return
        val videoId = DouyinUrlParser.extractVideoId(lastPageUrl)
            ?: DouyinUrlParser.extractVideoId(view.url.orEmpty())
            ?: request.link.videoId
            ?: return
        val currentHost = runCatching { Uri.parse(view.url.orEmpty()).host?.lowercase().orEmpty() }.getOrDefault("")
        val supportedHost = currentHost == "douyin.com" || currentHost.endsWith(".douyin.com") ||
            currentHost == "iesdouyin.com" || currentHost.endsWith(".iesdouyin.com")
        if (!supportedHost) return
        detailFetchInjected = true
        Log.d("AsterMedia", "inject detail fetch: videoId=$videoId page=${view.url.orEmpty().take(100)}")
        view.evaluateJavascript(DOUYIN_DETAIL_FETCH.replace("__VIDEO_ID__", videoId), null)
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
        injectDetailFetch(view)
        pollCount += 1
        view.evaluateJavascript(DOUYIN_MEDIA_PROBE) { raw ->
            pollInFlight = false
            if (finished) return@evaluateJavascript
            val payload = decodeJavascriptResult(raw)
            val candidate = payload?.optString("url").orEmpty()
            val candidateLikely = isLikelyVideoUrl(candidate)
            // API 直链优先（yt-dlp 路径，官方下发、附清晰度档位），嗅探 URL 兜底；
            // 无论哪条路完成，都先记下嗅探结果作兼容备选
            if (candidateLikely) lastSniffedUrl = normalizeCandidateUrl(candidate)
            // 页面跳转会让注入的 JS 上下文连同 __asterDetail 一起销毁：
            // 探针报 absent 且曾注入过 → 允许在当前页面重新注入
            val detailState = payload?.optString("detailState").orEmpty()
            if (detailState == "absent" && detailFetchInjected) detailFetchInjected = false
            val apiDetail = payload?.optJSONObject("apiDetail")
            if (apiDetail != null && finishWithApiDetail(apiDetail, view)) {
                return@evaluateJavascript
            }
            if (apiDetail == null && detailState == "failed") {
                Log.d("AsterMedia", "detail api failed (status/perm), relying on sniff fallback")
            }
            val noteImages = payload?.optJSONArray("noteImages")?.let(::extractHttpUrlList).orEmpty()
            val noteMotions = payload?.optJSONArray("noteMotions")?.let(::extractHttpUrlList).orEmpty()
            val musicUrl = normalizeCandidateUrl(payload?.optString("musicUrl").orEmpty())
                .takeIf { it.startsWith("http") }
                .orEmpty()
            val isNotePage = payload?.optBoolean("isNotePage", false) == true || request.link.isNote
            if (noteImages.isNotEmpty() || noteMotions.isNotEmpty()) {
                // On a note page, give self.__pace_f / detail fetch up to 3 polls to populate musicUrl & motions
                // before accepting a bare DOM image fallback.
                val hasRichNoteData = noteMotions.isNotEmpty() || musicUrl.isNotBlank() || pollCount >= 4
                if (hasRichNoteData) {
                    finishWithImageNote(
                        images = noteImages,
                        motions = noteMotions,
                        musicUrl = musicUrl,
                        rawId = payload?.optString("videoId").orEmpty(),
                        rawTitle = payload?.optString("title").orEmpty(),
                        rawAuthor = payload?.optString("author").orEmpty(),
                        pageUrl = payload?.optString("pageUrl").orEmpty(),
                        view = view
                    )
                    return@evaluateJavascript
                }
            }
            if (candidateLikely && !isNotePage) {
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
        lastSniffedUrl = normalizeCandidateUrl(url)
        finishWithPayload(url, JSONObject(), view)
    }

    /**
     * 官方 detail 接口数据 → ResolvedMedia。play.urls 是 CDN 直链（yt-dlp 同样
     * 优先于 aweme/v1 包装链接），gears 提供各清晰度档位，填入
     * availableVariants 供清晰度选择条使用；嗅探到的 URL 保留为兼容备选。
     * 返回 true 表示已完成解析；false = detail 无可用直链，继续嗅探轮询。
     */
    private fun finishWithApiDetail(detail: JSONObject, view: WebView): Boolean {
        val detailImages = detail.optJSONArray("images")?.let(::extractHttpUrlList).orEmpty()
        val detailMotions = detail.optJSONArray("motions")?.let(::extractHttpUrlList).orEmpty()
        if (detailImages.isNotEmpty() || detailMotions.isNotEmpty()) {
            val musicUrl = normalizeCandidateUrl(detail.optString("musicUrl"))
                .takeIf { it.startsWith("http") }
                .orEmpty()
            finishWithImageNote(
                images = detailImages,
                motions = detailMotions,
                musicUrl = musicUrl,
                rawId = detail.optString("aweme_id"),
                rawTitle = detail.optString("desc"),
                rawAuthor = detail.optString("nickname"),
                pageUrl = view.url.orEmpty().ifBlank { lastPageUrl },
                view = view
            )
            return true
        }

        val play = detail.optJSONObject("play")
        val urls = play?.optJSONArray("urls")
        var primary = ""
        var secondary = ""
        if (urls != null) {
            val ranked = (0 until urls.length())
                .asSequence()
                .map { normalizeCandidateUrl(urls.optString(it)) }
                .filter(::isLikelyVideoUrl)
                .distinct()
                .toList()
                .sortedByDescending { candidateUrlScore(it) }
            primary = ranked.firstOrNull().orEmpty()
            secondary = ranked.getOrNull(1).orEmpty()
        }
        if (primary.isBlank()) {
            // detail 到手但没有可用直链：不终止，回退到嗅探轮询路径
            Log.d("AsterMedia", "api detail unusable: hasPlay=${play != null} urlCount=${urls?.length() ?: 0}")
            return false
        }

        finished = true
        handler.removeCallbacksAndMessages(null)
        Log.d("AsterMedia", "api detail ok: primary=${primary.take(140)} gears=${detail.optJSONArray("gears")?.length() ?: 0}")
        val currentUrl = view.url.orEmpty().ifBlank { lastPageUrl }
        val resolvedId = detail.optString("aweme_id")
            .ifBlank { request.link.videoId.orEmpty() }
            .ifBlank { DouyinUrlParser.extractVideoId(currentUrl).orEmpty() }
            .ifBlank { System.currentTimeMillis().toString() }
        val title = detail.optString("desc")
            .substringBefore(" - 抖音")
            .substringBefore("_抖音")
            .trim()
            .ifBlank { "抖音视频 $resolvedId" }
        val cookieHeader = CookieManager.getInstance().getCookie("https://www.douyin.com/").orEmpty()
        val selectedSource = DouyinMediaSourceSelector.preferWatermarkFree(primary)
        val variants = extractVariants(detail, primary)
        val qualityLabel = variants.firstOrNull { it.mediaUrl == primary }?.label
            ?: variants.firstOrNull()?.label
            .orEmpty()
        val thumbnailUrl = normalizeCandidateUrl(detail.optString("originCover"))
            .takeIf { it.startsWith("http") }
            .orEmpty()
        val fallbackThumbnail = normalizeCandidateUrl(detail.optString("cover")).takeIf { it.startsWith("http") }.orEmpty()
        onProgress("已找到视频源")
        onResolved(
            ResolvedMedia(
                videoId = resolvedId,
                title = title,
                author = detail.optString("nickname").trim(),
                thumbnailUrl = thumbnailUrl,
                thumbnailFallbackUrl = fallbackThumbnail,
                mediaUrl = selectedSource.primaryUrl,
                sourceUrl = request.link.sourceUrl,
                referer = currentUrl.ifBlank { "https://www.douyin.com/" },
                userAgent = view.settings.userAgentString.orEmpty(),
                cookieHeader = cookieHeader,
                compatibilityMediaUrl = listOf(secondary, lastSniffedUrl, selectedSource.compatibilityUrl)
                    .firstOrNull { it.isNotBlank() && it != selectedSource.primaryUrl }
                    .orEmpty(),
                prefersWatermarkFree = selectedSource.watermarkFreePreferred,
                qualityLabel = qualityLabel,
                availableVariants = variants
            )
        )
        return true
    }

    private fun finishWithImageNote(
        images: List<String>,
        motions: List<String>,
        musicUrl: String,
        rawId: String,
        rawTitle: String,
        rawAuthor: String,
        pageUrl: String,
        view: WebView
    ) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        val currentUrl = pageUrl.ifBlank { view.url.orEmpty() }.ifBlank { lastPageUrl }
        val resolvedId = rawId
            .ifBlank { request.link.videoId.orEmpty() }
            .ifBlank { DouyinUrlParser.extractVideoId(currentUrl).orEmpty() }
            .ifBlank { System.currentTimeMillis().toString() }
        val title = rawTitle
            .substringBefore(" - 抖音")
            .substringBefore("_抖音")
            .trim()
            .ifBlank { "抖音图文 $resolvedId" }
        val cookieHeader = CookieManager.getInstance().getCookie("https://www.douyin.com/").orEmpty()
        val cleanedMotions = motions.map { DouyinMediaSourceSelector.preferWatermarkFree(it).primaryUrl }.distinct()
        val isMotionNote = cleanedMotions.isNotEmpty()
        val count = if (isMotionNote) cleanedMotions.size else images.size
        val label = when {
            isMotionNote && musicUrl.isNotBlank() -> "实况动图配乐 (${count}段) · 无水印"
            isMotionNote -> "实况动图视频 (${count}段) · 无水印"
            musicUrl.isNotBlank() -> "图文配乐合成 (${count}张) · 无水印"
            else -> "图文合成视频 (${count}张) · 无水印"
        }
        val primaryPreviewUrl = if (isMotionNote) cleanedMotions.first() else images.first()
        val coverUrl = images.firstOrNull().orEmpty().ifBlank { primaryPreviewUrl }
        val variant = MediaVariant(
            id = "douyin-note-$count",
            label = label,
            mediaUrl = primaryPreviewUrl,
            audioUrl = musicUrl,
            imageUrls = images,
            motionVideoUrls = cleanedMotions,
            prefersWatermarkFree = true,
            mimeType = "video/mp4",
            fileExtension = "mp4"
        )
        Log.d(
            "AsterMedia",
            "douyin note resolved: id=$resolvedId images=${images.size} motions=${cleanedMotions.size} hasMusic=${musicUrl.isNotBlank()}"
        )
        onProgress(if (isMotionNote) "已识别实况动图与背景配乐" else "已识别图文配乐（${images.size} 张无水印原图）")
        onResolved(
            ResolvedMedia(
                videoId = resolvedId,
                title = title,
                author = rawAuthor.trim(),
                thumbnailUrl = coverUrl,
                thumbnailFallbackUrl = images.getOrNull(1).orEmpty(),
                mediaUrl = primaryPreviewUrl,
                audioUrl = musicUrl,
                imageUrls = images,
                motionVideoUrls = cleanedMotions,
                sourceUrl = request.link.sourceUrl,
                referer = currentUrl.ifBlank { "https://www.douyin.com/" },
                userAgent = view.settings.userAgentString.orEmpty(),
                cookieHeader = cookieHeader,
                prefersWatermarkFree = true,
                platform = com.adong.adchat.media.MediaPlatform.Douyin,
                qualityLabel = label,
                mimeType = "video/mp4",
                fileExtension = "mp4",
                availableVariants = listOf(variant)
            )
        )
    }

    /**
     * `iesdouyin.com/share/note/<id>` under Mobile UA does not embed `videoInfoRes`,
     * whereas `www.douyin.com/note/<id>` under Desktop Chrome UA embeds the complete
     * `self.__pace_f` payload (`images`, Live Photo `images[].video.playAddr`, and `music.playUrl`).
     */
    private fun redirectMobileNoteToDesktop(view: WebView, uri: Uri?): Boolean {
        val full = uri?.toString().orEmpty()
        if (full.isBlank()) return false
        val path = uri?.path.orEmpty()
        if (!path.contains("/note/", ignoreCase = true) && !path.contains("/slides/", ignoreCase = true)) {
            return false
        }
        val host = uri?.host?.lowercase().orEmpty()
        if (host.contains("iesdouyin.com") || host == "m.douyin.com") {
            val noteId = DouyinUrlParser.extractVideoId(full) ?: request.link.videoId
            if (!noteId.isNullOrBlank()) {
                val desktopUrl = "https://www.douyin.com/note/$noteId"
                if (lastPageUrl != desktopUrl) {
                    lastPageUrl = desktopUrl
                    view.settings.userAgentString = DESKTOP_CHROME_UA
                    view.loadUrl(desktopUrl)
                    return true
                }
            }
        }
        return false
    }

    private fun extractHttpUrlList(array: JSONArray): List<String> =
        (0 until array.length())
            .map { normalizeCandidateUrl(array.optString(it)) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()

    /**
     * yt-dlp 的做法：aweme/v1 包装链接可能被限流（"Downloads from API might
     * get blocked"），douyinvod / video/tos 直链优先。
     */
    private fun candidateUrlScore(url: String): Int {
        var score = 0
        val lower = url.lowercase()
        if (lower.contains("douyinvod")) score += 200
        if (lower.contains("/video/tos/")) score += 180
        if (lower.contains(".mp4")) score += 120
        if (lower.contains("/aweme/v1/play")) score -= 100
        return score
    }

    /** bit_rate 档位 → 清晰度变体；主档 URL 保底在列。 */
    private fun extractVariants(detail: JSONObject, primaryUrl: String): List<MediaVariant> {
        val variants = mutableListOf<MediaVariant>()
        val gears = detail.optJSONArray("gears")
        if (gears != null) {
            val seenLabels = mutableSetOf<String>()
            for (index in 0 until gears.length()) {
                val entry = gears.optJSONObject(index) ?: continue
                val urls = entry.optJSONArray("urls") ?: continue
                val url = (0 until urls.length())
                    .asSequence()
                    .map { normalizeCandidateUrl(urls.optString(it)) }
                    .filter(::isLikelyVideoUrl)
                    .maxByOrNull(::candidateUrlScore)
                    ?: continue
                val gear = entry.optString("gear").trim()
                if (gear.isBlank() || !seenLabels.add(gear)) continue
                variants.add(
                    MediaVariant(
                        id = "gear-$index",
                        label = gear,
                        mediaUrl = url,
                        fileSize = entry.optLong("size", 0L)
                    )
                )
            }
        }
        if (variants.none { it.mediaUrl == primaryUrl }) {
            variants.add(0, MediaVariant(id = "gear-primary", label = "默认", mediaUrl = primaryUrl))
        }
        return variants
    }

    private fun finishWithPayload(url: String, payload: JSONObject, view: WebView) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        Log.d("AsterMedia", "resolver finished: candidateUrl=${normalizeCandidateUrl(url).take(160)} candidateCount=${payload.optInt("candidateCount")} pageUrl=${view.url.orEmpty().take(120)}")
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
        const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
              function isAudioOnly(url) {
                return /(?:\.mp3|\.m4a)(?:\?|$)|ies-music|douyinstatic\.com\/obj\/tos-cn-ve-2774|mime_type=audio/i.test(url);
              }
              function scoreUrl(value, originScore) {
                var url = normalize(value);
                if (!/^https?:\/\//i.test(url) || isImage(url) || isAudioOnly(url) || /\.m3u8(?:\?|$)/i.test(url)) return 0;
                var fromMediaElement = (originScore || 0) >= 1000;
                var strongMarker = /douyinvod|\/video\/tos\/|\/video\/play|\/aweme\/v1\/play|\.mp4(?:\?|$)|video_id=|mime_type=video/i.test(url);
                if (!fromMediaElement && !strongMarker) return 0;
                var score = originScore || 0;
                // /aweme/v1/play(wm) carries the canonical video_id that DouyinMediaSourceSelector
                // rewrites to the watermark-free /aweme/v1/play/ endpoint. A <video> tag's currentSrc
                // after redirect is often a watermarked douyinvod CDN URL, so prefer play/playwm!
                if (/\/aweme\/v1\/play(?:wm)?\//i.test(url) && /video_id=/i.test(url)) score += 900;
                if (/douyinvod/i.test(url)) score += 480;
                if (/\/video\/tos\//i.test(url)) score += 420;
                if (/\.mp4(?:\?|$)/i.test(url)) score += 360;
                if (/video_id=|mime_type=video/i.test(url)) score += 240;
                if (/\/video\/play|playwm/i.test(url)) score += 180;
                return score;
              }

              var foundTitle = '';
              var foundAuthor = '';
              var foundMusicUrl = '';
              var noteImages = [];
              var noteImageSeen = {};
              var noteMotions = [];
              var noteMotionSeen = {};
              var isNotePage = /\/(?:note|slides)\//i.test(location.pathname || '');

              function addNoteImage(rawUrl) {
                var u = normalize(rawUrl);
                if (!/^https?:\/\//i.test(u) || /avatar|user-avatar/i.test(u)) return;
                if (!noteImageSeen[u]) {
                  noteImageSeen[u] = true;
                  noteImages.push(u);
                }
              }
              function addNoteMotion(rawUrl) {
                var u = normalize(rawUrl);
                if (!/^https?:\/\//i.test(u) || isImage(u) || isAudioOnly(u)) return;
                if (!noteMotionSeen[u]) {
                  noteMotionSeen[u] = true;
                  noteMotions.push(u);
                }
              }

              var candidates = [];
              var seen = {};
              function add(value, originScore) {
                var url = normalize(value);
                if (isAudioOnly(url)) {
                  if (!foundMusicUrl && /^https?:\/\//i.test(url)) foundMusicUrl = url;
                  return;
                }
                var score = scoreUrl(url, originScore);
                if (!score || seen[url]) return;
                seen[url] = true;
                candidates.push({ url: url, score: score });
              }

              try {
                Array.prototype.slice.call(document.querySelectorAll('video')).forEach(function (node) {
                  var isBgmPlayer = node.getAttribute('data-index') === '-1';
                  var srcs = [node.currentSrc, node.src, node.getAttribute('src')];
                  Array.prototype.slice.call(node.querySelectorAll('source')).forEach(function (sNode) {
                    srcs.push(sNode.src || sNode.getAttribute('src'));
                  });
                  srcs.forEach(function (rawSrc) {
                    var u = normalize(rawSrc);
                    if (!/^https?:\/\//i.test(u)) return;
                    if (isBgmPlayer || isAudioOnly(u)) {
                      if (!foundMusicUrl) foundMusicUrl = u;
                    } else if (isNotePage) {
                      addNoteMotion(u);
                    } else {
                      add(u, 1150);
                    }
                  });
                  if (!window.__adchatPoster && node.poster) window.__adchatPoster = node.poster;
                });
              } catch (error) {}

              try {
                performance.getEntriesByType('resource').forEach(function (entry) {
                  add(entry.name, 520);
                });
              } catch (error) {}

              function pickBestImageUrl(item) {
                if (!item || typeof item !== 'object') return '';
                var lists = [item.url_list, item.urlList];
                for (var li = 0; li < lists.length; li++) {
                  var arr = lists[li];
                  if (!Array.isArray(arr) || !arr.length) continue;
                  for (var ai = 0; ai < arr.length; ai++) {
                    var u = normalize(arr[ai]);
                    if (/^https?:\/\//i.test(u) && !/tplv-dy-water/i.test(u) && !/\.webp(?:\?|$)/i.test(u)) return u;
                  }
                  for (var bi = 0; bi < arr.length; bi++) {
                    var u2 = normalize(arr[bi]);
                    if (/^https?:\/\//i.test(u2) && !/tplv-dy-water/i.test(u2)) return u2;
                  }
                  var first = normalize(arr[0]);
                  if (/^https?:\/\//i.test(first)) return first;
                }
                return '';
              }
              function pickMotionVideoUrl(item) {
                if (!item || typeof item !== 'object' || !item.video || typeof item.video !== 'object') return '';
                var v = item.video;
                var nodes = [v.play_addr_h264, v.play_addr, v.playAddr, v.download_addr, v.downloadAddr];
                for (var ni = 0; ni < nodes.length; ni++) {
                  var node = nodes[ni];
                  if (!node) continue;
                  if (Array.isArray(node) && node.length) {
                    var first = node[0];
                    var src = typeof first === 'string' ? normalize(first) : normalize((first && (first.src || first.url)) || '');
                    if (/^https?:\/\//i.test(src) && !isAudioOnly(src)) return src;
                  }
                  var list = node.url_list || node.urlList;
                  if (Array.isArray(list) && list.length) {
                    var u0 = normalize(list[0]);
                    if (/^https?:\/\//i.test(u0) && !isAudioOnly(u0)) return u0;
                  }
                }
                var api = normalize(v.playApi || v.play_api || '');
                if (/^https?:\/\//i.test(api)) return api;
                return '';
              }
              function pickMusicFromAddr(addr) {
                if (!addr || typeof addr !== 'object') return '';
                var list = addr.url_list || addr.urlList;
                if (Array.isArray(list) && list.length) {
                  var u0 = normalize(list[0]);
                  if (/^https?:\/\//i.test(u0)) return u0;
                }
                var uri = normalize(addr.uri);
                if (/^https?:\/\//i.test(uri)) return uri;
                return '';
              }
              try {
                Array.prototype.slice.call(document.querySelectorAll('audio')).forEach(function (node) {
                  var au = normalize(node.currentSrc || node.src || node.getAttribute('src'));
                  if (!foundMusicUrl && /^https?:\/\//i.test(au)) foundMusicUrl = au;
                });
              } catch (error) {}

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
                  var rawImages = Array.isArray(value.images) && value.images.length ? value.images :
                    (value.image_post_info && Array.isArray(value.image_post_info.images) ? value.image_post_info.images : null);
                  var isImageNote = !!(rawImages && rawImages.length);
                  if (isImageNote) {
                    isNotePage = true;
                    for (var ii = 0; ii < rawImages.length; ii++) {
                      var pickedImg = pickBestImageUrl(rawImages[ii]);
                      if (pickedImg) addNoteImage(pickedImg);
                      var pickedMotion = pickMotionVideoUrl(rawImages[ii]);
                      if (pickedMotion) addNoteMotion(pickedMotion);
                    }
                  }
                  if (value.music && typeof value.music === 'object' && !foundMusicUrl) {
                    var mUrl = pickMusicFromAddr(value.music.play_url || value.music.playUrl);
                    if (!mUrl && typeof value.music.extra === 'string' && value.music.extra) {
                      try { mUrl = normalize(JSON.parse(value.music.extra).original_song_url); } catch (e) {}
                    }
                    if (/^https?:\/\//i.test(mUrl)) foundMusicUrl = mUrl;
                  }
                  if (value.video && typeof value.video === 'object') {
                    addThumbnail(value.video.origin_cover, 0);
                    addThumbnail(value.video.cover, 0);
                    addThumbnail(value.video.dynamic_cover, 0);
                    addThumbnail(value.video.animated_cover, 0);
                    var playAddr = value.video.play_addr || value.video.playAddr;
                    if (isImageNote) {
                      // On Douyin image notes, video.play_addr holds the background music MP3/M4A!
                      if (!foundMusicUrl) {
                        var noteBgm = pickMusicFromAddr(playAddr);
                        if (/^https?:\/\//i.test(noteBgm)) foundMusicUrl = noteBgm;
                      }
                    } else {
                      var vid = (playAddr && typeof playAddr.uri === 'string' && playAddr.uri) ||
                        (typeof value.video.vid === 'string' && value.video.vid) || '';
                      if (vid && /^[a-z0-9_]{10,}$/i.test(vid) && !/^https?:\/\//i.test(vid)) {
                        add('https://aweme.snssdk.com/aweme/v1/playwm/?video_id=' + vid + '&ratio=1080p&line=0', 950);
                      }
                    }
                  }
                  var keys = Object.keys(value);
                  for (var j = 0; j < keys.length && j < 120; j++) {
                    var childKey = keys[j];
                    if (isImageNote && childKey === 'video') continue;
                    if (/cover|poster|thumbnail/i.test(childKey)) addThumbnail(value[childKey], 0);
                    walk(value[childKey], childKey, depth + 1);
                  }
                }
              }

              try {
                Array.prototype.slice.call(document.scripts || []).forEach(function (script) {
                  var id = script.id || '';
                  var raw = script.textContent || '';
                  if (!raw || !(/RENDER_DATA|UNIVERSAL|SIGI_STATE/i.test(id) || /videoDetail|aweme_detail|bitRateList|note_\(id\)/.test(raw))) return;
                  var variants = [raw];
                  try { variants.push(decodeURIComponent(raw)); } catch (error) {}
                  for (var i = 0; i < variants.length; i++) {
                    try { walk(JSON.parse(variants[i]), '', 0); } catch (error) {}
                  }
                });
              } catch (error) {}

              try {
                var pace = window.__pace_f || self.__pace_f || [];
                for (var pi = 0; pi < pace.length; pi++) {
                  var pEntry = pace[pi];
                  var chunk = Array.isArray(pEntry) ? pEntry[1] : '';
                  if (typeof chunk !== 'string' || !chunk) continue;
                  var lines = chunk.split('\n');
                  for (var li = 0; li < lines.length; li++) {
                    var line = lines[li].trim();
                    if (!line || !(/aweme|images|videoDetail|music/.test(line))) continue;
                    var colon = line.indexOf(':');
                    var jsonPart = colon >= 0 ? line.slice(colon + 1).trim() : line;
                    if (jsonPart.charAt(0) === '[' || jsonPart.charAt(0) === '{') {
                      try { walk(JSON.parse(jsonPart), '', 0); } catch (e) {}
                    }
                  }
                }
              } catch (error) {}

              try {
                [window._SSR_DATA, window.SSR_RENDER_DATA, window._ROUTER_DATA, window.__INITIAL_STATE__, window.__NEXT_DATA__].forEach(function (state) {
                  if (state) walk(state, '', 0);
                });
              } catch (error) {}

              if (!noteImages.length) {
                try {
                  Array.prototype.slice.call(document.querySelectorAll('img')).forEach(function (img) {
                    var s = normalize(img.src || '');
                    if (s.indexOf('douyinpic') >= 0 && s.indexOf('WEBPC_RELATED') < 0 &&
                        (s.indexOf('biz_tag=aweme_images') >= 0 || s.indexOf('tplv-dy-aweme-images') >= 0)) {
                      addNoteImage(s);
                    }
                  });
                } catch (error) {}
              }

              candidates.sort(function (a, b) { return b.score - a.score; });
              function meta(selector) {
                var node = document.querySelector(selector);
                return node ? (node.content || node.getAttribute('content') || '') : '';
              }
              addThumbnail(meta('meta[property="og:image"]'), 0);
              addThumbnail(meta('meta[name="twitter:image"]'), 0);
              foundThumbnail = thumbnailCandidates.length ? thumbnailCandidates[0] : (noteImages.length ? noteImages[0] : '');
              var pageText = text(document.body && document.body.innerText).slice(0, 1200);
              var challenge = /安全验证|完成验证|拖动滑块|验证码|captcha|verify/i.test(pageText) ||
                !!document.querySelector('[class*="captcha"], iframe[src*="verify"], [id*="captcha"]');
              var pageUrl = location.href || '';
              var idMatch = pageUrl.match(/(?:\/(?:video|note|slides)\/|[?&](?:modal_id|aweme_id)=)(\d{8,})/);
              return JSON.stringify({
                url: candidates.length ? candidates[0].url : '',
                title: foundTitle || meta('meta[property="og:title"]') || document.title || '',
                author: foundAuthor || meta('meta[name="author"]') || '',
                thumbnail: foundThumbnail || '',
                thumbnailCandidates: thumbnailCandidates.slice(0, 4),
                noteImages: noteImages.slice(0, 36),
                noteMotions: noteMotions.slice(0, 36),
                isNotePage: isNotePage,
                musicUrl: foundMusicUrl || '',
                pageUrl: pageUrl,
                videoId: idMatch ? idMatch[1] : '',
                challenge: challenge,
                candidateCount: candidates.length,
                detailState: (function () {
                  var state = window.__asterDetail;
                  if (!state) return 'absent';
                  if (!state.ok) return 'failed';
                  return state.detail ? 'ready' : 'pending';
                })(),
                apiDetail: (window.__asterDetail && window.__asterDetail.ok && window.__asterDetail.detail) ? window.__asterDetail.detail : null
              });
            })();
        """.trimIndent()

    /**
     * 页面上下文直调官方 detail / slidesinfo 接口。支持普通视频与图文配乐作品。
     */
    val DOUYIN_DETAIL_FETCH = """
        (function () {
          var videoId = '__VIDEO_ID__';
          function done(ok, detail) {
            window.__asterDetail = { ok: ok, detail: detail || null };
          }
          if (window.__asterDetail) { return; }
          function slimAddr(addr) {
            if (!addr || !addr.url_list || !addr.url_list.length) return null;
            return { urls: addr.url_list.slice(0, 3), size: addr.data_size || 0 };
          }
          function pickImage(item) {
            if (!item) return '';
            var lists = [item.url_list, item.urlList];
            for (var l = 0; l < lists.length; l++) {
              var arr = lists[l];
              if (!Array.isArray(arr) || !arr.length) continue;
              for (var i = 0; i < arr.length; i++) {
                var u = String(arr[i] || '');
                if (/^https?:\/\//i.test(u) && !/tplv-dy-water/i.test(u) && !/\.webp(?:\?|$)/i.test(u)) return u;
              }
              if (/^https?:\/\//i.test(String(arr[0] || ''))) return String(arr[0]);
            }
            return '';
          }
          function pickMotion(item) {
            if (!item || !item.video) return '';
            var v = item.video;
            var nodes = [v.play_addr_h264, v.play_addr, v.playAddr, v.download_addr, v.downloadAddr];
            for (var n = 0; n < nodes.length; n++) {
              var node = nodes[n];
              if (!node) continue;
              if (Array.isArray(node) && node.length) {
                var f = node[0];
                var s = typeof f === 'string' ? f : ((f && (f.src || f.url)) || '');
                if (/^https?:\/\//i.test(String(s || ''))) return String(s);
              }
              var list = node.url_list || node.urlList;
              if (Array.isArray(list) && list.length && /^https?:\/\//i.test(String(list[0] || ''))) {
                return String(list[0]);
              }
            }
            if (typeof v.playApi === 'string' && /^https?:\/\//i.test(v.playApi)) return v.playApi;
            return '';
          }
          function pickMusic(detail) {
            var m = detail.music || {};
            var playUrl = m.play_url || m.playUrl || {};
            if (Array.isArray(playUrl.url_list) && playUrl.url_list.length && /^https?:\/\//i.test(playUrl.url_list[0])) {
              return String(playUrl.url_list[0]);
            }
            if (Array.isArray(playUrl.urlList) && playUrl.urlList.length && /^https?:\/\//i.test(playUrl.urlList[0])) {
              return String(playUrl.urlList[0]);
            }
            if (typeof playUrl.uri === 'string' && /^https?:\/\//i.test(playUrl.uri)) {
              return playUrl.uri;
            }
            var vAddr = (detail.video || {}).play_addr || (detail.video || {}).playAddr || {};
            if (Array.isArray(vAddr.url_list) && vAddr.url_list.length && /^https?:\/\//i.test(vAddr.url_list[0])) {
              return String(vAddr.url_list[0]);
            }
            if (typeof vAddr.uri === 'string' && /^https?:\/\//i.test(vAddr.uri)) {
              return vAddr.uri;
            }
            return '';
          }
          function slim(detail) {
            var video = detail.video || {};
            var rawImages = Array.isArray(detail.images) && detail.images.length ? detail.images :
              (detail.image_post_info && Array.isArray(detail.image_post_info.images) ? detail.image_post_info.images : []);
            var images = [];
            var motions = [];
            for (var i = 0; i < rawImages.length; i++) {
              var imgUrl = pickImage(rawImages[i]);
              if (imgUrl && images.indexOf(imgUrl) < 0) images.push(imgUrl);
              var motionUrl = pickMotion(rawImages[i]);
              if (motionUrl && motions.indexOf(motionUrl) < 0) motions.push(motionUrl);
            }
            var gears = [];
            if (!images.length && !motions.length) {
              (video.bit_rate || []).forEach(function (entry) {
                var addr = slimAddr(entry.play_addr);
                var gear = entry.gear_name || '';
                if (!addr || !gear || entry.is_bytevc2) return;
                gears.push({ gear: gear, urls: addr.urls, size: addr.size });
              });
            }
            var originCover = (video.origin_cover && video.origin_cover.url_list && video.origin_cover.url_list[0]) || video.origin_cover || '';
            var cover = (video.cover && video.cover.url_list && video.cover.url_list[0]) || video.cover || '';
            return {
              aweme_id: detail.aweme_id || detail.awemeId || '',
              desc: detail.desc || '',
              nickname: (detail.author || detail.authorInfo || {}).nickname || '',
              images: images.slice(0, 36),
              motions: motions.slice(0, 36),
              musicUrl: (images.length || motions.length) ? pickMusic(detail) : '',
              play: (images.length || motions.length) ? null : slimAddr(video.play_addr),
              gears: gears.slice(0, 5),
              originCover: typeof originCover === 'string' ? originCover : '',
              cover: typeof cover === 'string' ? cover : ''
            };
          }
          var host = location.hostname || '';
          var endpoint = host.indexOf('iesdouyin.com') >= 0
            ? ('/web/api/v2/aweme/slidesinfo/?reflow_source=reflow_page&web_id=75123456789012345&device_id=75123456789012345&aweme_ids=%5B' + videoId + '%5D&request_source=200')
            : ('/aweme/v1/web/aweme/detail/?aweme_id=' + videoId + '&device_platform=webapp&aid=6383&cookie_enabled=true&platform=PC&downlink=10&effective_type=4g');
          try {
            fetch(endpoint, { method: 'GET', credentials: 'include', headers: { 'Accept': 'application/json' } })
              .then(function (response) {
                if (!response.ok) { done(false, null); return null; }
                return response.json();
              })
              .then(function (json) {
                var detail = json && (json.aweme_detail || (json.data && json.data.aweme_detail) || (json.aweme_details && json.aweme_details[0]));
                if (detail && (detail.video || (detail.images && detail.images.length))) { done(true, slim(detail)); }
                else { done(false, null); }
              })
              .catch(function () { done(false, null); });
          } catch (error) {
            done(false, null);
          }
        })();
    """.trimIndent()
    }
}

