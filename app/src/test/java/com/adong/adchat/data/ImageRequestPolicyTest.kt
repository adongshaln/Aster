package com.adong.adchat.data

import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImageRequestPolicyTest {
    @Test
    fun paidImageRequestsUseLongNonReplayingClient() {
        val client = OkHttpClient.Builder().applyImageRequestPolicy().build()

        assertEquals(TimeUnit.MINUTES.toMillis(IMAGE_READ_TIMEOUT_MINUTES).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(IMAGE_WRITE_TIMEOUT_MINUTES).toInt(), client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
    }

    @Test
    fun timeoutAndGatewayTimeoutAreTreatedAsPossiblyPaid() {
        assertEquals(true, imageDeliveryMayBeUncertain(SocketTimeoutException("timeout")))
        assertEquals(true, imageDeliveryMayBeUncertain(IllegalStateException("服务端暂时不可用（HTTP 504）")))
        assertEquals(false, imageDeliveryMayBeUncertain(java.io.IOException("unable to resolve host")))
    }

    @Test
    fun mangaAnalysisUsesLongNonReplayingMultimodalClient() {
        val client = OkHttpClient.Builder().applyMangaAnalysisRequestPolicy().build()

        assertEquals(TimeUnit.MINUTES.toMillis(MANGA_ANALYSIS_READ_TIMEOUT_MINUTES).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(MANGA_ANALYSIS_WRITE_TIMEOUT_MINUTES).toInt(), client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
    }
}
