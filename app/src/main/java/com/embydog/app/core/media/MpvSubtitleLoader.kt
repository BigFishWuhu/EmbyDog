package com.embydog.app.core.media

import com.embydog.app.core.model.SubtitleTrack
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Download on an IO thread before sub-add: mpv's synchronous command must never wait on HTTP. */
internal class MpvSubtitleLoader(client: OkHttpClient, private val directory: File) : Closeable {
    private val client = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mpv-subtitle-download").apply { isDaemon = true }
    }
    private val files = mutableMapOf<Int, File>()
    private var generation = 0L
    private var closed = false
    private var activeCall: Call? = null

    @Synchronized
    fun load(index: Int, track: SubtitleTrack, onReady: (File) -> Unit, onFailure: () -> Unit) {
        if (closed) return
        cancel()
        val requestGeneration = generation
        files[index]?.let { onReady(it); return }
        val call = client.newCall(Request.Builder().url(track.uri).build())
        activeCall = call
        try {
            worker.execute {
                var downloaded: File? = null
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful)
                        val body = requireNotNull(response.body)
                        check(body.contentLength() <= MAX_BYTES)
                        check(directory.isDirectory || directory.mkdirs())
                        val suffix = when (track.mimeType) {
                            "text/x-ssa", "text/x-ass" -> ".ass"
                            "text/vtt" -> ".vtt"
                            "application/ttml+xml" -> ".ttml"
                            else -> ".srt"
                        }
                        val file = File.createTempFile("subtitle-", suffix, directory)
                        downloaded = file
                        body.byteStream().use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val size = input.read(buffer)
                                    if (size < 0) break
                                    total += size
                                    check(total <= MAX_BYTES)
                                    output.write(buffer, 0, size)
                                }
                            }
                        }
                        synchronized(this) {
                            if (closed || generation != requestGeneration) {
                                file.delete()
                            } else {
                                files[index] = file
                                activeCall = null
                                onReady(file)
                            }
                        }
                    }
                } catch (_: Exception) {
                    downloaded?.delete()
                    synchronized(this) {
                        if (!closed && generation == requestGeneration) {
                            activeCall = null
                            onFailure()
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            call.cancel()
        }
    }

    @Synchronized
    fun cancel() {
        generation++
        activeCall?.cancel()
        activeCall = null
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancel()
        worker.shutdownNow()
        files.values.forEach { it.delete() }
        files.clear()
    }

    private companion object { const val MAX_BYTES = 16L * 1024 * 1024 }
}
