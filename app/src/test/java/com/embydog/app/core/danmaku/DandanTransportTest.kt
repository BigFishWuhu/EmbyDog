package com.embydog.app.core.danmaku

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DandanTransportTest {
    private val credentials = DanmakuCredentials("test-app", "secret")
    private fun client() = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(3, TimeUnit.SECONDS).build()

    @Test fun signsOnlyPathAndNeverSendsEmbyOrAppSecretHeaders(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val transport = DandanTransport(client(), server.url("/"), nowSeconds = { 1735660800 })
            val url = transport.apiUrl("comment/42").newBuilder().addQueryParameter("withRelated", "true").build()
            assertEquals("{}", transport.get(url, credentials, 100))
            val request = server.takeRequest()
            assertEquals("a51XLR9H5lTOAAIvCCffnN3Qx7Qu1aP5YFUiqTal9Tk=", request.getHeader("X-Signature"))
            assertEquals("test-app", request.getHeader("X-AppId")); assertEquals("1735660800", request.getHeader("X-Timestamp"))
            listOf("Authorization", "X-Emby-Token", "X-AppSecret", "Cookie").forEach { assertNull(request.getHeader(it)) }
        }
    }
    @Test fun redirectDropsAllAuthentication(): Unit = runBlocking {
        MockWebServer().use { api -> MockWebServer().use { cdn ->
            api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/comments.json")))
            cdn.enqueue(MockResponse().setBody("{\"comments\":[]}"))
            val transport = DandanTransport(client(), api.url("/"), allowedRedirect = { it == cdn.url("/comments.json") })
            transport.get(transport.apiUrl("comment/42"), credentials, 100)
            val redirected = cdn.takeRequest()
            listOf("X-AppId", "X-Signature", "X-Timestamp", "X-AppSecret", "Authorization").forEach { assertNull(redirected.getHeader(it)) }
        } }
    }
    @Test fun rejectsUnknownRedirectAndOversizeResponses(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/"))
            server.enqueue(MockResponse().setBody("x".repeat(101)))
            val transport = DandanTransport(client(), server.url("/"))
            assertTrue(runCatching { transport.get(transport.apiUrl("comment/42"), credentials, 100) }.isFailure)
            assertTrue(runCatching { transport.get(transport.apiUrl("comment/42"), credentials, 100) }.isFailure)
        }
    }
    @Test fun missingCredentialsDoNotMakeNetworkRequests(): Unit = runBlocking {
        MockWebServer().use { server ->
            val transport = DandanTransport(client(), server.url("/"))
            assertTrue(runCatching { transport.get(transport.apiUrl("comment/42"), DanmakuCredentials(), 100) }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun redirectHostChecksAreExactAndHttpsOnly() {
        assertTrue(isDandanDownload("https://cas.dandanplay.net/data".toHttpUrl()))
        listOf("http://cas.dandanplay.net/data", "https://dandanplay.net.evil.example/data", "https://evil-dandanplay.net/data", "https://user:pass@cas.dandanplay.net/data", "https://cas.dandanplay.net:444/data")
            .forEach { assertFalse(it, isDandanDownload(it.toHttpUrl())) }
    }
}
