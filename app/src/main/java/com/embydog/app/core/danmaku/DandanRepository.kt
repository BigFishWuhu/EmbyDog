package com.embydog.app.core.danmaku

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DanmakuSearchResult(val episodes: List<DanmakuEpisode>, val hasMore: Boolean)
data class DanmakuLoadResult(val comments: List<DanmakuComment>, val fromCache: Boolean)

@Singleton
class DandanRepository @Inject constructor(@ApplicationContext context: Context, private val json: Json) {
    private val transport = DandanTransport(OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build())
    private val directory = File(context.cacheDir, "danmaku")
    private val cacheMutex = Mutex()

    suspend fun search(query: String, episode: String?, credentials: DanmakuCredentials): DanmakuSearchResult {
        require(query.trim().length in 2..120) { "请输入 2–120 个字符的作品名" }
        val url = transport.apiUrl("search/episodes").newBuilder().addQueryParameter("anime", query.trim())
            .addQueryParameter("v2", "true").apply {
                episode?.trim()?.takeIf { it.matches(Regex("(?:[1-9][0-9]{0,4}|[CSO][1-9][0-9]{0,4})")) }
                    ?.let { addQueryParameter("episode", it) }
            }.build()
        val dto = json.decodeFromString<DandanSearchResponse>(transport.get(url, credentials, 1024 * 1024))
        require(dto.success) { "弹弹 Play 搜索未成功，请稍后重试" }
        val episodes = dto.animes.orEmpty().flatMap { anime -> anime.episodes.orEmpty().mapNotNull {
            if (it.episodeId <= 0 || anime.animeTitle.isNullOrBlank()) null
            else DanmakuEpisode(it.episodeId, anime.animeTitle.take(200), it.episodeTitle.orEmpty().take(200))
        } }.distinctBy { it.id }.take(500)
        return DanmakuSearchResult(episodes, dto.hasMore)
    }

    suspend fun cachedComments(episodeId: Long, credentials: DanmakuCredentials): DanmakuLoadResult = withContext(Dispatchers.IO) {
        val file = File(directory, "${danmakuHash(credentials.appId + "\u0000" + credentials.secret)}-$episodeId.json")
        val comments = cacheMutex.withLock {
            if (!file.isFile || file.length() > MAX_COMMENT_BYTES) emptyList()
            else runCatching { json.decodeFromString<List<DanmakuComment>>(file.readText()).take(MAX_COMMENTS) }.getOrDefault(emptyList())
        }
        DanmakuLoadResult(comments, true)
    }

    suspend fun comments(episodeId: Long, credentials: DanmakuCredentials, refresh: Boolean = false): DanmakuLoadResult {
        require(credentials.configured) { "请先在应用设置中配置弹弹 Play 接入凭据" }
        require(episodeId > 0)
        // App-specific comment libraries must not share caches across credentials.
        val file = File(directory, "${danmakuHash(credentials.appId + "\u0000" + credentials.secret)}-$episodeId.json")
        if (!refresh) {
            val cached = withContext(Dispatchers.IO) { cacheMutex.withLock {
                if (!file.isFile || file.length() > MAX_COMMENT_BYTES || System.currentTimeMillis() - file.lastModified() !in 0..CACHE_AGE_MS) null
                else runCatching { json.decodeFromString<List<DanmakuComment>>(file.readText()).take(MAX_COMMENTS) }.getOrNull()
            } }
            if (cached != null) return DanmakuLoadResult(cached, true)
        }
        val url = transport.apiUrl("comment/$episodeId").newBuilder().addQueryParameter("withRelated", "true").build()
        val raw = transport.get(url, credentials, MAX_COMMENT_BYTES)
        val comments = withContext(Dispatchers.Default) {
            json.decodeFromString<DandanCommentsResponse>(raw).comments.orEmpty().asSequence()
                .mapNotNull { it.parsed() }.distinct().sortedBy { it.timeMs }.take(MAX_COMMENTS).toList()
        }
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.IO) { cacheMutex.withLock {
            // Cache failures must never stop video playback or discard downloaded comments.
            runCatching {
                check(directory.isDirectory || directory.mkdirs())
                val bytes = json.encodeToString(comments).toByteArray(Charsets.UTF_8)
                if (bytes.size <= MAX_COMMENT_BYTES) {
                    val atomic = AtomicFile(file)
                    val output = atomic.startWrite()
                    try { output.write(bytes); atomic.finishWrite(output) }
                    catch (error: Exception) { atomic.failWrite(output); throw error }
                }
                var total = 0L
                directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
                    .forEachIndexed { index, entry -> total += entry.length(); if (index >= 20 || total > 64L * 1024 * 1024) entry.delete() }
            }
        } }
        return DanmakuLoadResult(comments, false)
    }

    private companion object {
        const val MAX_COMMENT_BYTES = 12 * 1024 * 1024
        const val MAX_COMMENTS = 20_000
        const val CACHE_AGE_MS = 7L * 24 * 3600 * 1000
    }
}

