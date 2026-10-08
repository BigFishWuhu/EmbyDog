package com.embydog.app.core.media

import com.embydog.app.core.model.PlaybackSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

fun OkHttpClient.forPlayback(source: PlaybackSource, meter: PlaybackBandwidthMeter? = null): OkHttpClient {
    val server = source.serverUrl.toHttpUrl()
    return newBuilder().addNetworkInterceptor { chain ->
        val request = chain.request().newBuilder().removeHeader("X-Emby-Token")
        if (chain.request().url.hasSameOrigin(server)) {
            request.header("X-Emby-Token", source.accessToken)
        }
        val response = chain.proceed(request.build())
        val body = response.body
        if (meter == null || body == null) response else response.newBuilder().body(object : ResponseBody() {
            private val counted = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also(meter::record)
            }.buffer()
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = counted
        }).build()
    }.build()
}
