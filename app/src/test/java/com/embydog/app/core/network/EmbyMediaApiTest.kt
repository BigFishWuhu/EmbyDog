package com.embydog.app.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class EmbyMediaApiTest {
    @Test fun playbackReportsUseAuthenticatedSessionEndpointsAndPositionTicks() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
            val client = api(server)
            listOf("Sessions/Playing", "Sessions/Playing/Progress", "Sessions/Playing/Stopped").forEach { path ->
                client.reportPlayback(server.url("/proxy/emby/$path").toString(), "token", "MediaBrowser DeviceId=\"test\"",
                    PlaybackReportDto("episode", "source", "play-session", 123000000, isPaused = true))
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/proxy/emby/$path", request.path)
                assertEquals("token", request.getHeader("X-Emby-Token"))
                assertTrue(request.getHeader("X-Emby-Authorization")!!.contains("test"))
                assertTrue(request.body.readUtf8().contains("\"PositionTicks\":123000000"))
            }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private fun api(server: MockWebServer): EmbyApi = Retrofit.Builder().baseUrl(server.url("/"))
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(EmbyApi::class.java)

    @Test fun favoriteChangesUsePostAndDeleteWithoutDiscardingProxyPrefix() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
            val url = EmbyUrl.endpoint(server.url("/proxy/emby/"), "Users/user/FavoriteItems/movie")
            api.addFavorite(url, "test-token")
            api.removeFavorite(url, "test-token")
            val add = server.takeRequest(); val remove = server.takeRequest()
            assertEquals("POST", add.method); assertEquals("DELETE", remove.method)
            assertEquals("/proxy/emby/Users/user/FavoriteItems/movie", add.path)
            assertEquals(add.path, remove.path)
            assertEquals("test-token", add.getHeader("X-Emby-Token"))
        }
    }

    @Test fun searchAndEpisodeMetadataPreserveFavoritesAndHierarchy() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"episode","Name":"第一集","Type":"Episode","SeriesId":"show","SeasonId":"season","ParentIndexNumber":1,"IndexNumber":2,"UserData":{"IsFavorite":true}}],"TotalRecordCount":41}"""))
            val result = api(server).getItems(server.url("/Users/user/Items?SearchTerm=%E4%B8%AD%E6%96%87&StartIndex=40&Limit=40").toString(), "token")
            assertEquals(41, result.totalRecordCount)
            assertEquals("show", result.items.single().seriesId)
            assertEquals("season", result.items.single().seasonId)
            assertTrue(result.items.single().userData!!.isFavorite)
        }
    }

    @Test fun subtitleMetadataDoesNotDiscardTitlesOrHearingImpairedFlags() {
        val stream = json.decodeFromString<MediaStreamDto>(
            """{"Type":"Subtitle","Index":5,"Language":"chi","Title":"繁英双语 字幕组","DisplayTitle":"Chinese","Path":"/media/movie.cht.ass","Codec":"ass","IsHearingImpaired":true}""")
        assertEquals("繁英双语 字幕组", stream.title)
        assertTrue(stream.isHearingImpaired)
        assertEquals("/media/movie.cht.ass", stream.path)
    }
}
