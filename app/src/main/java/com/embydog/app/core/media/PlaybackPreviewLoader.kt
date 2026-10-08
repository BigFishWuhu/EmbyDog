package com.embydog.app.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.embydog.app.core.model.PlaybackSource
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class PlaybackPreview(val bitmap: Bitmap?, val frameTimeMs: Long? = null, val message: String? = null)

/** Fetch only BIF header, index and selected JPEG. Never seek the main player to generate previews. */
internal class PlaybackPreviewLoader(private val source: PlaybackSource, client: OkHttpClient) : Closeable {
    private val client = client.forPlayback(source).newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "playback-preview").apply { isDaemon = true } }
    private var generation = 0L
    private var closed = false
    private var activeCall: Call? = null
    private var index: BifIndex? = null
    private var archive: ByteArray? = null
    private var unavailable = source.previewUrl == null
    private var backoffUntil = 0L
    private val frames = object : LinkedHashMap<Long, PlaybackPreview>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PlaybackPreview>) = size > 8
    }

    @Synchronized fun request(positionMs: Long, onReady: (PlaybackPreview) -> Unit) {
        if (closed) return
        cancel()
        val requestGeneration = generation
        try {
            worker.execute {
                if (!isCurrent(requestGeneration)) return@execute
                val result = try {
                    if (unavailable || System.nanoTime() < backoffUntil) PlaybackPreview(null, message = "服务器暂无画面预览")
                    else {
                        val bif = index ?: run {
                            val header = range(0, BifParser.HEADER_SIZE, requestGeneration)
                            val count = BifParser.imageCount(header)
                            val table = range(BifParser.HEADER_SIZE.toLong(), (count + 1) * 8, requestGeneration)
                            BifParser.parse(header, table).also { index = it }
                        }
                        val frame = bif.frameAt(positionMs)
                        frames[frame.offset] ?: run {
                            val bytes = range(frame.offset, frame.length, requestGeneration)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = (bounds.outWidth / 320).coerceAtLeast(1)
                            }
                            PlaybackPreview(requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)), frame.timeMs)
                                .also { frames[frame.offset] = it }
                        }
                    }
                } catch (_: Exception) {
                    if (isCurrent(requestGeneration)) backoffUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    PlaybackPreview(null, message = "画面预览暂不可用，仍可按时间定位")
                }
                synchronized(this) { if (!closed && generation == requestGeneration) onReady(result) }
            }
        } catch (_: RejectedExecutionException) { Unit }
    }

    @Synchronized private fun isCurrent(value: Long) = !closed && generation == value

    private fun range(offset: Long, length: Int, requestGeneration: Long): ByteArray {
        archive?.let { bytes ->
            require(offset + length <= bytes.size)
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        val call = client.newCall(Request.Builder().url(requireNotNull(source.previewUrl))
            .header("Range", "bytes=$offset-${offset + length - 1}").header("Accept-Encoding", "identity").build())
        synchronized(this) {
            check(!closed && generation == requestGeneration)
            activeCall = call
        }
        return try {
            call.execute().use { response ->
                if (response.code in setOf(404, 405, 501)) unavailable = true
                check(response.isSuccessful)
                val body = requireNotNull(response.body)
                if (response.code == 206) {
                    check(response.header("Content-Range")?.startsWith("bytes $offset-") == true)
                    val bytes = body.byteStream().use { it.readBytesBounded(length) }
                    check(bytes.size == length)
                    bytes
                } else {
                    // A server ignoring Range may serve one small archive; never repeatedly download a large BIF.
                    check(response.code == 200 && body.contentLength() <= MAX_ARCHIVE_BYTES)
                    val bytes = body.byteStream().use { it.readBytesBounded(MAX_ARCHIVE_BYTES) }
                    archive = bytes
                    check(offset + length <= bytes.size)
                    bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
                }
            }
        } finally { synchronized(this) { if (activeCall === call) activeCall = null } }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            check(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    @Synchronized fun cancel() { generation++; activeCall?.cancel(); activeCall = null }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        cancel()
        worker.shutdownNow()
        // Cache is confined to the worker; enqueueing is closed, remaining work releases it naturally.
    }
    private companion object { const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024 }
}
