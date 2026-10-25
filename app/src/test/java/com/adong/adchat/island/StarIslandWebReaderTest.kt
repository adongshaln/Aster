package com.adong.adchat.island

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class StarIslandWebReaderTest {
    @Test fun extractsArticleWithoutScriptsOrNavigationAndMarksTextTruncation() = runBlocking {
        withServer { server, reader ->
            server.enqueue(html("""
                <html><head><title>示例标题</title></head><body><nav>导航噪音</nav>
                <article><h1>正文标题</h1><p>第一段</p><p>第二段</p><script>秘密脚本</script></article>
                <footer>页脚噪音</footer></body></html>
            """.trimIndent()))
            val page = reader.read(PUBLIC_URL)
            assertEquals("示例标题", page.title)
            assertTrue(page.text.contains("第一段\n第二段"))
            assertFalse(page.text.contains("噪音"))
            assertFalse(page.text.contains("秘密脚本"))
            assertFalse(page.truncated)
            server.enqueue(html("<article>${"字".repeat(StarIslandWebReader.MAX_TEXT_CHARS + 1)}</article>"))
            val longPage = reader.read(PUBLIC_URL)
            assertEquals(StarIslandWebReader.MAX_TEXT_CHARS, longPage.text.length)
            assertTrue(longPage.truncated)
        }
    }

    @Test fun rejectsOversizedChunkedBodiesWithoutTrustingContentLength() = runBlocking {
        withServer { server, reader ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain")
                .setChunkedBody("x".repeat(StarIslandWebReader.MAX_BODY_BYTES + 1), 8_192))
            val error = runCatching { reader.read(PUBLIC_URL) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("2 MiB"))
        }
    }

    @Test fun redirectToPrivateAddressIsRejectedBeforeTheNextRequest() = runBlocking {
        withServer { server, reader ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1/private"))
            val error = runCatching { reader.read(PUBLIC_URL) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("公开网页"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun actualDnsResultsAndNumericHostsCannotAccessPrivateNetworks() = runBlocking {
        for (address in listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1")) {
            assertFalse(address, isPublicWebAddress(InetAddress.getByName(address)))
        }
        assertTrue(isPublicWebAddress(InetAddress.getByName("8.8.8.8")))
        var lookups = 0
        val reader = StarIslandWebReader(publicWebClient(OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups++
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }).build()))
        assertTrue(runCatching { reader.read(PUBLIC_URL) }.exceptionOrNull()?.message.orEmpty().contains("公开网页"))
        assertEquals(1, lookups)
        assertTrue(runCatching { reader.read("http://2130706433/") }.exceptionOrNull()?.message.orEmpty().contains("公开网页"))
        assertEquals(1, lookups)
    }

    @Test fun loginAndNonWebResponsesHaveActionableErrors() = runBlocking {
        withServer { server, reader ->
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { reader.read(PUBLIC_URL) }.exceptionOrNull()?.message.orEmpty().contains("登录"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("pdf"))
            assertTrue(runCatching { reader.read(PUBLIC_URL) }.exceptionOrNull()?.message.orEmpty().contains("不是可阅读"))
            server.enqueue(html("<body><script>renderEverything()</script></body>"))
            assertTrue(runCatching { reader.read(PUBLIC_URL) }.exceptionOrNull()?.message.orEmpty().contains("未找到"))
        }
    }

    @Test fun cancellationClosesCallsWaitingForHeadersOrTheRestOfTheBody() = runBlocking {
        val responses = listOf(
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
            html("<article>尚未接收完整</article>").setHeader("Content-Length", 10_000)
        )
        for ((index, response) in responses.withIndex()) {
            val bodyStarted = CompletableDeferred<Unit>()
            withServer(onBodyStart = { bodyStarted.complete(Unit) }) { server, reader ->
                server.enqueue(response)
                val job = launch(Dispatchers.IO) { reader.read(PUBLIC_URL) }
                try {
                    assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                    if (index > 0) withTimeout(2_000) { bodyStarted.await() }
                    withTimeout(2_000) { job.cancelAndJoin() }
                    assertTrue(job.isCancelled)
                    assertEquals(1, server.requestCount)
                } finally { job.cancelAndJoin() }
            }
        }
    }

    private fun html(body: String) = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    private suspend fun withServer(onBodyStart: () -> Unit = {}, block: suspend (MockWebServer, StarIslandWebReader) -> Unit) {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        // The internal constructor permits a trusted test transport; production has no URL-rewriting interceptor.
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { onBodyStart() }
        }).addInterceptor { chain ->
            val request = chain.request()
            val localUrl = server.url("/").newBuilder().encodedPath(request.url.encodedPath)
                .encodedQuery(request.url.encodedQuery).build()
            chain.proceed(request.newBuilder().url(localUrl).build())
        }.build()
        try { block(server, StarIslandWebReader(client)) } finally { server.shutdown() }
    }

    companion object { private const val PUBLIC_URL = "http://reader.example/article" }
}
