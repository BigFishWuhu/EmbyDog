package com.embydog.app.core.media

import com.embydog.app.core.model.PlaybackSource
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MpvHttpGatewayTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var gateway: MpvHttpGateway? = null

    @Before
    fun setup() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    }

    @After
    fun cleanup() {
        gateway?.close()
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    private fun source() = PlaybackSource(
        itemId = "item", title = "Test", uri = server.url("/video.mkv").toString(),
        accessToken = "private-token", startPositionMs = 0, subtitles = emptyList(),
        serverUrl = server.url("/emby/").toString(),
    )

    private fun openGateway() = MpvHttpGateway(source(), client).also { gateway = it }

    @Test
    fun `streams bytes and forwards range without exposing token`() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("abc")
            .setHeader("Content-Range", "bytes 5-7/20"))
        val url = openGateway().register(source().uri)
        assertFalse(url.contains("private-token"))
        client.newCall(Request.Builder().url(url).header("Range", "bytes=5-7").build()).execute().use {
            assertEquals(206, it.code)
            assertEquals("bytes 5-7/20", it.header("Content-Range"))
            assertEquals("abc", it.body!!.string())
        }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("bytes=5-7", request.getHeader("Range"))
        assertEquals("private-token", request.getHeader("X-Emby-Token"))
    }

    @Test
    fun `redirect to another origin strips emby authentication`() {
        MockWebServer().use { cdn ->
            cdn.start()
            cdn.enqueue(MockResponse().setBody("video"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/movie")))
            val url = openGateway().register(source().uri)
            client.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals("video", it.body!!.string())
            }
            assertEquals("private-token", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-Emby-Token"))
            assertNull(cdn.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-Emby-Token"))
        }
    }

    @Test
    fun `rewrites hls segments and key urls through local authenticated routes`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
            .setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:3,\nsegment.ts\n"))
        val url = openGateway().register(server.url("/hls/master.m3u8").toString())
        val playlist = client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        assertTrue(playlist.startsWith("#EXTM3U"))
        assertFalse(playlist.contains(server.url("/").toString()))
        val segmentUrl = playlist.lineSequence().first { it.startsWith("http://127.0.0.1:") }
        val keyUrl = Regex("URI=\"([^\"]+)\"").find(playlist)!!.groupValues[1]
        assertTrue(keyUrl.startsWith("http://127.0.0.1:"))
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse().setBody("segment"))
        client.newCall(Request.Builder().url(segmentUrl).build()).execute().use {
            assertEquals("segment", it.body!!.string())
        }
        assertEquals("/hls/segment.ts", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `head request is relayed without downloading media body`() {
        server.enqueue(MockResponse().setHeader("Content-Length", 1234))
        val url = openGateway().register(source().uri)
        client.newCall(Request.Builder().url(url).head().build()).execute().use {
            assertEquals("1234", it.header("Content-Length"))
            assertEquals("", it.body!!.string())
        }
        assertEquals("HEAD", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun `unknown local route cannot select arbitrary upstream url`() {
        val url = openGateway().register(source().uri).substringBeforeLast('/') + "/unknown"
        client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(404, it.code) }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `server error is preserved and classified for playback recovery`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val proxy = openGateway()
        val url = proxy.register(source().uri)
        client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(503, it.code) }
        assertNotNull(proxy.lastFailure)
        assertEquals(PlaybackFailureKind.NETWORK, proxy.lastFailure)
    }
}
