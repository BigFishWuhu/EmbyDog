package com.embydog.app.core.media

import com.embydog.app.core.model.PlaybackSource
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class MpvHttpGateway(source: PlaybackSource, client: OkHttpClient, meter: PlaybackBandwidthMeter? = null) : Closeable {
    private val client = client.forPlayback(source, meter)
    private val server = ServerSocket().apply {
        bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
    }
    private data class Route(val url: HttpUrl, val isMedia: Boolean)
    private val routes = ConcurrentHashMap<String, Route>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val acceptor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-http-accept").apply { isDaemon = true }
    }
    private val workers = ThreadPoolExecutor(
        4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "mpv-http-stream").apply { isDaemon = true } },
    )
    @Volatile private var closed = false
    @Volatile var lastFailure: PlaybackFailureKind? = null
        private set

    init {
        acceptor.execute {
            while (!closed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                sockets.add(socket)
                try {
                    workers.execute {
                        try { serve(socket) } finally { sockets.remove(socket) }
                    }
                } catch (_: RejectedExecutionException) {
                    sockets.remove(socket)
                    socket.close()
                }
            }
        }
    }

    @Synchronized
    fun register(value: String, isMedia: Boolean = true): String {
        check(!closed)
        val remote = value.toHttpUrl()
        val entry = Route(remote, isMedia)
        val existing = routes.entries.firstOrNull { it.value == entry }?.key
        val extension = remote.encodedPath.substringAfterLast('.').takeIf {
            it.length in 1..6 && it.all(Char::isLetterOrDigit)
        }?.let { ".$it" }.orEmpty()
        val route = existing ?: "/${UUID.randomUUID()}$extension".also {
            check(routes.size < 20_000) { "播放清单过大" }
            routes[it] = entry
        }
        return "http://127.0.0.1:${server.localPort}$route"
    }

    private fun serve(socket: Socket) {
        socket.use {
            var responseStarted = false
            var isMediaRequest = false
            try {
                socket.soTimeout = 15_000
                val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val requestLine = reader.readLine()?.split(' ') ?: return
                val output = socket.getOutputStream()
                if (requestLine.size != 3 || requestLine[0] !in setOf("GET", "HEAD")) {
                    writeHeaders(output, 405)
                    return
                }
                val route = routes[requestLine[1]]
                if (route == null) {
                    writeHeaders(output, 404)
                    return
                }
                isMediaRequest = route.isMedia
                val headers = mutableMapOf<String, String>()
                var headerBytes = 0
                while (true) {
                    val line = reader.readLine() ?: return
                    headerBytes += line.length
                    require(headerBytes <= 16_384)
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val head = requestLine[0] == "HEAD"
                val request = Request.Builder().url(route.url).method(if (head) "HEAD" else "GET", null)
                headers["range"]?.let { request.header("Range", it) }
                headers["if-range"]?.let { request.header("If-Range", it) }
                val call = client.newCall(request.build())
                calls.add(call)
                try {
                    if (closed) {
                        call.cancel()
                        return
                    }
                    call.execute().use { response ->
                        if (response.isSuccessful && isMediaRequest) lastFailure = null
                        if (!response.isSuccessful && isMediaRequest) {
                            lastFailure = if (response.code == 408 || response.code >= 500) {
                                PlaybackFailureKind.NETWORK
                            } else PlaybackFailureKind.SOURCE
                        }
                        val responseHeaders = mutableMapOf<String, String>()
                        listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges").forEach { name ->
                            response.header(name)?.let { responseHeaders[name] = it }
                        }
                        val body = response.body
                        val playlist = !head && response.isSuccessful && body != null &&
                            (response.request.url.encodedPath.endsWith(".m3u8", true) ||
                                response.header("Content-Type").orEmpty().contains("mpegurl", true))
                        if (playlist) {
                            val bytes = body!!.byteStream().readBytesLimited(2 * 1024 * 1024)
                            val rewritten = rewritePlaylist(bytes.toString(Charsets.UTF_8), response.request.url, isMediaRequest)
                                .toByteArray(Charsets.UTF_8)
                            responseHeaders["Content-Length"] = rewritten.size.toString()
                            responseHeaders["Content-Type"] = "application/vnd.apple.mpegurl"
                            writeHeaders(output, response.code, responseHeaders)
                            responseStarted = true
                            output.write(rewritten)
                        } else {
                            writeHeaders(output, response.code, responseHeaders)
                            responseStarted = true
                            if (!head && body != null) streamBody(body.byteStream(), output, isMediaRequest)
                        }
                    }
                } finally {
                    calls.remove(call)
                }
            } catch (_: java.io.IOException) {
                if (isMediaRequest && !responseStarted) lastFailure = PlaybackFailureKind.NETWORK
                if (!responseStarted) runCatching { writeHeaders(socket.getOutputStream(), 502) }
            } catch (_: IllegalArgumentException) {
                if (!responseStarted) runCatching { writeHeaders(socket.getOutputStream(), 400) }
            } catch (_: IllegalStateException) {
                if (!responseStarted) runCatching { writeHeaders(socket.getOutputStream(), 502) }
            }
        }
    }

    private fun rewritePlaylist(playlist: String, base: HttpUrl, isMedia: Boolean): String =
        playlist.removePrefix("\uFEFF").lineSequence().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            !line.startsWith('#') -> register(requireNotNull(base.resolve(line.trim())).toString(), isMedia)
            else -> Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                val url = requireNotNull(base.resolve(match.groupValues[1]))
                "URI=\"${register(url.toString(), isMedia)}\""
            }
        }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun streamBody(input: java.io.InputStream, output: OutputStream, isMedia: Boolean) {
        val buffer = ByteArray(64 * 1024)
        while (!closed) {
            val count = try {
                input.read(buffer)
            } catch (error: java.io.IOException) {
                if (isMedia) lastFailure = PlaybackFailureKind.NETWORK
                throw error
            }
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    private fun writeHeaders(output: OutputStream, code: Int, headers: Map<String, String> = emptyMap()) {
        val text = buildString {
            append("HTTP/1.1 $code Response\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.US_ASCII))
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        server.close()
        calls.forEach(Call::cancel)
        sockets.forEach { runCatching { it.close() } }
        acceptor.shutdownNow()
        workers.shutdownNow()
        routes.clear()
    }
}
