package com.embydog.app.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.embydog.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class GithubUpdateRepository internal constructor(private val context: Context, json: Json, private val transport: GithubHttpTransport) {
    @Inject constructor(@ApplicationContext context: Context, json: Json) : this(context, json,
        GithubHttpTransport(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build(), "EmbyDog/${BuildConfig.VERSION_NAME}"))
    private val codec = json
    // Deliberately independent of authenticated Emby traffic, cookies and account settings.

    suspend fun check(): UpdateCandidate? {
        val release = transport.response("https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest") { result ->
            when (result.code) {
                404 -> error("GitHub 暂未发布正式版本")
                403, 429 -> error("GitHub 请求受到限制，请稍后再试")
                else -> require(result.isSuccessful) { "GitHub 检查更新失败（HTTP ${result.code}）" }
            }
            codec.decodeFromString<GithubRelease>(transport.readText(result, 1024 * 1024))
        }
        return selectUpdate(release, BuildConfig.VERSION_NAME, Build.SUPPORTED_ABIS.toList())
    }

    suspend fun download(candidate: UpdateCandidate, progress: (Long, Long) -> Unit): File {
        val coroutine = currentCoroutineContext()
        val expected = candidate.expectedSha256 ?: checksumFor(transport.downloadText(requireNotNull(candidate.checksum).url), candidate.apk.name)
        val directory = File(context.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
        val partial = File.createTempFile("github-update-", ".part", directory)
        val destination = File(directory, "github-update.apk")
        try {
            transport.withDownload(candidate.apk.url) { result ->
                require(result.isSuccessful) { "APK 下载失败（HTTP ${result.code}）" }
                val body = requireNotNull(result.body)
                require(body.contentLength() <= MAX_APK_BYTES) { "APK 超过大小限制" }
                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                var notified = 0L
                partial.outputStream().use { output -> body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= MAX_APK_BYTES && downloaded <= candidate.apk.size) { "APK 下载大小异常" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        if (downloaded - notified >= 256 * 1024) { progress(downloaded, candidate.apk.size); notified = downloaded }
                    }
                } }
                require(downloaded == candidate.apk.size) { "APK 下载不完整，请重试" }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == expected) { "APK 校验失败，文件已拒绝使用" }
                coroutine.ensureActive()
                verifyPackage(partial, candidate.version)
                progress(downloaded, downloaded)
            }
            coroutine.ensureActive()
            check(!destination.exists() || destination.delete()) { "旧更新文件无法清理" }
            check(partial.renameTo(destination)) { "更新文件保存失败" }
            return destination
        } finally { if (partial.exists()) partial.delete() }
    }

    @Suppress("DEPRECATION")
    internal fun verifyPackage(file: File, expectedVersion: String) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("下载的文件不是有效 APK")
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName && archive.versionName == expectedVersion) { "APK 包名或版本与更新不一致" }
        val nextCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val currentCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(nextCode > currentCode) { "APK 版本号没有增加，不能覆盖升级" }
        fun signers(info: android.content.pm.PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
        val current = signers(installed)
        require(current.isNotEmpty() && current == signers(archive)) {
            "更新签名与当前安装不一致。Debug 测试版不能直接升级正式签名版，请先备份配置；应用不会自动卸载。"
        }
    }
}

internal class GithubHttpTransport(private val client: OkHttpClient, private val userAgent: String) {

    suspend fun downloadText(url: String): String = withDownload(url) { result ->
        require(result.isSuccessful) { "更新校验文件下载失败" }
        readText(result, 64 * 1024)
    }

    suspend fun <T> withDownload(initial: String, block: (Response) -> T): T {
        require(isReleaseAssetUrl(initial)) { "更新下载地址不受信任" }
        var url = initial
        repeat(6) {
            var redirect: String? = null
            var value: T? = null
            response(url) { result ->
                if (result.code in listOf(301, 302, 303, 307, 308)) {
                    val location = requireNotNull(result.header("Location")) { "下载重定向缺少地址" }
                    redirect = result.request.url.resolve(location)?.toString()
                    require(redirect != null && isDownloadRedirectUrl(redirect!!)) { "下载重定向地址不受信任" }
                } else value = block(result)
            }
            if (redirect == null) {
                @Suppress("UNCHECKED_CAST")
                return value as T
            }
            url = redirect!!
        }
        error("下载重定向次数过多")
    }

    fun readText(response: Response, limit: Int): String {
        val body = requireNotNull(response.body)
        require(body.contentLength() <= limit) { "GitHub 响应超过大小限制" }
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                require(output.size() + count <= limit) { "GitHub 响应超过大小限制" }
                output.write(bytes, 0, count)
            }
        }
        return output.toString("UTF-8")
    }

    suspend fun <T> response(url: String, block: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("User-Agent", userAgent)
            .header("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "application/octet-stream").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try { val value = block(it); if (continuation.isActive) continuation.resume(value) }
                    catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            }
        })
    }
}
