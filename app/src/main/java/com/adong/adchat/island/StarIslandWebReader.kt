package com.adong.adchat.island

import com.adong.adchat.data.withCancellableResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

data class StarIslandWebPage(val title: String, val url: String, val text: String, val truncated: Boolean)

/** Fetches only after an explicit reading action; no scripts, subresources or browser credentials. */
class StarIslandWebReader internal constructor(client: OkHttpClient) {
    constructor() : this(publicWebClient(OkHttpClient()))

    // The public constructor owns the network policy. Internal callers may supply a trusted
    // transport (for example a local test server), while redirect handling stays in this reader.
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun read(url: String): StarIslandWebPage = try {
        withContext(Dispatchers.IO) {
            withTimeout(TOTAL_TIMEOUT_MS) {
                var target = checkedHttpUrl(url)
                repeat(MAX_REDIRECTS + 1) { hop ->
                    currentCoroutineContext().ensureActive()
                    requirePublicWebTarget(target)
                    val request = Request.Builder().url(target)
                        .header("User-Agent", "Aster StarIsland/1.0")
                        .header("Accept", "text/html, application/xhtml+xml, text/plain;q=0.9")
                        .build()
                    var redirected: HttpUrl? = null
                    val page = client.newCall(request).withCancellableResponse { response ->
                        if (response.code in REDIRECT_CODES) {
                            if (hop == MAX_REDIRECTS) throw StarIslandWebException("网页跳转次数过多，请打开原页查看")
                            val location = response.header("Location")
                                ?: throw StarIslandWebException("网页跳转地址缺失，请打开原页查看")
                            redirected = target.resolve(location)
                                ?: throw StarIslandWebException("网页跳转地址无效")
                            checkedHttpUrl(redirected.toString())
                            null
                        } else {
                            readPage(response)
                        }
                    }
                    if (page != null) return@withTimeout page
                    target = checkNotNull(redirected)
                }
                error("unreachable")
            }
        }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw StarIslandWebException("网页读取超时，请稍后重试")
    } catch (error: CancellationException) {
        throw error
    } catch (error: StarIslandWebException) {
        throw error
    } catch (error: SocketTimeoutException) {
        throw StarIslandWebException("网页读取超时，请稍后重试")
    } catch (error: IOException) {
        currentCoroutineContext().ensureActive()
        throw StarIslandWebException("无法读取网页，请检查网络连接或打开原页查看")
    }

    private suspend fun readPage(response: Response): StarIslandWebPage {
        if (response.code == 401 || response.code == 403) {
            throw StarIslandWebException("网页需要登录或访问验证，请打开原页查看")
        }
        if (!response.isSuccessful) throw StarIslandWebException("网页返回 HTTP ${response.code}，请打开原页查看")
        val body = response.body ?: throw StarIslandWebException("网页没有返回正文")
        val type = body.contentType()
        val mime = type?.let { "${it.type}/${it.subtype}" }
        if (mime !in setOf("text/html", "application/xhtml+xml", "text/plain")) {
            throw StarIslandWebException("这个链接不是可阅读的网页或文本，请使用打开原页或媒体下载")
        }
        if (body.contentLength() > MAX_BODY_BYTES) throw StarIslandWebException("网页超过 2 MiB，暂时无法读取，请打开原页查看")
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        val source = body.byteStream()
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = source.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES + 1 - bytes.size()))
            if (count == -1) break
            bytes.write(buffer, 0, count)
            if (bytes.size() > MAX_BODY_BYTES) throw StarIslandWebException("网页超过 2 MiB，暂时无法读取，请打开原页查看")
        }
        currentCoroutineContext().ensureActive()
        val finalUrl = response.request.url.toString()
        val title: String
        val text: String
        if (mime == "text/plain") {
            title = response.request.url.host
            text = normalizeText(bytes.toString(type?.charset(Charsets.UTF_8)?.name() ?: "UTF-8"))
        } else {
            val document = Jsoup.parse(ByteArrayInputStream(bytes.toByteArray()), type?.charset()?.name(), finalUrl)
            title = document.title().trim().ifBlank { document.selectFirst("h1")?.text().orEmpty() }
                .ifBlank { response.request.url.host }.take(200)
            val hasLoginForm = document.selectFirst("input[type=password]") != null
            document.select("script,style,noscript,iframe,nav,header,footer,aside,form,button,input,textarea,select,canvas,svg,template,[hidden],[aria-hidden=true]").remove()
            val main = document.select("article,main,[role=main]").maxByOrNull { it.text().length }
            val root = main ?: document.body()
            root.select("p,div,section,li,blockquote,pre,h1,h2,h3,h4,h5,h6,br,tr")
                .forEach { it.after(TextNode("\n")) }
            text = normalizeText(root.wholeText())
            if (hasLoginForm && text.length < 200) {
                throw StarIslandWebException("网页需要登录，请打开原页查看")
            }
        }
        if (text.isBlank()) throw StarIslandWebException("未找到可阅读正文，网页可能需要登录或运行脚本，请打开原页查看")
        currentCoroutineContext().ensureActive()
        val excerpt = text.take(MAX_TEXT_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        return StarIslandWebPage(title, finalUrl, excerpt, text.length > excerpt.length)
    }

    private fun normalizeText(value: String): String = value.lineSequence()
        .map { it.replace(HORIZONTAL_SPACE, " ").trim() }
        .filter(String::isNotEmpty).joinToString("\n")

    companion object {
        internal const val MAX_BODY_BYTES = 2 * 1024 * 1024
        internal const val MAX_TEXT_CHARS = 16_000
        private const val TOTAL_TIMEOUT_MS = 30_000L
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val HORIZONTAL_SPACE = Regex("[\\t\\u000B\\f\\r ]+")
    }
}

internal class StarIslandWebException(message: String) : IOException(message)

/** Each actual DNS result is checked; redirects never inherit an unchecked destination. */
internal fun publicWebClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .proxy(Proxy.NO_PROXY)
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .cache(null)
    .followRedirects(false)
    .followSslRedirects(false)
    .dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = base.dns.lookup(hostname).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !isPublicWebAddress(it) }) {
                throw StarIslandWebException("星岛只读取公开网页，不访问本机或局域网地址")
            }
        }
    })
    .build()

internal fun requirePublicWebTarget(url: HttpUrl) {
    val host = url.host
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
        throw StarIslandWebException("星岛只读取公开网页，不访问本机或局域网地址")
    }
    // Reject numeric local addresses before opening a call, in addition to checking DNS results.
    if (':' in host || host.all { it.isDigit() || it == '.' }) {
        if (!isPublicWebAddress(InetAddress.getByName(host))) {
            throw StarIslandWebException("星岛只读取公开网页，不访问本机或局域网地址")
        }
    }
}

internal fun isPublicWebAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 0xff }
    if (bytes.size == 16) {
        // Only global unicast IPv6. Excludes unique-local, mapped and translation address space.
        return (bytes[0] and 0xe0) == 0x20 && !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
    }
    return bytes[0] != 0 && bytes[0] < 224 &&
        !(bytes[0] == 100 && bytes[1] in 64..127) &&
        !(bytes[0] == 198 && bytes[1] in 18..19) &&
        !(bytes[0] == 192 && bytes[1] == 0 && bytes[2] in setOf(0, 2)) &&
        !(bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100) &&
        !(bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113)
}
