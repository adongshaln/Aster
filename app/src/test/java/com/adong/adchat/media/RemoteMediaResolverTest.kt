package com.adong.adchat.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RemoteMediaResolverTest {
    @Test fun canceledTwitterRequestDoesNotSwitchEndpoints() = runBlocking {
        withServer { server, resolver ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { resolver.resolve(twitterInput) }
            try {
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requestCount)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun canceledBilibiliCookieFetchDoesNotContinueToViewEndpoints() = runBlocking {
        withServer { server, resolver ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { resolver.resolve(biliInput) }
            try {
                assertEquals("/x/frontend/finger/spi", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requestCount)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun canceledBilibiliViewFetchDoesNotFallBackToOtherApisOrPages() = runBlocking {
        withServer { server, resolver ->
            server.enqueue(MockResponse().setBody("{\"code\":0,\"data\":{\"b_3\":\"visitor\"}}"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { resolver.resolve(biliInput) }
            try {
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertTrue(server.takeRequest(3, TimeUnit.SECONDS)?.path.orEmpty().startsWith("/x/web-interface/view?"))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(2, server.requestCount)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun canceledBilibiliShortUrlRequestDoesNotRetry() = runBlocking {
        withServer { server, resolver ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val input = MediaInput(MediaPlatform.Bilibili, "https://b23.tv/example", "example")
            val job = launch(Dispatchers.IO) { resolver.resolve(input) }
            try {
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
                assertEquals(1, server.requestCount)
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun realTwitterEndpointFailuresStillUseTheFallback() = runBlocking {
        withServer { server, resolver ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""
                {"tweet":{"text":"sample","media":{"videos":[{"formats":[{"url":"https://cdn.example/1280x720/video.mp4","content_type":"video/mp4"}]}]}}}
            """.trimIndent()))
            val result = withTimeout(3_000) { resolver.resolve(twitterInput) }
            assertEquals("sample", result.title)
            assertEquals("https://cdn.example/1280x720/video.mp4", result.mediaUrl)
            assertEquals("720p", result.qualityLabel)
            assertEquals(2, server.requestCount)
        }
    }

    private val twitterInput = MediaInput(MediaPlatform.Twitter, "https://x.com/example/status/12345", "12345")
    private val biliInput = MediaInput(MediaPlatform.Bilibili, "https://www.bilibili.com/video/BV1xx411c7mD", "BV1xx411c7mD")

    private suspend fun withServer(block: suspend (MockWebServer, RemoteMediaResolver) -> Unit) {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val localUrl = server.url("/").newBuilder()
                    .encodedPath(original.url.encodedPath)
                    .encodedQuery(original.url.encodedQuery)
                    .build()
                chain.proceed(original.newBuilder().url(localUrl).build())
            }.build()
        try { block(server, RemoteMediaResolver(client)) } finally { server.shutdown() }
    }
}
