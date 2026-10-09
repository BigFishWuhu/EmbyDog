package com.embydog.app.core.update

import java.io.IOException
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GithubTransportTest {
    private val url = "https://github.com/$UPDATE_REPOSITORY/releases/download/v0.2.0/SHA256SUMS.txt"
    private fun transport(server: MockWebServer) = GithubHttpTransport(OkHttpClient.Builder().followRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build()) }.build(), "EmbyDog/test")

    @Test fun downloadsManifestWithoutAccountAuthentication(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("checksum fixture"))
            assertEquals("checksum fixture", transport(server).downloadText(url))
            val request = server.takeRequest()
            assertEquals("EmbyDog/test", request.getHeader("User-Agent"))
            assertNull(request.getHeader("X-Emby-Token")); assertNull(request.getHeader("Authorization"))
        }
    }
    @Test fun followsOnlyTrustedHttpsRedirects(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://release-assets.githubusercontent.com/checksum"))
            server.enqueue(MockResponse().setBody("trusted"))
            assertEquals("trusted", transport(server).downloadText(url))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://release-assets.githubusercontent.com/checksum"))
            try { transport(server).downloadText(url); fail("HTTP downgrade must be rejected") } catch (_: IllegalArgumentException) { }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/checksum"))
            try { transport(server).downloadText(url); fail("Untrusted host must be rejected") } catch (_: IllegalArgumentException) { }
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun boundsChunkedResponsesAndCancelsSlowRequests(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("a".repeat(65537), 1024))
            try { transport(server).downloadText(url); fail("Oversized manifest must be rejected") } catch (_: IllegalArgumentException) { }
            server.enqueue(MockResponse().setHeadersDelay(5, java.util.concurrent.TimeUnit.SECONDS).setBody("late"))
            val pending = async { transport(server).downloadText(url) }
            delay(100)
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }
}
