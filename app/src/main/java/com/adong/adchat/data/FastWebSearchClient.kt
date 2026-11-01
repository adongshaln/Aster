package com.adong.adchat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

internal data class DirectSearchItem(
    val title: String,
    val url: String,
    val snippet: String,
    val summary: String = "",
    val siteName: String = "",
    val publishedDate: String = ""
)

/**
 * High-speed concurrent web search & page reader for model-autonomous ("self-search") workflows.
 *
 * Eliminates the slow LLM-inside-LLM bottleneck by executing search queries in parallel across:
 * 1. DeepSeek official `/v1/tools/search` (when the profile targets `api.deepseek.com`).
 * 2. Gemini native Google Search Grounding (`:generateContent` with `google_search`, when targeting `generativelanguage.googleapis.com`).
 * 3. Concurrent Bing RSS (`cn.bing.com` + `www.bing.com`) + Bing HTML + DuckDuckGo HTML scraping with connection pooling.
 */
internal object FastWebSearchClient {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val httpClient: OkHttpClient by lazy {
        defaultApiClient().newBuilder()
            .connectionPool(ConnectionPool(12, 2, TimeUnit.MINUTES))
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .writeTimeout(3500, TimeUnit.MILLISECONDS)
            .callTimeout(5500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun executeSearch(
        profile: ApiProfile,
        model: String,
        callId: String,
        queries: List<String>,
        source: String = "web",
        clientOverride: OkHttpClient? = null
    ): DelegatedSearchResult = withContext(Dispatchers.IO) {
        val cleanQueries = queries.map(String::trim).filter(String::isNotBlank).distinct().take(4)
        if (cleanQueries.isEmpty()) {
            return@withContext DelegatedSearchResult(
                output = JSONObject().put("ok", false).put("error", "search_query_empty").toString(),
                citations = emptyList(),
                activity = ChatToolActivity(callId, DELEGATED_WEB_SEARCH_TOOL, "搜索查询为空", TOOL_STATUS_FAILED)
            )
        }
        val activeClient = clientOverride ?: httpClient
        try {
            val perQueryResults = coroutineScope {
                cleanQueries.map { q ->
                    async {
                        q to searchSingleQuery(profile, model, q, activeClient)
                    }
                }.awaitAll()
            }

            val allCitations = linkedMapOf<String, ChatCitation>()
            val researchBuilder = StringBuilder()

            for ((q, pair) in perQueryResults) {
                val (items, groundedNote) = pair
                if (cleanQueries.size > 1) {
                    researchBuilder.append("### 搜索词：").append(q).append("\n")
                }
                if (groundedNote.isNotBlank()) {
                    researchBuilder.append(groundedNote.trim()).append("\n\n")
                }
                items.forEachIndexed { idx, item ->
                    if (item.url.isNotBlank()) {
                        allCitations.putIfAbsent(item.url, ChatCitation(item.title.ifBlank { item.url }, item.url))
                    }
                    researchBuilder.append("[${idx + 1}] ").append(item.title.ifBlank { item.url })
                    if (item.siteName.isNotBlank()) researchBuilder.append(" (").append(item.siteName).append(")")
                    if (item.publishedDate.isNotBlank()) researchBuilder.append(" · ").append(item.publishedDate)
                    researchBuilder.append("\nURL: ").append(item.url).append("\n")
                    val bodyText = item.summary.ifBlank { item.snippet }.trim()
                    if (bodyText.isNotBlank()) {
                        researchBuilder.append("摘要: ").append(bodyText).append("\n")
                    }
                    researchBuilder.append("\n")
                }
            }

            val finalResearch = researchBuilder.toString().trim()
            val citationsList = allCitations.values.toList()
            if (finalResearch.isBlank() && citationsList.isEmpty()) {
                return@withContext DelegatedSearchResult(
                    output = JSONObject()
                        .put("ok", false)
                        .put("error", "no_search_results")
                        .put("query", cleanQueries.joinToString(" | "))
                        .put("message", "未检索到相关网页结果，请换用更具体的关键词重试。")
                        .toString(),
                    citations = emptyList(),
                    activity = ChatToolActivity(
                        callId,
                        DELEGATED_WEB_SEARCH_TOOL,
                        "未找到「${cleanQueries.first().take(18)}」的相关结果",
                        TOOL_STATUS_COMPLETED
                    )
                )
            }

            val querySummary = if (cleanQueries.size == 1) {
                cleanQueries.first().take(20)
            } else {
                "${cleanQueries.first().take(14)} 等 ${cleanQueries.size} 项"
            }
            val label = if (citationsList.isEmpty()) {
                "已搜索「$querySummary」"
            } else {
                "已搜索「$querySummary」· ${citationsList.size} 个来源"
            }

            DelegatedSearchResult(
                output = delegatedSearchToolOutput(
                    query = cleanQueries.joinToString(" | "),
                    source = source,
                    backendModel = model.ifBlank { "direct-web-search" },
                    research = finalResearch,
                    citations = citationsList
                ),
                citations = citationsList,
                activity = ChatToolActivity(callId, DELEGATED_WEB_SEARCH_TOOL, label, TOOL_STATUS_COMPLETED)
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            DelegatedSearchResult(
                output = JSONObject()
                    .put("ok", false)
                    .put("error", "search_failed")
                    .put("message", e.message ?: "联网搜索失败")
                    .toString(),
                citations = emptyList(),
                activity = ChatToolActivity(callId, DELEGATED_WEB_SEARCH_TOOL, e.message ?: "联网搜索失败", TOOL_STATUS_FAILED)
            )
        }
    }

    private suspend fun searchSingleQuery(
        profile: ApiProfile,
        model: String,
        query: String,
        client: OkHttpClient
    ): Pair<List<DirectSearchItem>, String> = coroutineScope {
        val host = profile.baseUrl.lowercase()
        val isOfficialDeepSeek = host.contains("api.deepseek.com") && profile.apiKey.isNotBlank()
        val isOfficialGemini = host.contains("generativelanguage.googleapis.com") && profile.apiKey.isNotBlank()

        // 1. If connected directly to DeepSeek's official API, race DeepSeek `/v1/tools/search` alongside Bing RSS.
        val deepSeekDeferred = if (isOfficialDeepSeek) {
            async { runCatching { tryDeepSeekNativeSearch(profile, query, client) }.getOrNull() }
        } else null

        // 2. If connected directly to Google's Gemini API, race Gemini native Google Search Grounding.
        val geminiDeferred = if (isOfficialGemini && model.isGeminiModel()) {
            async { runCatching { tryGeminiNativeGrounding(profile, model, query, client) }.getOrNull() }
        } else null

        // 3. Always launch fast Bing RSS search concurrently (~180-350ms).
        val bingRssDeferred = async {
            runCatching { fetchBingRss(query, client, "https://cn.bing.com") }
                .getOrDefault(emptyList())
                .ifEmpty {
                    runCatching { fetchBingRss(query, client, "https://www.bing.com") }.getOrDefault(emptyList())
                }
        }

        // Check DeepSeek native search first if available
        val deepSeekItems = deepSeekDeferred?.await().orEmpty()
        if (deepSeekItems.size >= 3) {
            val rssItems = withTimeoutOrNull(250L) { bingRssDeferred.await() }.orEmpty()
            return@coroutineScope mergeSearchItems(deepSeekItems + rssItems) to ""
        }

        // Check Gemini native grounding if available
        val geminiGrounded = geminiDeferred?.await()
        if (geminiGrounded != null && (geminiGrounded.first.isNotEmpty() || geminiGrounded.second.isNotBlank())) {
            val rssItems = withTimeoutOrNull(250L) { bingRssDeferred.await() }.orEmpty()
            return@coroutineScope mergeSearchItems(geminiGrounded.first + rssItems) to geminiGrounded.second
        }

        var items = mergeSearchItems(deepSeekItems + bingRssDeferred.await())

        // 4. If RSS returned fewer than 4 items, race Bing HTML + DuckDuckGo HTML in parallel
        if (items.size < 4) {
            val htmlResults = listOf(
                async { runCatching { fetchBingHtml(query, client) }.getOrDefault(emptyList()) },
                async { runCatching { fetchDuckDuckGoHtml(query, client) }.getOrDefault(emptyList()) }
            ).awaitAll().flatten()
            items = mergeSearchItems(items + htmlResults)
        }

        // 5. Fast snippet enrichment for the top 2 results if their snippets are short (< 180 chars)
        val enriched = enrichTopItemsConcurrently(items, client)
        enriched to ""
    }

    private suspend fun enrichTopItemsConcurrently(
        items: List<DirectSearchItem>,
        client: OkHttpClient
    ): List<DirectSearchItem> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope items
        val enrichedTop = withTimeoutOrNull(1200L) {
            items.take(2).map { item ->
                async {
                    if (item.summary.length >= 220 || item.snippet.length >= 220) {
                        item
                    } else {
                        val excerpt = runCatching { fetchPageSnippet(item.url, client, maxChars = 550) }.getOrDefault("")
                        if (excerpt.length > item.snippet.length) {
                            item.copy(summary = excerpt)
                        } else {
                            item
                        }
                    }
                }
            }.awaitAll()
        } ?: return@coroutineScope items
        enrichedTop + items.drop(2)
    }

    internal fun tryDeepSeekNativeSearch(
        profile: ApiProfile,
        query: String,
        client: OkHttpClient
    ): List<DirectSearchItem> {
        val base = profile.baseUrl.trim().trimEnd('/')
            .removeSuffix("/v1")
            .removeSuffix("/beta")
            .removeSuffix("/anthropic")
        val url = "$base/v1/tools/search"
        val body = JSONObject()
            .put("query", query.take(128))
            .put("count", 8)
            .put("need_content", false)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${profile.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return emptyList()
            return parseDeepSeekSearchResponse(JSONObject(text))
        }
    }

    internal fun parseDeepSeekSearchResponse(root: JSONObject): List<DirectSearchItem> {
        val results = root.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<DirectSearchItem>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val url = obj.optString("url").trim()
            if (url.isBlank()) continue
            val title = obj.optString("title").trim().ifBlank { url }
            val snippet = obj.optString("snippet").trim()
            val summary = obj.optString("summary").trim().ifBlank { snippet }
            val siteName = obj.optString("site_name").takeUnless { it == "null" }?.trim().orEmpty()
            val date = obj.optString("date").takeUnless { it == "null" }?.trim()?.take(10).orEmpty()
            list += DirectSearchItem(
                title = title,
                url = url,
                snippet = snippet.ifBlank { summary.take(200) },
                summary = summary.take(700),
                siteName = siteName,
                publishedDate = date
            )
        }
        return list
    }

