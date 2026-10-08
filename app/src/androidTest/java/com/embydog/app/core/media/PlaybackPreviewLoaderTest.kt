package com.embydog.app.core.media

import android.graphics.Bitmap
import android.graphics.Color
import com.embydog.app.core.model.PlaybackSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class PlaybackPreviewLoaderTest {
    @Test fun rangePreviewLoadsOnlyHeaderIndexAndRequestedImageAndCachesFrames() {
        MockWebServer().use { server ->
            val archive = archive()
            server.dispatcher = rangeDispatcher(archive)
            PlaybackPreviewLoader(source(server), OkHttpClient()).use { loader ->
                val first = preview(loader, 15_000)
                assertNotNull(first.bitmap); assertEquals(10_000L, first.frameTimeMs)
                assertTrue(Color.blue(first.bitmap!!.getPixel(8, 8)) > 200)
                assertEquals(3, server.requestCount)
                repeat(3) { assertEquals("test-token", server.takeRequest().getHeader("X-Emby-Token")) }
                assertNotNull(preview(loader, 17_000).bitmap)
                assertEquals(3, server.requestCount)
            }
        }
    }
    @Test fun missingServerPreviewFallsBackWithoutRepeatedRequests() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            PlaybackPreviewLoader(source(server), OkHttpClient()).use { loader ->
                assertNull(preview(loader, 1000).bitmap)
                assertNotNull(preview(loader, 2000).message)
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun serverIgnoringRangeDownloadsOnlyOneBoundedArchive() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(archive())))
            PlaybackPreviewLoader(source(server), OkHttpClient()).use { loader ->
                assertNotNull(preview(loader, 1000).bitmap)
                assertNotNull(preview(loader, 15_000).bitmap)
                assertEquals(1, server.requestCount)
            }
        }
    }

    private fun source(server: MockWebServer) = PlaybackSource("fixture", "fixture", server.url("/video").toString(),
        "test-token", 0, emptyList(), server.url("/").toString(), previewUrl = server.url("/Videos/fixture/index.bif").toString())
    private fun preview(loader: PlaybackPreviewLoader, position: Long): PlaybackPreview {
        val ready = CountDownLatch(1); var result: PlaybackPreview? = null
        loader.request(position) { result = it; ready.countDown() }
        assertTrue(ready.await(6, TimeUnit.SECONDS))
        return requireNotNull(result)
    }
    private fun rangeDispatcher(bytes: ByteArray) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")!!.removePrefix("bytes=").split('-').map(String::toInt)
            return MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes ${range[0]}-${range[1]}/${bytes.size}")
                .setBody(Buffer().write(bytes, range[0], range[1] - range[0] + 1))
        }
    }
    private fun archive(): ByteArray {
        fun jpeg(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this); bitmap.recycle() }.toByteArray()
        }
        val first = jpeg(Color.RED); val second = jpeg(Color.BLUE)
        val offset = 64 + 24
        val bytes = ByteBuffer.allocate(offset + first.size + second.size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a))
        bytes.putInt(0); bytes.putInt(2); bytes.putInt(1000)
        bytes.position(64)
        bytes.putInt(0); bytes.putInt(offset); bytes.putInt(10); bytes.putInt(offset + first.size)
        bytes.putInt(-1); bytes.putInt(offset + first.size + second.size)
        bytes.put(first); bytes.put(second)
        return bytes.array()
    }
}
