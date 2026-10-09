package com.embydog.app.core.offline

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val none = DownloadCheckpoint(0, -1, null)

    @Test fun downloadsOriginalFileWithTokenHeaderAndKnownLength(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("ETag", "\"v1\"").setBody("original-file"))
            val file = temporary.newFile()
            val result = OfflineTransport(OkHttpClient()).download("a", server.url("/Items/a/Download").toString(), "fixture-secret", file, none)
            assertEquals("original-file", file.readText()); assertEquals(file.length(), result.total)
            assertEquals("\"v1\"", result.validator)
            val request = server.takeRequest()
            assertEquals("fixture-secret", request.getHeader("X-Emby-Token")); assertFalse(request.path!!.contains("fixture-secret"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
        }
    }

    @Test fun resumesOnlyAtValidatedContentRange(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6").setHeader("ETag", "\"v1\"").setBody("def"))
            val file = temporary.newFile().apply { writeText("abc") }
            OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", file, DownloadCheckpoint(3, 6, "\"v1\""))
            assertEquals("abcdef", file.readText())
            val request = server.takeRequest(); assertEquals("bytes=3-", request.getHeader("Range")); assertEquals("\"v1\"", request.getHeader("If-Range"))
        }
    }

    @Test fun ignoredRangeReplacesAndNeverAppendsOldFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("ETag", "\"new\"").setBody("new-video"))
            val file = temporary.newFile().apply { writeText("old") }
            OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", file, DownloadCheckpoint(3, 6, "\"old\""), checkpoint = {
                if (it.bytes == 0L) assertEquals("Old bytes must be gone before committing a new validator", 0, file.length())
            })
            assertEquals("new-video", file.readText())
        }
    }

    @Test fun missingValidatorRestartsFromZero(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete"))
            val file = temporary.newFile().apply { writeText("partial") }
            OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", file, DownloadCheckpoint(7, 8, null))
            assertNull(server.takeRequest().getHeader("Range")); assertEquals("complete", file.readText())
        }
    }

    @Test fun incorrectRangeCannotCorruptExistingPartialFile(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/6").setBody("cdef"))
            val file = temporary.newFile().apply { writeText("abc") }
            assertFailure { OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", file, DownloadCheckpoint(3, 6, "\"v1\"")) }
            assertEquals("abc", file.readText())
        }
    }

    @Test fun range416IsNotMistakenForACompletedDownload(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416)); server.enqueue(MockResponse().setBody("fresh"))
            val file = temporary.newFile().apply { writeText("stale") }
            OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", file, DownloadCheckpoint(5, 5, "\"v1\""))
            assertEquals("fresh", file.readText()); assertEquals("bytes=5-", server.takeRequest().getHeader("Range")); assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun truncatedBodyFailsAndKeepsPartialBytes(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("a".repeat(100_000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val file = temporary.newFile()
            assertFailure { OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", file, none) }
            assertTrue(file.length() in 1..99_999)
        }
    }

    @Test fun crossOriginRedirectNeverReceivesAccountToken(): Unit = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { external ->
            external.enqueue(MockResponse().setBody("unexpected"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", external.url("/steal")))
            assertFailure { OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", temporary.newFile(), none) }
            assertEquals(0, external.requestCount)
        } }
    }

    @Test fun externalSubtitleOriginIsRejectedBeforeAnyRequest(): Unit = runBlocking {
        MockWebServer().use { external ->
            assertFailure { OfflineTransport(OkHttpClient()).download("a", external.url("/").toString(), "token", temporary.newFile(), none,
                authOrigin = "https://server.example/") }
            assertEquals(0, external.requestCount)
        }
    }

    @Test fun cancellationStopsBlockedNetworkRead(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("video").setHeadersDelay(5, TimeUnit.SECONDS))
            val transport = OfflineTransport(OkHttpClient())
            val job = launch { assertFailure { transport.download("a", server.url("/").toString(), "token", temporary.newFile(), none) } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            transport.cancel("a")
            withTimeout(2_000) { job.join() }
        }
    }

    @Test fun hlsResponsesAreNotStoredAsOriginalVideos(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U"))
            assertFailure { OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", temporary.newFile(), none) }
        }
    }

    @Test fun oversizedSubtitleIsRejected(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("12345"))
            assertFailure { OfflineTransport(OkHttpClient()).download("a", server.url("/").toString(), "token", temporary.newFile(), none, maxBytes = 4) }
        }
    }

    @Test fun contentRangeParserRejectsMalformedAndOverflowRanges() {
        listOf("bytes 5-3/6", "bytes 0-6/6", "bytes 0-2/*", "bytes 0-999999999999999999999/3").forEach {
            assertTrue(runCatching { parseContentRange(it) }.isFailure)
        }
        assertEquals(ContentRange(3, 5, 6), parseContentRange("bytes 3-5/6"))
        assertEquals("mkv", safeContainer("../../outside"))
    }

    private suspend fun assertFailure(block: suspend () -> Unit) { assertTrue("Expected failure", runCatching { block() }.isFailure) }
}
