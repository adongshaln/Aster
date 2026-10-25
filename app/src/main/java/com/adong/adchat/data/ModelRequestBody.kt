package com.adong.adchat.data

import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Allow OkHttp to try another address before sending, but never replay a model POST once sending
 * started. Global retryOnConnectionFailure(false) also disables safe IPv4/IPv6 route fallback.
 */
internal fun Request.Builder.postModelRequest(body: RequestBody): Request.Builder = post(
    object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isDuplex() = body.isDuplex()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }
)