/** Independent of Emby authentication, cookies, logging and interceptors. */
internal class DandanTransport(
    private val client: OkHttpClient,
    private val base: HttpUrl = "https://api.dandanplay.net/".toHttpUrl(),
    private val allowedRedirect: (HttpUrl) -> Boolean = ::isDandanDownload,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun apiUrl(path: String): HttpUrl = base.newBuilder().addPathSegments("api/v2/$path").build()

    suspend fun get(url: HttpUrl, credentials: DanmakuCredentials, limit: Int): String {
        require(credentials.configured) { "请先在应用设置中配置弹弹 Play 接入凭据" }
        require(url.scheme == base.scheme && url.host == base.host && url.port == base.port && url.encodedPath.startsWith("/api/v2/"))
        val timestamp = nowSeconds()
        var request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "EmbyDog")
            .header("X-AppId", credentials.appId).header("X-Timestamp", timestamp.toString())
            .header("X-Signature", dandanSignature(credentials.appId, timestamp, url.encodedPath, credentials.secret)).build()
        repeat(5) { hop ->
            val result = response(request, limit)
            result.text?.let { return it }
            val target = requireNotNull(result.redirect)
            require(hop < 4 && allowedRedirect(target)) { "弹幕服务返回了不受支持的下载地址" }
            // Build a fresh request: no signature or app credentials are forwarded to the CDN.
            request = Request.Builder().url(target).header("Accept", "application/json").header("User-Agent", "EmbyDog").build()
        }
        error("弹幕服务重定向过多")
    }

    private data class Result(val text: String? = null, val redirect: HttpUrl? = null)

    private suspend fun response(request: Request, limit: Int): Result = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(IOException("弹幕网络连接失败，请稍后重试")) }
            override fun onResponse(call: Call, response: Response) = response.use { result ->
                if (!continuation.isActive) return
                try {
                    val value = if (result.code in listOf(301, 302, 303, 307, 308)) {
                        Result(redirect = result.header("Location")?.let { result.request.url.resolve(it) }
                            ?: error("弹幕服务返回了无效下载地址"))
                    } else {
                        when (result.code) {
                            401, 403 -> error("弹弹 Play 鉴权失败，请检查 AppId、AppSecret、设备时间和应用权限")
                            429 -> error("弹弹 Play 请求额度不足，请稍后再试")
                        }
                        require(result.isSuccessful) { "弹幕请求失败（HTTP ${result.code}）" }
                        val body = requireNotNull(result.body)
                        require(body.contentLength() <= limit) { "弹幕数据超过大小限制" }
                        val output = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= limit) { "弹幕数据超过大小限制" }
                                output.write(buffer, 0, count)
                            }
                        }
                        Result(text = output.toString("UTF-8"))
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
}

internal fun isDandanDownload(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
    url.username.isEmpty() && url.password.isEmpty() &&
    listOf("dandanplay.net", "dandanplay.com").any { url.host == it || url.host.endsWith(".$it") }
