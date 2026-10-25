package com.adong.adchat.island

import com.adong.adchat.data.withCancellableResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 短链展开器：跟随重定向拿到真实落地地址（v.douyin.com / b23.tv / t.co）。
 * 不下载页面体，只拿最终 URL，尽量轻量。
 */
object IslandLinkResolver {
    private val client by lazy {
        publicWebClient(OkHttpClient()).newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val browserUA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    /** 返回重定向后的最终 URL；失败或未跳转时返回 null。 */
    suspend fun expand(shortUrl: String): String? = try {
        withContext(Dispatchers.IO) {
            if (!IslandLinkParser.isShortLink(shortUrl)) return@withContext null
            withTimeout(12_000) {
                var target = checkedHttpUrl(shortUrl)
                repeat(6) { hop ->
                    requirePublicWebTarget(target)
                    val request = Request.Builder().url(target)
                        .header("User-Agent", browserUA).head().build()
                    var next: String? = null
                    val landed = client.newCall(request).withCancellableResponse { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            next = response.header("Location")?.let { target.resolve(it)?.toString() }
                            null
                        } else if (response.isSuccessful && hop > 0) {
                            target.toString()
                        } else null
                    }
                    if (landed != null) return@withTimeout landed
                    val nextUrl = next ?: return@withTimeout null
                    // Automatic expansion is limited to the existing media sites. Ordinary web
                    // destinations are fetched only by the explicit ReadWeb action.
                    if (!IslandLinkParser.isSupported(nextUrl) && !IslandLinkParser.isShortLink(nextUrl)) {
                        return@withTimeout null
                    }
                    target = checkedHttpUrl(nextUrl)
                }
                null
            }
        }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }
}
