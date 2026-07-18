package com.adong.adchat.data

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol

internal const val IMAGE_CONNECT_TIMEOUT_SECONDS = 90L
internal const val IMAGE_READ_TIMEOUT_MINUTES = 90L
internal const val IMAGE_WRITE_TIMEOUT_MINUTES = 20L
internal const val MANGA_ANALYSIS_READ_TIMEOUT_MINUTES = 45L
internal const val MANGA_ANALYSIS_WRITE_TIMEOUT_MINUTES = 10L
internal const val MAX_CONCURRENT_IMAGE_COUNT = 20
internal const val MAX_MANGA_BATCH_IMAGES = 20

internal fun canReserveImageCapacity(activeImages: Int, requestedImages: Int): Boolean =
    activeImages >= 0 && requestedImages > 0 && activeImages + requestedImages <= MAX_CONCURRENT_IMAGE_COUNT

internal fun remainingActiveImages(reservedImages: Int, completedImages: Int): Int =
    (reservedImages - completedImages).coerceAtLeast(0)

internal fun longTaskDispatcher(): Dispatcher = Dispatcher().apply {
    maxRequests = MAX_CONCURRENT_IMAGE_COUNT
    maxRequestsPerHost = MAX_CONCURRENT_IMAGE_COUNT
}

internal fun OkHttpClient.Builder.applyImageRequestPolicy(): OkHttpClient.Builder = apply {
    // Image edits can spend many minutes processing after the upload has completed.
    // Do not inherit the short JSON timeout or transparently replay a paid request.
    protocols(listOf(Protocol.HTTP_1_1))
    connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    connectTimeout(IMAGE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    readTimeout(IMAGE_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    writeTimeout(IMAGE_WRITE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    callTimeout(0, TimeUnit.MILLISECONDS)
    retryOnConnectionFailure(false)
}

internal fun OkHttpClient.Builder.applyMangaAnalysisRequestPolicy(): OkHttpClient.Builder = apply {
    protocols(listOf(Protocol.HTTP_1_1))
    connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    connectTimeout(IMAGE_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    readTimeout(MANGA_ANALYSIS_READ_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    writeTimeout(MANGA_ANALYSIS_WRITE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    callTimeout(0, TimeUnit.MILLISECONDS)
    retryOnConnectionFailure(false)
}

internal fun imageDeliveryMayBeUncertain(error: Throwable): Boolean {
    val causes = generateSequence(error) { it.cause }.toList()
    if (causes.any { it is SocketTimeoutException }) return true
    val detail = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
    if (detail.contains("timeout") || detail.contains("timed out") || detail.contains("unexpected end") || detail.contains("connection reset") ||
        detail.contains("http 408") || detail.contains("http 502") || detail.contains("http 503") ||
        detail.contains("http 504") || detail.contains("http 524")
    ) {
        return true
    }
    if (causes.none { it is IOException }) return false
    return !detail.contains("unable to resolve host") &&
        !detail.contains("failed to connect") &&
        !detail.contains("connection refused")
}
