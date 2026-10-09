package com.embydog.app.core.update

import java.math.BigInteger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val UPDATE_REPOSITORY = "BigFishWuhu/EmbyDog"

internal data class AppVersion(val core: List<BigInteger>, val pre: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        core.zip(other.core).forEach { (a, b) -> a.compareTo(b).takeIf { it != 0 }?.let { return it } }
        if (pre.isEmpty() && other.pre.isNotEmpty()) return 1
        if (pre.isNotEmpty() && other.pre.isEmpty()) return -1
        pre.zip(other.pre).forEach { (a, b) ->
            val an = a.toBigIntegerOrNull(); val bn = b.toBigIntegerOrNull()
            val result = when { an != null && bn != null -> an.compareTo(bn); an != null -> -1; bn != null -> 1; else -> a.compareTo(b) }
            if (result != 0) return result
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(value: String): AppVersion? {
            val match = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
                .matchEntire(value.removePrefix("v")) ?: return null
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            if (pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return AppVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }
    }
}

@Serializable
internal data class GithubRelease(@SerialName("tag_name") val tag: String, val body: String? = null,
    val draft: Boolean = false, val prerelease: Boolean = false, val assets: List<GithubAsset> = emptyList())

@Serializable
data class GithubAsset(val name: String, val size: Long = 0,
    @SerialName("browser_download_url") val url: String, val digest: String? = null)

data class UpdateCandidate(val version: String, val notes: String, val apk: GithubAsset,
    val checksum: GithubAsset?, val expectedSha256: String?)

internal fun selectUpdate(release: GithubRelease, installed: String, abis: List<String>): UpdateCandidate? {
    val current = AppVersion.parse(installed) ?: error("当前应用版本格式不受支持")
    val next = AppVersion.parse(release.tag) ?: error("GitHub 版本格式不受支持")
    if (release.draft || release.prerelease || next.pre.isNotEmpty() || next <= current) return null
    val preferred = if ("arm64-v8a" in abis) "EmbyDog-${release.tag}-arm64-v8a.apk" else "EmbyDog-${release.tag}-universal.apk"
    val apk = release.assets.firstOrNull { it.name == preferred }
        ?: release.assets.firstOrNull { it.name == "EmbyDog-${release.tag}-universal.apk" }
        ?: error("最新版本没有适合当前设备的 APK")
    require(apk.size in 1..MAX_APK_BYTES) { "APK 大小不受支持" }
    require(isReleaseAssetUrl(apk.url)) { "更新下载地址不受信任" }
    val checksum = release.assets.firstOrNull { it.name == "SHA256SUMS.txt" }?.also {
        require(isReleaseAssetUrl(it.url)) { "校验文件地址不受信任" }
    }
    val digest = apk.digest?.removePrefix("sha256:")?.takeIf { Regex("[0-9a-fA-F]{64}").matches(it) }?.lowercase()
    require(digest != null || checksum != null) { "最新版本缺少 SHA256 校验信息" }
    return UpdateCandidate(release.tag.removePrefix("v"), release.body.orEmpty().take(32000), apk, checksum, digest)
}

internal const val MAX_APK_BYTES = 256L * 1024 * 1024

internal fun isReleaseAssetUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.host == "github.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        url.encodedPath.startsWith("/$UPDATE_REPOSITORY/releases/download/")
}

internal fun isDownloadRedirectUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        (isReleaseAssetUrl(value) || url.host.endsWith(".githubusercontent.com"))
}

internal fun checksumFor(text: String, filename: String): String = text.lineSequence().mapNotNull { line ->
    val match = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").matchEntire(line.trim())
    match?.takeIf { it.groupValues[2] == filename }?.groupValues?.get(1)?.lowercase()
}.singleOrNull() ?: error("校验清单中没有唯一的 APK 校验值")
