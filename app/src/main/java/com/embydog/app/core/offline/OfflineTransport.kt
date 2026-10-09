package com.embydog.app.core.offline

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class DownloadCheckpoint(val bytes: Long, val total: Long, val validator: String?)
class DownloadPaused(message: String) : IOException(message)

/** Raw-file transport. A 200 response to Range always replaces, never appends. */
@javax.inject.Singleton
class OfflineTransport @Inject constructor(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cache(null).readTimeout(20, TimeUnit.SECONDS).build()
    private val calls = ConcurrentHashMap<String, Call>()
    private val generations = ConcurrentHashMap<String, AtomicLong>()
    fun cancel(id: String) { generations.getOrPut(id) { AtomicLong() }.incrementAndGet(); calls[id]?.cancel() }

    suspend fun download(id: String, url: String, token: String, file: File, previous: DownloadCheckpoint,
        maxBytes: Long = Long.MAX_VALUE, authOrigin: String = url, guard: () -> Unit = {},
        checkpoint: (DownloadCheckpoint) -> Unit = {}): DownloadCheckpoint = withContext(Dispatchers.IO) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) { "无法创建缓存目录" }
        val coroutine = currentCoroutineContext()
        val generation = generations.getOrPut(id) { AtomicLong() }.get()
        var offset = file.length().takeIf { previous.validator != null } ?: 0L
        if (offset == 0L && file.exists()) file.outputStream().use { }
        var resetAttempted = false
        try { while (true) {
            coroutine.ensureActive(); guard()
            val original = url.toHttpUrl()
            val origin = authOrigin.toHttpUrl()
            check(original.scheme == origin.scheme && original.host == origin.host && original.port == origin.port) { "为保护账号凭据，不支持跨服务器字幕下载" }
            val response = request(id, original, token, offset, previous.validator, generation)
            response.use {
                if (response.code == 416 && offset > 0 && !resetAttempted) {
                    file.outputStream().use { }; offset = 0; resetAttempted = true
                } else {
                    check(response.isSuccessful) { when (response.code) {
                        401, 403 -> "服务器拒绝下载，请检查账号下载权限或重新登录"
                        404 -> "服务器找不到原始媒体文件"
                        else -> "下载失败（HTTP ${response.code}）"
                    } }
                    val body = requireNotNull(response.body) { "服务器返回空文件" }
                    val type = response.header("Content-Type").orEmpty().lowercase()
                    check(!type.contains("mpegurl") && !type.contains("text/html") && !type.contains("application/json")) { "服务器返回的不是原始媒体文件" }
                    check(response.header("Content-Encoding").let { it == null || it.equals("identity", true) }) { "下载响应的编码不支持断点缓存" }
                    val validator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    val total: Long
                    val append = response.code == 206
                    if (append) {
                        val range = parseContentRange(response.header("Content-Range"))
                        check(range.first == offset && range.last == range.total - 1) { "服务器断点范围异常，请重试" }
                        check(offset == 0L || validator == null || validator == previous.validator) { "服务器文件已变化，请删除任务后重新缓存" }
                        check(body.contentLength() < 0 || body.contentLength() == range.last - range.first + 1) { "服务器断点长度异常" }
                        total = range.total
                    } else {
                        check(response.code == 200) { "服务器返回了不受支持的下载响应" }
                        offset = 0
                        // Clear stale bytes before committing the validator of the new response.
                        file.outputStream().use { }
                        total = body.contentLength()
                    }
                    check(total <= maxBytes) { "缓存文件超过大小限制" }
                    var bytes = offset
                    checkpoint(DownloadCheckpoint(bytes, total, validator))
                    var lastTick = System.nanoTime()
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(file, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutine.ensureActive()
                                val size = input.read(buffer)
                                if (size < 0) break
                                bytes += size
                                check(bytes <= maxBytes && (total < 0 || bytes <= total)) { "下载文件长度异常" }
                                output.write(buffer, 0, size)
                                if (System.nanoTime() - lastTick >= 750_000_000L) {
                                    guard(); checkpoint(DownloadCheckpoint(bytes, total, validator)); lastTick = System.nanoTime()
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    coroutine.ensureActive(); guard()
                    check(bytes > 0 && (total < 0 || bytes == total)) { "下载连接提前中断，点击继续重试" }
                    val result = DownloadCheckpoint(bytes, bytes, validator)
                    checkpoint(result)
                    calls.remove(id)
                    return@withContext result
                }
            }
        } } finally { calls.remove(id)?.cancel() }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun request(id: String, original: HttpUrl, token: String, offset: Long, validator: String?, generation: Long): Response {
        var url = original
        repeat(6) { attempt ->
            val builder = Request.Builder().url(url).header("Accept-Encoding", "identity").header("X-Emby-Token", token)
            if (offset > 0) builder.header("Range", "bytes=$offset-").header("If-Range", requireNotNull(validator))
            val call = client.newCall(builder.build())
            calls[id] = call
            if (generations[id]?.get() != generation) call.cancel()
            val response = call.execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let(url::resolve)
            response.close()
            check(next != null && attempt < 5) { "下载重定向异常" }
            // Do not send the account token (or downgrade HTTPS) to an external server.
            check(next.scheme == original.scheme && next.host == original.host && next.port == original.port) { "为保护账号凭据，不支持跨服务器下载重定向" }
            url = next
        }
        error("下载重定向过多")
    }
}

internal data class ContentRange(val first: Long, val last: Long, val total: Long)
internal fun parseContentRange(value: String?): ContentRange {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty()) ?: error("缺少有效的断点范围")
    val range = ContentRange(match.groupValues[1].toLong(), match.groupValues[2].toLong(), match.groupValues[3].toLong())
    check(range.first >= 0 && range.last >= range.first && range.total > range.last)
    return range
}