    internal fun tryGeminiNativeGrounding(
        profile: ApiProfile,
        model: String,
        query: String,
        client: OkHttpClient
    ): Pair<List<DirectSearchItem>, String>? {
        val cleanModel = model.trim().substringAfterLast('/').ifBlank { "gemini-2.5-flash" }
        val base = profile.baseUrl.trim().trimEnd('/')
            .replace(Regex("/v1beta/openai$"), "")
            .replace(Regex("/v1beta$"), "")
            .replace(Regex("/v1$"), "")
        val url = "$base/v1beta/models/$cleanModel:generateContent"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text",
                    "Search Google for accurate, up-to-date facts on: $query\nProvide concise factual notes with dates, numbers, and names.")))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1024))
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", profile.apiKey.trim())
            .header("Authorization", "Bearer ${profile.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return null
            return parseGeminiGroundingResponse(JSONObject(text))
        }
    }

    internal fun parseGeminiGroundingResponse(root: JSONObject): Pair<List<DirectSearchItem>, String> {
        val candidates = root.optJSONArray("candidates") ?: return emptyList<DirectSearchItem>() to ""
        val first = candidates.optJSONObject(0) ?: return emptyList<DirectSearchItem>() to ""
        val parts = first.optJSONObject("content")?.optJSONArray("parts")
        val noteBuilder = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val t = part.optString("text")
                if (t.isNotBlank()) noteBuilder.append(t)
            }
        }
        val items = mutableListOf<DirectSearchItem>()
        val grounding = first.optJSONObject("groundingMetadata")
        val chunks = grounding?.optJSONArray("groundingChunks")
        if (chunks != null) {
            for (i in 0 until chunks.length()) {
                val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                val uri = web.optString("uri").trim()
                val title = web.optString("title").trim().ifBlank { uri }
                if (uri.isNotBlank()) {
                    items += DirectSearchItem(title = title, url = uri, snippet = "")
                }
            }
        }
        return items to noteBuilder.toString().trim()
    }

    internal fun fetchBingRss(
        query: String,
        client: OkHttpClient,
        host: String = "https://cn.bing.com"
    ): List<DirectSearchItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "$host/search?q=$encoded&format=rss&count=10"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val xml = response.body?.string().orEmpty()
            return parseBingRssXml(xml)
        }
    }

    internal fun parseBingRssXml(xml: String): List<DirectSearchItem> {
        if (!xml.contains("<item>")) return emptyList()
        val items = mutableListOf<DirectSearchItem>()
        val itemRegex = Regex("<item>([\\s\\S]*?)</item>")
        val titleRegex = Regex("<title>([\\s\\S]*?)</title>")
        val linkRegex = Regex("<link>([\\s\\S]*?)</link>")
        val descRegex = Regex("<description>([\\s\\S]*?)</description>")
        val dateRegex = Regex("<pubDate>([\\s\\S]*?)</pubDate>")

        for (match in itemRegex.findAll(xml)) {
            val block = match.groupValues[1]
            val rawUrl = linkRegex.find(block)?.groupValues?.get(1)?.let(::decodeXmlEntities)?.trim().orEmpty()
            val url = unwrapRedirectUrl(rawUrl)
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val title = titleRegex.find(block)?.groupValues?.get(1)?.let(::cleanHtmlText).orEmpty().ifBlank { url }
            val snippet = descRegex.find(block)?.groupValues?.get(1)?.let(::cleanHtmlText).orEmpty()
            val pubDate = dateRegex.find(block)?.groupValues?.get(1)?.let(::cleanHtmlText).orEmpty()
            val hostName = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
            items += DirectSearchItem(
                title = title,
                url = url,
                snippet = snippet.take(400),
                summary = snippet.take(400),
                siteName = hostName,
                publishedDate = pubDate
            )
            if (items.size >= 8) break
        }
        return items
    }

    internal fun fetchBingHtml(query: String, client: OkHttpClient): List<DirectSearchItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://cn.bing.com/search?q=$encoded&count=10"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            return parseBingHtml(html)
        }
    }

    internal fun parseBingHtml(html: String): List<DirectSearchItem> {
        val items = mutableListOf<DirectSearchItem>()
        val blockRegex = Regex("<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>([\\s\\S]*?)</li>")
        val linkRegex = Regex("<h2[^>]*>[\\s\\S]*?<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?</h2>")
        val snippetRegex = Regex("<div[^>]*class=\"[^\"]*b_caption[^\"]*\"[^>]*>([\\s\\S]*?)</div>")

        for (match in blockRegex.findAll(html)) {
            val block = match.groupValues[1]
            val linkMatch = linkRegex.find(block) ?: continue
            val rawUrl = decodeXmlEntities(linkMatch.groupValues[1]).trim()
            val url = unwrapRedirectUrl(rawUrl)
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val title = cleanHtmlText(linkMatch.groupValues[2]).ifBlank { url }
            val snippet = snippetRegex.find(block)?.groupValues?.get(1)?.let(::cleanHtmlText).orEmpty()
            val hostName = runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
            items += DirectSearchItem(
                title = title,
                url = url,
                snippet = snippet.take(400),
                summary = snippet.take(400),
                siteName = hostName
            )
            if (items.size >= 8) break
        }
        return items
    }

    internal fun fetchDuckDuckGoHtml(query: String, client: OkHttpClient): List<DirectSearchItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            return parseDuckDuckGoHtml(html)
        }
    }

    internal fun parseDuckDuckGoHtml(html: String): List<DirectSearchItem> {
        val items = mutableListOf<DirectSearchItem>()
        val resultRegex = Regex("<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>[\\s\\S]*?(?:<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>([\\s\\S]*?)</a>)?")
        for (match in resultRegex.findAll(html)) {
            val rawUrl = decodeXmlEntities(match.groupValues[1]).trim()
            val url = unwrapRedirectUrl(rawUrl)
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val title = cleanHtmlText(match.groupValues[2]).ifBlank { url }
            val snippet = cleanHtmlText(match.groupValues[3])
            items += DirectSearchItem(
                title = title,
                url = url,
                snippet = snippet.take(400),
                summary = snippet.take(400)
            )
            if (items.size >= 8) break
        }
        return items
    }

    suspend fun executeFetchPage(
        profile: ApiProfile,
        callId: String,
        url: String,
        clientOverride: OkHttpClient? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext ToolExecutionResult(
                output = JSONObject().put("ok", false).put("error", "invalid_url").put("message", "仅支持 HTTP/HTTPS 链接").toString(),
                activity = ChatToolActivity(callId, FETCH_WEB_TOOL, "网页链接无效", TOOL_STATUS_FAILED)
            )
        }
        val activeClient = clientOverride ?: httpClient
        try {
            val host = profile.baseUrl.lowercase()
            if (host.contains("api.deepseek.com") && profile.apiKey.isNotBlank()) {
                val deepSeekFetch = runCatching { tryDeepSeekNativeFetch(profile, cleanUrl, activeClient) }.getOrNull()
                if (deepSeekFetch != null && deepSeekFetch.second.isNotBlank()) {
                    val title = deepSeekFetch.first.ifBlank { cleanUrl }
                    return@withContext ToolExecutionResult(
                        output = JSONObject()
                            .put("ok", true)
                            .put("url", cleanUrl)
                            .put("title", title)
                            .put("content", deepSeekFetch.second.take(12_000))
                            .toString(),
                        activity = ChatToolActivity(callId, FETCH_WEB_TOOL, "已读取网页：${title.take(22)}", TOOL_STATUS_COMPLETED)
                    )
                }
            }
            val (title, content) = fetchFullPageText(cleanUrl, activeClient, maxChars = 10_000)
            require(content.isNotBlank()) { "未能从该网页提取到可读正文" }
            ToolExecutionResult(
                output = JSONObject()
                    .put("ok", true)
                    .put("url", cleanUrl)
                    .put("title", title.ifBlank { cleanUrl })
                    .put("content", content)
                    .toString(),
                activity = ChatToolActivity(
                    callId,
                    FETCH_WEB_TOOL,
                    "已读取网页：${title.ifBlank { cleanUrl }.take(22)}",
                    TOOL_STATUS_COMPLETED
                )
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            ToolExecutionResult(
                output = JSONObject()
                    .put("ok", false)
                    .put("url", cleanUrl)
                    .put("error", e.message ?: "网页读取失败")
                    .toString(),
                activity = ChatToolActivity(callId, FETCH_WEB_TOOL, "网页读取失败：${cleanUrl.take(24)}", TOOL_STATUS_FAILED)
            )
        }
    }

    private fun tryDeepSeekNativeFetch(
        profile: ApiProfile,
        targetUrl: String,
        client: OkHttpClient
    ): Pair<String, String>? {
        val base = profile.baseUrl.trim().trimEnd('/')
            .removeSuffix("/v1")
            .removeSuffix("/beta")
            .removeSuffix("/anthropic")
        val endpoint = "$base/v1/tools/fetch"
        val body = JSONObject().put("url", targetUrl)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${profile.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) return null
            val root = JSONObject(text)
            return root.optString("title") to root.optString("content")
        }
    }

    private fun fetchPageSnippet(url: String, client: OkHttpClient, maxChars: Int = 550): String =
        fetchFullPageText(url, client, maxChars).second

    internal fun fetchFullPageText(url: String, client: OkHttpClient, maxChars: Int = 8000): Pair<String, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "" to ""
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (contentType.isNotBlank() && !contentType.contains("text/") && !contentType.contains("json") && !contentType.contains("xml") && !contentType.contains("html")) {
                return "" to ""
            }
            val raw = response.body?.source()?.let { source ->
                // Read at most 256 KB to keep page extraction ultra-fast and memory-safe
                source.request(262_144L)
                source.buffer.clone().readString(minOf(source.buffer.size, 262_144L), StandardCharsets.UTF_8)
            }.orEmpty()
            if (raw.isBlank()) return "" to ""
            val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.get(1)?.let(::cleanHtmlText).orEmpty()
            val cleaned = raw
                .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style\\b[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<noscript\\b[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<(nav|footer|header|aside|svg)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("</(p|div|h1|h2|h3|h4|li|tr|br|article|section)>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .let(::decodeXmlEntities)
                .lines()
                .map { it.replace(Regex("\\s+"), " ").trim() }
                .filter { it.length >= 12 }
                .joinToString("\n")
                .take(maxChars)
            return title to cleaned
        }
    }

    internal fun unwrapRedirectUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().let {
            if (it.startsWith("//")) "https:$it" else it
        }
        // Unwrap DuckDuckGo ?uddg=...
        if (trimmed.contains("uddg=")) {
            val encoded = trimmed.substringAfter("uddg=").substringBefore('&')
            val decoded = runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }.getOrDefault("")
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded
        }
        // Unwrap Bing /ck/a?...&u=a1aHR0c...
        if (trimmed.contains("/ck/a") && trimmed.contains("u=a1")) {
            val uParam = trimmed.substringAfter("u=a1").substringBefore('&')
            val decoded = runCatching {
                val padded = uParam.padEnd((uParam.length + 3) / 4 * 4, '=')
                String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
            }.getOrDefault("")
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) return decoded
        }
        return trimmed
    }

    private fun mergeSearchItems(items: List<DirectSearchItem>): List<DirectSearchItem> {
        val byUrl = linkedMapOf<String, DirectSearchItem>()
        for (item in items) {
            val key = item.url.trim().trimEnd('/')
            if (key.isBlank()) continue
            val existing = byUrl[key]
            if (existing == null) {
                byUrl[key] = item
            } else {
                byUrl[key] = existing.copy(
                    title = existing.title.ifBlank { item.title },
                    snippet = if (existing.snippet.length >= item.snippet.length) existing.snippet else item.snippet,
                    summary = if (existing.summary.length >= item.summary.length) existing.summary else item.summary,
                    siteName = existing.siteName.ifBlank { item.siteName },
                    publishedDate = existing.publishedDate.ifBlank { item.publishedDate }
                )
            }
        }
        return byUrl.values.take(10)
    }

    internal fun cleanHtmlText(raw: String): String = decodeXmlEntities(
        raw.replace(Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>"), "$1")
            .replace(Regex("<[^>]+>"), " ")
    ).replace(Regex("\\s+"), " ").trim()

    private fun decodeXmlEntities(input: String): String = input
        .replace("<!\\[CDATA\\[".toRegex(), "")
        .replace("]]>".toRegex(), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
}
