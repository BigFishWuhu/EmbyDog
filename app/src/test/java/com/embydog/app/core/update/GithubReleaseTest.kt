package com.embydog.app.core.update

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GithubReleaseTest {
    private val hash = "ab".repeat(32)
    private fun release(tag: String = "v0.2.0") = GithubRelease(tag, assets = listOf(
        GithubAsset("EmbyDog-$tag-arm64-v8a.apk", 123, "https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/arm.apk", "sha256:$hash"),
        GithubAsset("EmbyDog-$tag-universal.apk", 456, "https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/all.apk", "sha256:$hash")))

    @Test fun semanticVersionsCompareNumbersAndPrereleasesCorrectly() {
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.0")!!)
        assertTrue(AppVersion.parse("1.0.0")!! > AppVersion.parse("1.0.0-rc.2")!!)
        assertTrue(AppVersion.parse("1.0.0-rc.10")!! > AppVersion.parse("1.0.0-rc.2")!!)
        assertTrue(AppVersion.parse("1.0.0-alpha.1")!! < AppVersion.parse("1.0.0-alpha.beta")!!)
        assertEquals(0, AppVersion.parse("v1.2.3+build.7")!!.compareTo(AppVersion.parse("1.2.3")!!))
        listOf("1.2", "01.2.3", "1.2.3-01", "../../bad").forEach { assertNull(AppVersion.parse(it)) }
    }
    @Test fun selectsArm64OnlyOnSupportedDevicesAndFallsBackToUniversal() {
        assertTrue(selectUpdate(release(), "0.1.0", listOf("arm64-v8a", "armeabi-v7a"))!!.apk.name.endsWith("arm64-v8a.apk"))
        assertTrue(selectUpdate(release(), "0.1.0", listOf("x86_64"))!!.apk.name.endsWith("universal.apk"))
        assertTrue(selectUpdate(release().copy(assets = release().assets.drop(1)), "0.1.0", listOf("arm64-v8a"))!!.apk.name.endsWith("universal.apk"))
    }
    @Test fun olderEqualDraftAndPrereleaseNeverOfferUpdate() {
        assertNull(selectUpdate(release(), "0.2.0", listOf("arm64-v8a")))
        assertNull(selectUpdate(release(), "0.3.0", listOf("arm64-v8a")))
        assertNull(selectUpdate(release().copy(draft = true), "0.1.0", listOf("arm64-v8a")))
        assertNull(selectUpdate(release("v0.2.0-rc.1"), "0.1.0", listOf("arm64-v8a")))
    }
    @Test fun missingChecksumsWrongHostAndOversizedApkAreRejected() {
        val asset = release().assets.first()
        listOf(asset.copy(digest = null), asset.copy(url = "https://evil.example/update.apk"), asset.copy(size = MAX_APK_BYTES + 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { selectUpdate(release().copy(assets = listOf(it)), "0.1.0", listOf("arm64-v8a")) }
        }
    }
    @Test fun parsesGithubDigestAndChecksumManifestFallback() {
        val fromJson = Json { ignoreUnknownKeys = true }.decodeFromString<GithubRelease>("""{"tag_name":"v0.2.0","assets":[{"name":"EmbyDog-v0.2.0-universal.apk","size":456,"browser_download_url":"https://github.com/$UPDATE_REPOSITORY/releases/download/v0.2.0/all.apk","digest":"sha256:$hash"}]}""")
        assertEquals(hash, selectUpdate(fromJson, "0.1.0", listOf("x86_64"))!!.expectedSha256)
        val manifest = GithubAsset("SHA256SUMS.txt", 100, "https://github.com/$UPDATE_REPOSITORY/releases/download/v0.2.0/SHA256SUMS.txt")
        assertEquals(manifest, selectUpdate(release().copy(assets = listOf(release().assets.first().copy(digest = null), manifest)), "0.1.0", listOf("arm64-v8a"))!!.checksum)
        assertEquals(hash, checksumFor("${"cd".repeat(32)}  mapping.txt\r\n${hash.uppercase()}  target.apk\r\n", "target.apk"))
        assertThrows(IllegalStateException::class.java) { checksumFor("$hash  wrong.apk", "target.apk") }
        assertThrows(IllegalStateException::class.java) { checksumFor("$hash  target.apk\n$hash  target.apk", "target.apk") }
    }
    @Test fun urlsRejectDowngradeCredentialsAndLookalikeHosts() {
        assertTrue(isReleaseAssetUrl("https://github.com/$UPDATE_REPOSITORY/releases/download/v1.0.0/a.apk"))
        assertTrue(isDownloadRedirectUrl("https://release-assets.githubusercontent.com/path?signature=public"))
        listOf("http://github.com/$UPDATE_REPOSITORY/releases/download/x", "https://github.com.evil/$UPDATE_REPOSITORY/releases/download/x",
            "https://github.com/other/repo/releases/download/x", "https://user:pass@github.com/$UPDATE_REPOSITORY/releases/download/x").forEach { assertFalse(isReleaseAssetUrl(it)) }
        assertFalse(isDownloadRedirectUrl("https://githubusercontent.com.evil/a"))
        assertFalse(isDownloadRedirectUrl("http://release-assets.githubusercontent.com/a"))
    }
}
