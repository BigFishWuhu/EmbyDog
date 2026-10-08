package com.embydog.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbyUrlTest {
    @Test
    fun `adds http scheme and trailing slash`() {
        assertEquals("http://192.168.1.20:8096/", EmbyUrl.normalize("192.168.1.20:8096").toString())
    }

    @Test
    fun `preserves reverse proxy path when resolving endpoint`() {
        val base = EmbyUrl.normalize("https://example.com/media/emby")

        assertEquals(
            "https://example.com/media/emby/System/Info/Public",
            EmbyUrl.endpoint(base, "System/Info/Public"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty server address`() {
        EmbyUrl.normalize("   ")
    }

    @Test
    fun `resolves server media path through reverse proxy`() {
        val base = EmbyUrl.normalize("https://example.com/emby")

        assertEquals(
            "https://example.com/emby/videos/1/master.m3u8?MediaSourceId=2",
            EmbyUrl.serverPath(
                base,
                "/emby/videos/1/master.m3u8?MediaSourceId=2&api_key=secret",
            ).toString(),
        )
    }

    @Test
    fun `removes token from absolute media url`() {
        val base = EmbyUrl.normalize("https://example.com/emby")

        assertEquals(
            "https://cdn.example.com/video.m3u8?quality=auto",
            EmbyUrl.serverPath(
                base,
                "https://cdn.example.com/video.m3u8?quality=auto&X-Emby-Token=secret",
            ).toString(),
        )
    }
}
