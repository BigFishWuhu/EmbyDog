package com.embydog.app.core.media

import com.embydog.app.core.model.SubtitleTrack
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MpvSubtitleLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun track(url: String) = SubtitleTrack(url, "text/x-ssa", "zh", "中文字幕", true, false)

    @Test fun loadingIsAsynchronousAndDownloadedFilesAreCleanedUp() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[Script Info]\nTitle: test").setHeadersDelay(250, TimeUnit.MILLISECONDS))
            val ready = CountDownLatch(1)
            var downloaded: File? = null
            val loader = MpvSubtitleLoader(OkHttpClient(), temporary.newFolder())
            loader.use {
                loader.load(0, track(server.url("/subtitle.ass").toString()), {
                    downloaded = it
                    ready.countDown()
                }, { fail("Subtitle should load") })
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("Download must not block the calling thread", 1L, ready.count)
                assertTrue(ready.await(3, TimeUnit.SECONDS))
                assertTrue(downloaded!!.readText().contains("Script Info"))
                val cached = CountDownLatch(1)
                loader.load(0, track(server.url("/subtitle.ass").toString()), { cached.countDown() }, { fail() })
                assertTrue(cached.await(1, TimeUnit.SECONDS))
                assertEquals(1, server.requestCount)
            }
            assertFalse(downloaded!!.exists())
        }
    }

    @Test fun httpFailureIsReportedWithoutCreatingAFile() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502))
            val failure = CountDownLatch(1)
            val folder = temporary.newFolder()
            MpvSubtitleLoader(OkHttpClient(), folder).use { loader ->
                loader.load(0, track(server.url("/subtitle.ass").toString()), { fail() }, { failure.countDown() })
                assertTrue(failure.await(3, TimeUnit.SECONDS))
                assertTrue(folder.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test fun disablingSubtitlesCancelsPendingDownloadWithoutReportingAnError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("subtitle").setBodyDelay(2, TimeUnit.SECONDS))
            val callback = CountDownLatch(1)
            MpvSubtitleLoader(OkHttpClient(), temporary.newFolder()).use { loader ->
                loader.load(0, track(server.url("/subtitle.ass").toString()), { callback.countDown() }, { callback.countDown() })
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                loader.cancel()
                assertFalse(callback.await(300, TimeUnit.MILLISECONDS))
            }
        }
    }
}
