package com.adong.adchat.island

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 短链展开器：跟随重定向拿到真实落地地址（v.douyin.com / b23.tv / t.co）。
 * 不下载页面体，只拿最终 URL，尽量轻量。
 */
object IslandLinkResolver {
    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val browserUA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    /** 返回重定向后的最终 URL；失败或未跳转时返回 null。 */
    suspend fun expand(shortUrl: String): String? = withContext(Dispatchers.IO) {
        if (!IslandLinkParser.isShortLink(shortUrl)) return@withContext null
        runCatching {
            val request = Request.Builder()
                .url(shortUrl)
                .header("User-Agent", browserUA)
                .head()
                .build()
            val response = client.newCall(request).execute()
            response.use {
                val finalUrl = it.request.url.toString()
                if (finalUrl != shortUrl) finalUrl else null
            }
        }.getOrNull()
    }
}
