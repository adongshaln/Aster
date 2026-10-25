package com.adong.adchat.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ConnectException
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ModelRequestTransportTest {
    private val workingAddress = InetAddress.getByName("127.0.0.1")
    // DNS retains the real IPv6 → IPv4 route ordering; socket setup below deterministically
    // refuses the first route without depending on OS aliases, firewalls, or transparent proxies.
    private val refusedAddress = InetAddress.getByName("::1")

    @Test fun modelPostFallsBackToTheNextAddressBeforeSending() = runBlocking {
        val server = MockWebServer().apply { start(workingAddress, 0) }
        try {
            server.enqueue(success())
            val addresses = CopyOnWriteArrayList<InetAddress>()
            val repository = ApiRepository(client = client(listOf(refusedAddress, workingAddress), addresses))
            val profile = profile(server)
            val result = withTimeout(5_000) {
                repository.streamChat(profile, profile.chatModel, "", history, "route-test", skillsAllowed = false) {}
            }
            assertEquals("answer", result.text)
            assertEquals(listOf(refusedAddress, workingAddress), addresses.toList())
            assertEquals(1, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
        } finally { server.shutdown() }
    }

    @Test fun modelListGetAlsoRetainsAddressFallback() = runBlocking {
        val server = MockWebServer().apply { start(workingAddress, 0) }
        try {
            server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"test-model\"}]}"))
            val addresses = CopyOnWriteArrayList<InetAddress>()
            val repository = ApiRepository(client = client(listOf(refusedAddress, workingAddress), addresses))
            val result = withTimeout(5_000) { repository.fetchModels(profile(server)) }
            assertEquals("test-model", result.models.single().id)
            assertEquals(listOf(refusedAddress, workingAddress), addresses.toList())
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        } finally { server.shutdown() }
    }

    @Test fun acceptedPostIsNotReplayedWhenTheServerDisconnectsBeforeHeaders() = runBlocking {
        val server = MockWebServer().apply { start(workingAddress, 0) }
        try {
            // This policy receives the full request, then closes the connection without a response.
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(success())
            val addresses = CopyOnWriteArrayList<InetAddress>()
            val repository = ApiRepository(client = client(listOf(workingAddress, workingAddress), addresses))
            val profile = profile(server)
            val failure = runCatching {
                withTimeout(5_000) {
                    repository.streamChat(profile, profile.chatModel, "", history, "route-test", skillsAllowed = false) {}
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(1, server.requestCount)
            assertEquals(listOf(workingAddress), addresses.toList())
            val accepted = server.takeRequest()
            assertEquals("POST", accepted.method)
            assertTrue(accepted.body.readUtf8().contains("hello"))
        } finally { server.shutdown() }
    }

    private val history = listOf(ChatMessage(role = "user", content = "hello"))

    private fun profile(server: MockWebServer) = ApiProfile(
        baseUrl = server.url("/").newBuilder().host("model.test").build().toString(),
        apiKey = "test", chatModel = "test-model"
    )

    private fun client(addresses: List<InetAddress>, connects: MutableList<InetAddress>) = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .socketFactory(object : SocketFactory() {
            private val delegate = getDefault()
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    if ((endpoint as? InetSocketAddress)?.address == refusedAddress) {
                        throw ConnectException("fixture: IPv6 route refused before sending")
                    }
                    super.connect(endpoint, timeout)
                }
            }
            override fun createSocket(host: String, port: Int) = delegate.createSocket(host, port)
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
                delegate.createSocket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress, port: Int) = delegate.createSocket(host, port)
            override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int) =
                delegate.createSocket(host, port, localHost, localPort)
        })
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = addresses
        })
        .eventListener(object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                connects += inetSocketAddress.address
            }
        })
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun success() = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
}
