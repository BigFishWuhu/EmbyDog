package com.embydog.app.core.update

import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class UpdateDownloadDeviceTest {
    @Test fun validSignedUpdateDownloadsAndCanBeSharedWithInstaller(): Unit = runBlocking {
        fixture { repository, server, apk, root ->
            server.enqueue(MockResponse().setBody(Buffer().write(apk.readBytes())))
            var reported = 0L
            val file = repository.download(candidate(apk)) { downloaded, _ -> reported = downloaded }
            assertEquals(apk.length(), file.length())
            assertEquals(apk.length(), reported)
            assertTrue(File(root, "updates").listFiles().orEmpty().none { it.name.endsWith(".part") })
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Also validate the app's actual narrow FileProvider path without invoking installation.
            val shared = File(context.cacheDir, "updates/installer-fixture.apk")
            shared.parentFile!!.mkdirs(); shared.writeBytes(byteArrayOf(1))
            try { assertEquals("content", FileProvider.getUriForFile(context, "${context.packageName}.updates", shared).scheme) }
            finally { shared.delete() }
        }
    }

    @Test fun badChecksumAndWrongVersionNeverLeaveAnInstallableFile(): Unit = runBlocking {
        fixture { repository, server, apk, root ->
            server.enqueue(MockResponse().setBody(Buffer().write(apk.readBytes())))
            try { repository.download(candidate(apk).copy(expectedSha256 = "00".repeat(32))) { _, _ -> }; fail("Bad checksum must fail") }
            catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("校验失败")) }
            assertTrue(File(root, "updates").listFiles().orEmpty().isEmpty())
            server.enqueue(MockResponse().setBody(Buffer().write(apk.readBytes())))
            try { repository.download(candidate(apk).copy(version = "99.0.0")) { _, _ -> }; fail("Wrong APK version must fail") }
            catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("版本")) }
            assertTrue(File(root, "updates").listFiles().orEmpty().isEmpty())
        }
    }

    private fun candidate(apk: File): UpdateCandidate {
        val digest = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        return UpdateCandidate("0.1.0-ci.1", "Fixture", GithubAsset("fixture.apk", apk.length(),
            "https://github.com/$UPDATE_REPOSITORY/releases/download/v0.1.0-ci.1/fixture.apk"), null, digest)
    }

    private suspend fun fixture(block: suspend (GithubUpdateRepository, MockWebServer, File, File) -> Unit) {
        val filename = InstrumentationRegistry.getArguments().getString("updateFixture")
        assumeTrue("An explicit signed newer-version APK fixture is required", filename != null)
        val apk = File(filename!!)
        assertTrue("APK fixture must exist", apk.isFile)
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(original.cacheDir, "update-fixture-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(original) { override fun getCacheDir() = root }
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/fixture.apk")).build())
            }.build()
            try { block(GithubUpdateRepository(context, Json { ignoreUnknownKeys = true }, GithubHttpTransport(client, "EmbyDog/test")), server, apk, root) }
            finally {
                val updates = File(root, "updates")
                updates.listFiles().orEmpty().forEach { it.delete() }; updates.delete(); root.delete()
            }
        }
    }
}
