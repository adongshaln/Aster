package com.adong.adchat.media

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.adong.adchat.data.applyNetworkTrust
import com.adong.adchat.data.withCancellableResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class RemoteMediaResolver internal constructor(private val client: OkHttpClient) {
    constructor() : this(defaultClient())

    @Volatile private var biliCookieHeader: String = ""

    private val cookieMutex = Mutex()

    suspend fun resolve(input: MediaInput, onProgress: (String) -> Unit = {}): ResolvedMedia =
        withContext(Dispatchers.IO) {
            when (input.platform) {
                MediaPlatform.Direct -> resolveDirect(input, onProgress)
                MediaPlatform.Twitter -> resolveTwitter(input, onProgress)
                MediaPlatform.Bilibili -> resolveBilibili(input, onProgress)
                MediaPlatform.Douyin -> error("抖音链接需要使用页面解析器")
            }
        }

    private fun resolveDirect(input: MediaInput, onProgress: (String) -> Unit): ResolvedMedia {
        onProgress("正在检查直链")
        val extension = MediaInputParser.extensionOf(input.sourceUrl.substringBefore('#')).ifBlank { "mp4" }
        if (extension == "m3u8") throw MediaResolveException("暂不支持 M3U8 分片流")
        val mimeType = mimeTypeFor(extension)
        val title = runCatching {
            val path = input.sourceUrl.toHttpUrl().encodedPath
            URLDecoder.decode(path.substringAfterLast('/').substringBefore('?'), StandardCharsets.UTF_8.name())
                .ifBlank { "直链视频" }
        }.getOrDefault("直链视频")
        val variant = MediaVariant("original", "原始画质", input.sourceUrl, mimeType, extension)
        return ResolvedMedia(
            videoId = input.mediaId,
            title = title,
            mediaUrl = input.sourceUrl,
            sourceUrl = input.sourceUrl,
            referer = originOf(input.sourceUrl),
            userAgent = MOBILE_USER_AGENT,
            platform = MediaPlatform.Direct,
            qualityLabel = variant.label,
            mimeType = mimeType,
            fileExtension = extension,
            availableVariants = listOf(variant)
        )
    }

    private suspend fun resolveTwitter(input: MediaInput, onProgress: (String) -> Unit): ResolvedMedia {
        val tweetId = Regex("(?:status|statuses)/(\\d+)").find(input.sourceUrl)?.groupValues?.getOrNull(1)
            ?: input.mediaId.takeIf { it.all(Char::isDigit) }
            ?: throw MediaResolveException("无法识别 X 推文链接")
        val handle = Regex("""(?:twitter\.com|x\.com)/([^/]+)/status/\d+""", RegexOption.IGNORE_CASE)
            .find(input.sourceUrl)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val endpoints = buildList {
            if (handle != null) add("https://api.fxtwitter.com/$handle/status/$tweetId")
            add("https://api.fxtwitter.com/status/$tweetId")
        }
        var lastMessage = "X 没有返回可下载视频"
        endpoints.forEachIndexed { index, endpoint ->
            ensureActiveResolution()
            onProgress(if (index == 0) "正在读取 X 视频" else "正在切换解析线路")
            runCatching { requestJson(endpoint, mapOf("Accept" to "application/json")) }
                .onFailure {
                    if (it is CancellationException) throw it
                    currentCoroutineContext().ensureActive()
                    lastMessage = it.message ?: lastMessage
                }
                .getOrNull()
                ?.let { json -> normalizeTwitter(json, input, tweetId)?.let { return it } }
        }
        throw MediaResolveException(lastMessage)
    }

    private fun normalizeTwitter(root: JSONObject, input: MediaInput, tweetId: String): ResolvedMedia? {
        val tweet = root.optJSONObject("tweet") ?: root
        val videos = firstArray(
            tweet.optJSONObject("media")?.optJSONArray("videos"),
            tweet.optJSONArray("media_extended"),
            root.optJSONArray("media_extended")
        ) ?: JSONArray()

        val variants = mutableListOf<MediaVariant>()
        var thumbnail = ""
        for (videoIndex in 0 until videos.length()) {
            val video = videos.optJSONObject(videoIndex) ?: continue
            if (thumbnail.isBlank()) thumbnail = video.firstString("thumbnail_url", "thumbnail")
            val candidates = video.optJSONArray("formats") ?: video.optJSONArray("variants")
            if (candidates != null) {
                for (index in 0 until candidates.length()) {
                    val item = candidates.optJSONObject(index) ?: continue
                    twitterVariant(item.firstString("url"), item, variants.size)?.let(variants::add)
                }
            } else {
                twitterVariant(video.firstString("url"), video, variants.size)?.let(variants::add)
            }
        }
        root.optJSONArray("mediaURLs")?.let { urls ->
            for (index in 0 until urls.length()) {
                twitterVariant(urls.optString(index), null, variants.size)?.let(variants::add)
            }
        }

        val deduped = variants.distinctBy(MediaVariant::mediaUrl)
            .sortedByDescending { qualityNumber(it.label) }
            .mapIndexed { index, item -> item.copy(id = "x-${qualityNumber(item.label)}-$index") }
        if (deduped.isEmpty()) return null
        val selected = deduped.first()
        val authorObject = tweet.optJSONObject("author")
        val author = tweet.firstString("user_name")
            .ifBlank { authorObject?.firstString("name", "screen_name").orEmpty() }
            .ifBlank { root.firstString("user_name", "user_screen_name") }
        return ResolvedMedia(
            videoId = tweetId,
            title = tweet.firstString("text").ifBlank { root.firstString("text") }.ifBlank { "X 视频" },
            author = author,
            thumbnailUrl = thumbnail,
            mediaUrl = selected.mediaUrl,
            sourceUrl = input.sourceUrl,
            referer = "https://x.com/",
            userAgent = MOBILE_USER_AGENT,
            platform = MediaPlatform.Twitter,
            qualityLabel = selected.label,
            mimeType = selected.mimeType,
            fileExtension = selected.fileExtension,
            expectedFileSize = selected.fileSize,
            availableVariants = deduped
        )
    }

    private fun twitterVariant(url: String, json: JSONObject?, index: Int): MediaVariant? {
        if (url.isBlank()) return null
        val contentType = json?.firstString("content_type", "mime_type").orEmpty()
        val container = json?.firstString("container").orEmpty()
        if (!url.substringBefore('?').endsWith(".mp4", true) &&
            contentType != "video/mp4" && container != "mp4"
        ) return null
        val height = Regex("/(\\d+)x(\\d+)/").find(url)?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: json?.optInt("height")?.takeIf { it > 0 }
        val bitrate = json?.optLong("bitrate")?.takeIf { it > 0L }
        val label = when {
            height != null -> "${height}p"
            bitrate != null -> "${bitrate / 1_000} kbps"
            else -> "视频 ${index + 1}"
        }
        return MediaVariant("x-$index", label, url, "video/mp4", "mp4")
    }

    private suspend fun resolveBilibili(input: MediaInput, onProgress: (String) -> Unit): ResolvedMedia {
        onProgress("正在读取 B 站信息")
        val resolvedSource = resolveBilibiliSource(input.sourceUrl)
        val bvidOrAid = extractBiliId(resolvedSource)
            ?: extractBiliId(input.mediaId)
            ?: throw MediaResolveException("无法识别 B 站视频编号")
        val headers = biliHeaders(resolvedSource).toMutableMap()
        resolveBiliAnonymousCookie(headers)?.let { headers["Cookie"] = it }
        val data = resolveBiliViewData(bvidOrAid, resolvedSource, headers, onProgress)
        val bvid = data.optString("bvid").ifBlank { bvidOrAid.takeIf { it.startsWith("BV") }.orEmpty() }
        val aid = data.optLong("aid", 0L).takeIf { it > 0L }
            ?: bvidOrAid.removePrefix("av").removePrefix("AV").toLongOrNull()
            ?: 0L
        val cid = selectBiliCid(data, resolvedSource)
        if ((bvid.isBlank() && aid <= 0L) || cid <= 0L) throw MediaResolveException("B 站视频信息不完整")

        onProgress("正在获取无水印视频流")
        val defaultReferer = resolvedSource.takeIf { it.contains("bilibili.com") } ?: "https://www.bilibili.com/"
        val variants = resolveBiliVariants(bvid, aid, cid, headers, defaultReferer, onProgress)
        val selected = variants.firstOrNull()
            ?: throw MediaResolveException("B 站没有返回可直接保存的视频流")
        val thumbnail = BilibiliMediaPolicy.normalizeThumbnail(data.optString("pic"))
        return ResolvedMedia(
            videoId = bvid.ifBlank { "av$aid" },
            title = data.optString("title").ifBlank { "Bilibili 视频" },
            author = data.optJSONObject("owner")?.optString("name").orEmpty()
                .ifBlank { data.optJSONObject("upper")?.optString("name").orEmpty() },
            thumbnailUrl = thumbnail,
            mediaUrl = selected.mediaUrl,
            sourceUrl = resolvedSource,
            referer = selected.variantReferer ?: defaultReferer,
            compatibilityMediaUrl = selected.fallbackMediaUrl,
            audioUrl = selected.audioUrl,
            compatibilityAudioUrl = selected.fallbackAudioUrl,
            prefersWatermarkFree = selected.prefersWatermarkFree,
            userAgent = selected.variantUserAgent ?: DESKTOP_USER_AGENT,
            cookieHeader = headers["Cookie"].orEmpty(),
            platform = MediaPlatform.Bilibili,
            qualityLabel = selected.label,
            mimeType = selected.mimeType,
            fileExtension = selected.fileExtension,
            expectedFileSize = selected.fileSize,
            availableVariants = variants
        )
    }

    private suspend fun resolveBilibiliSource(sourceUrl: String): String {
        if (extractBiliId(sourceUrl) != null) return sourceUrl
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            ensureActiveResolution()
            try {
                val request = Request.Builder()
                    .url(sourceUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
                return client.newCall(request).withCancellableResponse { response ->
                    if (!response.isSuccessful) throw MediaResolveException("B 站短链接返回 ${response.code}")
                    response.request.url.toString()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                lastError = error
                biliBackoff(attempt)
            }
        }
        throw MediaResolveException(lastError?.message ?: "B 站短链接解析失败")
    }

    private suspend fun resolveBiliViewData(
        videoId: String,
        sourceUrl: String,
        headers: Map<String, String>,
        onProgress: (String) -> Unit
    ): JSONObject {
        val query = if (videoId.startsWith("BV")) "bvid=$videoId" else "aid=$videoId"
        val paths = listOf(
            "/x/web-interface/view?$query",
            "/x/web-interface/wbi/view?$query",
            "/x/web-interface/view/detail?$query"
        )
        var apiFailure: Throwable? = null
        paths.forEachIndexed { index, path ->
            ensureActiveResolution()
            val result = runCatching {
                val data = requestBiliJson(path, headers, rounds = if (index == 0) 2 else 1)
                    .optJSONObject("data")
                    ?: throw MediaResolveException("B 站没有返回视频信息")
                data.optJSONObject("View") ?: data
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            currentCoroutineContext().ensureActive()
            result.getOrNull()?.takeIf { it.has("bvid") || it.has("aid") }?.let { return it }
            apiFailure = result.exceptionOrNull() ?: apiFailure
            if (index == 0) onProgress("主接口波动，正在切换 B 站线路")
        }

        onProgress("接口波动，正在读取 B 站页面")
        val pageData = fetchBiliPageData(sourceUrl, headers)
        if (pageData != null) return pageData
        throw MediaResolveException(apiFailure?.message ?: "B 站视频信息暂时不可用")
    }

    private suspend fun fetchBiliPageData(sourceUrl: String, headers: Map<String, String>): JSONObject? {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            ensureActiveResolution()
            try {
                val request = Request.Builder().url(sourceUrl).get().apply {
                    headers.forEach { (name, value) -> if (value.isNotBlank()) header(name, value) }
                    header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    header("Cache-Control", "no-cache")
                }.build()
                val html = client.newCall(request).withCancellableResponse { response ->
                    if (!response.isSuccessful) throw MediaResolveException("B 站页面返回 ${response.code}")
                    response.body?.string().orEmpty()
                }
                BilibiliPageParser.extractVideoData(html)?.let { return it }
                throw MediaResolveException("B 站页面没有包含视频状态")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                lastError = error
                biliBackoff(attempt)
            }
        }
        return null
    }

    private suspend fun resolveBiliVariants(
        bvid: String,
        aid: Long,
        cid: Long,
        headers: Map<String, String>,
        defaultReferer: String,
        onProgress: (String) -> Unit
    ): List<MediaVariant> {
        val tvVariants = mutableListOf<MediaVariant>()
        var lastError: Throwable? = null

        // 1. Primary lane: Bilibili TV DASH endpoint (`/x/tv/playurl`, `mobi_app=android_tv_yst`, `fnval=4048`).
        //    Unlike Web/HTML5 streams which have the uploader's `bilibili` watermark burned into the video frames,
        //    the TV DASH transcode (`*-30320.m4s`, `*-100068.m4s`, `*-30288.m4s`) is completely watermark-free.
        if (aid > 0L) {
            try {
                val tvPath = BilibiliMediaPolicy.buildTvPlayurlPath(aid = aid, cid = cid, qn = 80, fnval = 4048)
                val tvRoot = requestBiliJson(tvPath, headers, rounds = 2)
                val tvData = tvRoot.optJSONObject("data") ?: tvRoot
                tvVariants += extractBiliDashVariants(
                    playData = tvData,
                    idPrefix = "bili-tv",
                    watermarkFree = true,
                    variantReferer = "",
                    variantUserAgent = TV_CDN_USER_AGENT
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                lastError = error
            }
        }

        // 2. Secondary lane: Web HTML5 progressive MP4 (`fnval=0&platform=html5&high_quality=1&try_look=1`).
        //    Provides higher resolution options (e.g., 1080P when anonymous TV caps at 720P) or serves as fallback.
        val webVariants = mutableListOf<MediaVariant>()
        var sawDash = false
        val qualities = listOf(80, 64, 32, 16)
        qualities.forEachIndexed { index, requestedQuality ->
            val path = "/x/player/playurl?bvid=$bvid&cid=$cid&qn=$requestedQuality&fnval=0&fourk=1&platform=html5&high_quality=1&try_look=1"
            val root = try {
                requestBiliJson(path, headers, rounds = if (tvVariants.isNotEmpty()) 1 else 2)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                currentCoroutineContext().ensureActive()
                lastError = error
                if (index == 0 && tvVariants.isEmpty()) onProgress("播放接口波动，正在切换线路")
                return@forEachIndexed
            }
            val play = root.optJSONObject("data") ?: return@forEachIndexed
            if (play.optJSONObject("dash") != null) sawDash = true
            val durl = play.optJSONArray("durl") ?: return@forEachIndexed
            if (durl.length() != 1) return@forEachIndexed
            val item = durl.optJSONObject(0) ?: return@forEachIndexed
            val backups = item.optJSONArray("backup_url")
            val primaryUrl = item.optString("url")
            val fallbackUrl = backups?.optString(0).orEmpty()
                .takeIf { it.isNotBlank() && it != primaryUrl }
                .orEmpty()
            val mediaUrl = primaryUrl.ifBlank { fallbackUrl }
            if (mediaUrl.isBlank()) return@forEachIndexed
            val actualQuality = play.optInt("quality", requestedQuality)
            webVariants += MediaVariant(
                id = "bili-web-$actualQuality",
                label = biliQualityLabel(actualQuality),
                mediaUrl = mediaUrl,
                fallbackMediaUrl = fallbackUrl.takeIf { primaryUrl.isNotBlank() }.orEmpty(),
                mimeType = "video/mp4",
                fileExtension = "mp4",
                fileSize = item.optLong("size", 0L),
                prefersWatermarkFree = false,
                variantReferer = defaultReferer,
                variantUserAgent = DESKTOP_USER_AGENT
            )
        }

        // 3. Fallback lane: Web DASH (`fnval=4048&try_look=1`) if neither TV DASH nor Web durl yielded streams.
        if (tvVariants.isEmpty() && webVariants.isEmpty()) {
            runCatching {
                val dashPath = "/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&fnval=4048&fnver=0&fourk=1&try_look=1"
                val dashRoot = requestBiliJson(dashPath, headers, rounds = 1)
                val dashData = dashRoot.optJSONObject("data")
                if (dashData != null) {
                    webVariants += extractBiliDashVariants(
                        playData = dashData,
                        idPrefix = "bili-dash",
                        watermarkFree = false,
                        variantReferer = defaultReferer,
                        variantUserAgent = DESKTOP_USER_AGENT
                    )
                }
            }.onFailure { if (it is CancellationException) throw it }
        }

        val combined = buildList {
            // Watermark-free TV variants come first so default selection is always watermark-free!
            addAll(tvVariants.distinctBy { it.id }.sortedByDescending { qualityNumber(it.label) })
            val tvQualities = tvVariants.map { qualityNumber(it.label) }.toSet()
            // Keep Web variants that offer higher resolution (e.g. 1080P) or when TV had no streams
            webVariants.distinctBy { it.id }
                .sortedByDescending { qualityNumber(it.label) }
                .forEach { webVar ->
                    if (tvVariants.isEmpty() || qualityNumber(webVar.label) !in tvQualities) {
                        add(webVar)
                    }
                }
        }
        if (combined.isNotEmpty()) return combined
        if (sawDash) throw MediaResolveException("该 B 站视频仅提供受保护的分轨流，当前无法直接保存")
        throw MediaResolveException(lastError?.message ?: "B 站播放地址暂时不可用，已自动重试")
    }

    private fun extractBiliDashVariants(
        playData: JSONObject,
        idPrefix: String,
        watermarkFree: Boolean,
        variantReferer: String,
        variantUserAgent: String
    ): List<MediaVariant> {
        val dash = playData.optJSONObject("dash") ?: return emptyList()
        val videos = dash.optJSONArray("video") ?: return emptyList()
        val audios = dash.optJSONArray("audio") ?: return emptyList()
        val durationSec = dash.optLong("duration", 0L).coerceAtLeast(0L)

        val audioItems = (0 until audios.length()).mapNotNull { audios.optJSONObject(it) }
        val bestAudio = audioItems.maxByOrNull { it.optInt("bandwidth", it.optInt("id", 0)) } ?: return emptyList()
        val (primaryAudioUrl, fallbackAudioUrl) = extractDashUrls(bestAudio)
        if (primaryAudioUrl.isBlank()) return emptyList()
        val audioBandwidth = bestAudio.optLong("bandwidth", 0L).coerceAtLeast(0L)

        val videoItems = (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }
        val groupedByQuality = videoItems.groupBy { it.optInt("id", 0) }.filterKeys { it > 0 }
        val variants = mutableListOf<MediaVariant>()

        for ((qn, candidates) in groupedByQuality.entries.sortedByDescending { it.key }) {
            // Prefer AVC / H.264 (`codecid == 7` or `avc1`) for universal Android MediaMuxer & gallery compatibility
            val chosen = candidates.firstOrNull {
                it.optInt("codecid") == 7 || it.optString("codecs").startsWith("avc", ignoreCase = true)
            } ?: candidates.firstOrNull() ?: continue
            val (primaryVideoUrl, fallbackVideoUrl) = extractDashUrls(chosen)
            if (primaryVideoUrl.isBlank()) continue
            val videoBandwidth = chosen.optLong("bandwidth", 0L).coerceAtLeast(0L)
            val estimatedBytes = if (durationSec > 0L && (videoBandwidth + audioBandwidth) > 0L) {
                ((videoBandwidth + audioBandwidth) * durationSec) / 8L
            } else 0L
            val baseLabel = biliQualityLabel(qn)
            variants += MediaVariant(
                id = "$idPrefix-$qn",
                label = if (watermarkFree) "$baseLabel · 无水印" else baseLabel,
                mediaUrl = primaryVideoUrl,
                fallbackMediaUrl = fallbackVideoUrl,
                audioUrl = primaryAudioUrl,
                fallbackAudioUrl = fallbackAudioUrl,
                prefersWatermarkFree = watermarkFree,
                variantReferer = variantReferer,
                variantUserAgent = variantUserAgent,
                mimeType = "video/mp4",
                fileExtension = "mp4",
                fileSize = estimatedBytes
            )
        }
        return variants
    }

    private fun extractDashUrls(node: JSONObject): Pair<String, String> {
        val primary = node.firstString("base_url", "baseUrl")
        val backups = node.optJSONArray("backup_url") ?: node.optJSONArray("backupUrl")
        val fallback = backups?.optString(0).orEmpty()
            .takeIf { it.isNotBlank() && it != "null" && it != primary }
            .orEmpty()
        val resolvedPrimary = primary.ifBlank { fallback }
        val resolvedFallback = if (primary.isNotBlank()) fallback else ""
        return resolvedPrimary to resolvedFallback
    }

    private fun selectBiliCid(data: JSONObject, sourceUrl: String): Long {
        val requestedPage = runCatching { sourceUrl.toHttpUrl().queryParameter("p")?.toIntOrNull() ?: 1 }.getOrDefault(1)
        val pages = data.optJSONArray("pages")
        return pages?.optJSONObject((requestedPage - 1).coerceAtLeast(0))?.optLong("cid")?.takeIf { it > 0L }
            ?: pages?.optJSONObject(0)?.optLong("cid")?.takeIf { it > 0L }
            ?: data.optLong("cid", 0L)
    }

    private suspend fun requestBiliJson(path: String, headers: Map<String, String>, rounds: Int): JSONObject {
        var lastError: Throwable? = null
        repeat(rounds.coerceAtLeast(1)) { round ->
            BILI_API_BASES.forEach { base ->
                ensureActiveResolution()
                try {
                    val root = requestJson("$base$path", headers)
                    val code = root.optInt("code", -1)
                    if (code == 0) return root
                    if (code == -101 || code == -10403 || code == -404) {
                        assertBiliSuccess(root)
                    }
                    lastError = runCatching { assertBiliSuccess(root) }.exceptionOrNull()
                        ?: MediaResolveException("B 站解析失败（$code）")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    currentCoroutineContext().ensureActive()
                    lastError = error
                }
            }
            biliBackoff(round)
        }
        throw MediaResolveException(lastError?.message ?: "B 站接口暂时不可用")
    }

    private suspend fun resolveBiliAnonymousCookie(baseHeaders: Map<String, String>): String? {
        biliCookieHeader.takeIf(String::isNotBlank)?.let { return it }
        cookieMutex.withLock {
            biliCookieHeader.takeIf(String::isNotBlank)?.let { return it }
            BILI_API_BASES.forEach { base ->
                val root = runCatching { requestJson("$base/x/frontend/finger/spi", baseHeaders) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                    ?: return@forEach
                if (root.optInt("code", -1) != 0) return@forEach
                val data = root.optJSONObject("data") ?: return@forEach
                val buvid3 = data.optString("b_3")
                val buvid4 = data.optString("b_4")
                val parts = buildList {
                    if (buvid3.isNotBlank()) add("buvid3=$buvid3")
                    if (buvid4.isNotBlank()) add("buvid4=$buvid4")
                    add("CURRENT_FNVAL=0")
                    add("CURRENT_QUALITY=80")
                }
                if (parts.size > 2) {
                    biliCookieHeader = parts.joinToString("; ")
                    return biliCookieHeader
                }
            }
        }
        return null
    }

    private suspend fun requestJson(url: String, headers: Map<String, String>): JSONObject {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> if (value.isNotBlank()) builder.header(name, value) }
        builder.header("Cache-Control", "no-cache").header("Pragma", "no-cache")
        return client.newCall(builder.build()).withCancellableResponse { response ->
            if (!response.isSuccessful) throw MediaResolveException("解析服务返回 ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw MediaResolveException("解析服务没有返回内容")
            runCatching { JSONObject(body) }.getOrElse {
                throw MediaResolveException("解析服务返回了异常内容")
            }
        }
    }

    private fun assertBiliSuccess(root: JSONObject) {
        val code = root.optInt("code", -1)
        if (code == 0) return
        val message = root.optString("message")
        throw MediaResolveException(
            when (code) {
                -101, -10403 -> "该 B 站内容需要登录或权限"
                -404 -> "B 站视频不存在或已失效"
                -352, -412 -> "B 站触发安全验证，正在切换解析线路"
                -799 -> "B 站请求过于频繁，正在稍后重试"
                else -> message.ifBlank { "B 站解析失败（$code）" }
            }
        )
    }

    private fun biliHeaders(sourceUrl: String): Map<String, String> = mapOf(
        "Referer" to sourceUrl,
        "Origin" to "https://www.bilibili.com",
        "User-Agent" to DESKTOP_USER_AGENT,
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.6",
        "Accept" to "application/json, text/plain, */*"
    )

    private suspend fun biliBackoff(attempt: Int) {
        ensureActiveResolution()
        delay((220L shl attempt.coerceIn(0, 2)) + (System.nanoTime() and 0x7fL))
    }

    private fun extractBiliId(value: String): String? = Regex("BV[0-9A-Za-z]{10}").find(value)?.value
        ?: Regex("(?:/video/)?av(\\d+)", RegexOption.IGNORE_CASE).find(value)?.groupValues?.getOrNull(1)
        ?: value.takeIf { it.all(Char::isDigit) }


    private fun biliQualityLabel(quality: Int): String = when (quality) {
        127 -> "8K"
        126 -> "杜比"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        74 -> "720P60"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "${quality}P"
    }

    private fun qualityNumber(label: String): Int = Regex("(\\d+)").find(label)?.value?.toIntOrNull() ?: 0

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"
        else -> "video/mp4"
    }

    private fun originOf(url: String): String = runCatching {
        val parsed = url.toHttpUrl()
        "${parsed.scheme}://${parsed.host}/"
    }.getOrDefault("")

    private suspend fun ensureActiveResolution() {
        currentCoroutineContext().ensureActive()
    }

    private fun firstArray(vararg arrays: JSONArray?): JSONArray? = arrays.firstOrNull { it != null && it.length() > 0 }

    private fun JSONObject.firstString(vararg keys: String): String {
        keys.forEach { key -> optString(key).takeIf { it.isNotBlank() && it != "null" }?.let { return it } }
        return ""
    }

    private companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .applyNetworkTrust()
            .build()

        const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36"
        const val TV_CDN_USER_AGENT = "Mozilla/5.0"
        val BILI_API_BASES = listOf("https://api.bilibili.com", "https://api.biliapi.com")
    }
}

class MediaResolveException(message: String) : Exception(message)



