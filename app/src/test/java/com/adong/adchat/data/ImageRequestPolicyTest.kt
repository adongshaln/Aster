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

        assertEquals(90L, IMAGE_READ_TIMEOUT_MINUTES)
        assertEquals(20L, IMAGE_WRITE_TIMEOUT_MINUTES)
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

        assertEquals(45L, MANGA_ANALYSIS_READ_TIMEOUT_MINUTES)
        assertEquals(10L, MANGA_ANALYSIS_WRITE_TIMEOUT_MINUTES)
        assertEquals(TimeUnit.MINUTES.toMillis(MANGA_ANALYSIS_READ_TIMEOUT_MINUTES).toInt(), client.readTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(MANGA_ANALYSIS_WRITE_TIMEOUT_MINUTES).toInt(), client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(listOf(Protocol.HTTP_1_1), client.protocols)
    }

    @Test
    fun longTaskDispatcherAllowsTwentyConcurrentImages() {
        val dispatcher = longTaskDispatcher()

        assertEquals(20, MAX_CONCURRENT_IMAGE_COUNT)
        assertEquals(20, MAX_MANGA_BATCH_IMAGES)
        assertEquals(MAX_CONCURRENT_IMAGE_COUNT, dispatcher.maxRequests)
        assertEquals(MAX_CONCURRENT_IMAGE_COUNT, dispatcher.maxRequestsPerHost)
    }

    @Test
    fun imageCapacitySupportsSplitBatchesWithoutExceedingTwenty() {
        assertEquals(true, canReserveImageCapacity(activeImages = 0, requestedImages = 20))
        assertEquals(true, canReserveImageCapacity(activeImages = 10, requestedImages = 10))
        assertEquals(true, canReserveImageCapacity(activeImages = 15, requestedImages = 5))
        assertEquals(false, canReserveImageCapacity(activeImages = 16, requestedImages = 5))
        assertEquals(false, canReserveImageCapacity(activeImages = 20, requestedImages = 1))
        assertEquals(10, remainingActiveImages(reservedImages = 10, completedImages = 0))
        assertEquals(5, remainingActiveImages(reservedImages = 10, completedImages = 5))
        assertEquals(0, remainingActiveImages(reservedImages = 10, completedImages = 10))
    }
}
