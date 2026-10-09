package com.embydog.app.core.offline

import android.content.Intent
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.core.media.*
import com.embydog.app.core.model.*
import com.embydog.app.core.session.privateCacheKey
import com.embydog.app.debug.PlayerTestActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.embydog.app.core.network.EmbyApi
import com.embydog.app.core.security.TokenCipher
import com.embydog.app.core.session.SessionStore
import com.embydog.app.feature.login.DeviceIdentity
import com.embydog.app.feature.media.MediaRepository
import com.embydog.app.feature.player.PlayerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class OfflineDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()

    private fun record(user: String = "fixture", status: DownloadStatus = DownloadStatus.COMPLETED) = OfflineRecord(
        privateCacheKey(user, "item"), privateCacheKey(user), "离线测试 · $user", "http://127.0.0.1:1/emby/", user, "item", "离线测试视频", "wav",
        40_000, PlaybackSelection(audioIndex = 0, subtitleIndex = 1, mediaSourceId = "item"),
        listOf(MediaTrackOption(0, MediaTrackKind.AUDIO, "PCM", null, false),
            MediaTrackOption(1, MediaTrackKind.SUBTITLE, "简体中文字幕", "zh", true, isDefault = true)),
        status = status, positionMs = 5_000)

    @Test fun completedCacheSurvivesRestartAndProducesOnlyLocalSources() = fixture { root, store ->
        val source = ready(store)
        store.savePosition(source.offlineId!!, 12_345)
        OfflineStore(root).use { restarted ->
            val local = restarted.playback(source.offlineId)
            assertEquals(12_345L, local.startPositionMs)
            assertEquals("", local.accessToken)
            assertNull(local.previewUrl); assertNull(local.playSessionId); assertNull(local.seriesId)
            assertTrue(local.uri.startsWith("file:")); assertTrue(local.subtitles.all { it.uri.startsWith("file:") })
            val manifest = File(root, "manifest.json").readText()
            assertFalse(manifest.contains("X-Emby-Token")); assertFalse(manifest.contains("api_key")); assertFalse(manifest.contains("accessToken"))
        }
    }

    @Test fun interruptedDownloadRecoversAsPausedAndRetainsPartialFile() = fixture { root, store ->
        val record = record(status = DownloadStatus.DOWNLOADING).copy(bytes = 3, totalBytes = 6, validator = "\"version1\"")
        store.directory(record.id).mkdirs(); store.partial(record.id).writeText("abc"); store.put(record)
        OfflineStore(root).use { restarted ->
            assertEquals(DownloadStatus.PAUSED, restarted.get(record.id)!!.status)
            assertEquals("abc", restarted.partial(record.id).readText())
            assertEquals("\"version1\"", restarted.get(record.id)!!.validator)
            assertTrue(runCatching { restarted.playback(record.id) }.isFailure)
        }
    }

    @Test fun profilesHaveDistinctFilesAndDeletionDoesNotRemoveAnotherProfile() = fixture { _, store ->
        val first = ready(store, "first"); val second = ready(store, "second")
        assertNotEquals(first.offlineId, second.offlineId)
        store.delete(first.offlineId!!)
        assertTrue(File(java.net.URI(second.uri)).isFile)
        assertEquals(1, store.state.value.records.size)
        assertEquals(second.uri, store.playback(second.offlineId!!).uri)
        assertTrue(runCatching { store.directory("../../outside") }.isFailure)
    }

    @Test fun incompleteMissingAndDamagedFilesCannotBePlayed() = fixture { _, store ->
        val record = record(status = DownloadStatus.PAUSED)
        store.put(record); assertTrue(runCatching { store.playback(record.id) }.isFailure)
        store.put(record.copy(status = DownloadStatus.COMPLETED, totalBytes = 5)); assertTrue(runCatching { store.playback(record.id) }.isFailure)
        store.directory(record.id).mkdirs(); store.video(record).writeText("bad")
        assertTrue(runCatching { store.playback(record.id) }.isFailure)
    }

    @Test fun corruptManifestIsNotSilentlyOverwrittenOrDeleted() = fixture { root, store ->
        val source = ready(store)
        File(root, "manifest.json").writeText("not-json")
        OfflineStore(root).use { broken ->
            assertNotNull(broken.readError)
            assertTrue(runCatching { broken.put(record()) }.isFailure)
            assertEquals("not-json", File(root, "manifest.json").readText())
            assertTrue(File(java.net.URI(source.uri)).exists())
        }
    }

    @Test fun localMpvPlaysSeeksAndLoadsSubtitleWithoutAnyHttpCalls() = localPlayer(mpv = true)
    @Test fun localMedia3PlaysSeeksAndLoadsSubtitleWithoutAnyHttpCalls() = localPlayer(mpv = false)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun offlinePlayerViewModelWorksWithNoLoginAndSavesLocalProgress() = fixture { root, store ->
        val source = ready(store)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { requests.incrementAndGet(); throw java.io.IOException("No network in offline test") }.build()
        val api = api(client)
        val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sessions = SessionStore(PreferenceDataStoreFactory.create(scope = dataScope, produceFile = { File(root, "empty-session.preferences_pb") }), TokenCipher())
        assertNull(runBlocking { sessions.session.first() })
        val reporter = PlaybackProgressReporter(api, DeviceIdentity(instrument.targetContext))
        val repository = MediaRepository(api, sessions, reporter)
        val cache = SimpleCache(File(root, "vm-cache"), LeastRecentlyUsedCacheEvictor(1024 * 1024), StandaloneDatabaseProvider(instrument.targetContext))
        val factory = PlaybackEngineFactory(instrument.targetContext, MediaPlayerFactory(instrument.targetContext, cache, client), client)
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, PlayerTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val lifecycle = ViewModelStore()
        var model: PlayerViewModel? = null
        try {
            instrument.runOnMainSync {
                model = PlayerViewModel(SavedStateHandle(mapOf("offlineId" to source.offlineId)), repository, factory, client, reporter, store)
                lifecycle.put("offline-player", model!!)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (model!!.state.value.isLoading && System.nanoTime() < deadline) Thread.sleep(50)
            assertNull(model!!.state.value.errorMessage)
            val engine = requireNotNull(model!!.state.value.engine)
            instrument.runOnMainSync { activity.setContentView(engine.createVideoView(activity)) }
            await(engine) { it.isPlaying && it.positionMs >= 5_000 }
            instrument.runOnMainSync { model!!.seekTo(20_000) }
            await(engine) { it.positionMs >= 19_500 }
            instrument.runOnMainSync { model!!.stopPlayback() }
            val savedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (store.get(source.offlineId!!)!!.positionMs < 19_500 && System.nanoTime() < savedDeadline) Thread.sleep(50)
            assertTrue(store.get(source.offlineId!!)!!.positionMs >= 19_500)
            assertEquals("No login, PlaybackInfo, thumbnails or reporting requests", 0, requests.get())
        } finally {
            instrument.runOnMainSync { model?.stopPlayback(); lifecycle.clear(); activity.finish() }
            cache.release(); dataScope.cancel()
        }
    }

    @Test fun downloadPreparationUsesOnlyMetadataAndDoesNotOpenPlaybackSession(): Unit = fixture { root, _ -> runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Id":"item","Name":"电影","Type":"Movie","MediaSources":[{"Id":"source","Container":"mkv","Size":128,"MediaStreams":[{"Type":"Audio","Index":0}]}]}"""))
            val client = OkHttpClient()
            val api = api(client)
            val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val sessions = SessionStore(PreferenceDataStoreFactory.create(scope = dataScope, produceFile = { File(root, "download-session.preferences_pb") }), TokenCipher())
                sessions.save(Session(server.url("/emby/").toString(), "Fixture", "server", "fixture", "Fixture", "fixture-token"))
                val repository = MediaRepository(api, sessions, PlaybackProgressReporter(api, DeviceIdentity(instrument.targetContext)))
                val plan = repository.getOfflinePlan("item", PlaybackSelection(audioIndex = 0))
                assertEquals(server.url("/emby/Items/item/Download").toString(), plan.url)
                assertEquals(128L, plan.record.totalBytes)
                assertEquals("source", plan.record.selection.mediaSourceId)
                assertEquals(1, server.requestCount)
                val request = server.takeRequest()
                assertEquals("GET", request.method); assertTrue(request.path!!.startsWith("/emby/Users/fixture/Items/item"))
                assertFalse(request.path!!.contains("PlaybackInfo"))
            } finally { dataScope.cancel() }
        }
    } }

    private fun api(client: OkHttpClient) = Retrofit.Builder().baseUrl("http://127.0.0.1:1/").client(client)
        .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType())).build().create(EmbyApi::class.java)

    @Test fun completeDownloadIncludesSubtitleAndPlaysAfterProfileAndServerAreGone() = downloadFixture { _, store, sessions, manager, downloader, plan, server, requests -> runBlocking {
        downloader.download(plan.record)
        val record = requireNotNull(store.get(plan.record.id))
        assertEquals(record.message, DownloadStatus.COMPLETED, record.status)
        assertEquals(waveBytes().size.toLong(), record.totalBytes)
        assertEquals(1, record.subtitles.size)
        assertTrue(requests.all { it.getHeader("X-Emby-Token") == "fixture-token" })
        assertTrue(requests.none { it.path.orEmpty().contains("PlaybackInfo") || it.path.orEmpty().contains("Sessions/Playing") })
        assertTrue(manager.networkAllowed())
        sessions.remove(record.profileId)
        server.shutdown()
        val source = store.playback(record.id)
        assertEquals("", source.accessToken); assertTrue(source.uri.startsWith("file:"))
        assertTrue(File(java.net.URI(source.subtitles.single().uri)).readText().contains("离线缓存字幕"))
        assertTrue(source.tracks.any { it.kind == MediaTrackKind.SUBTITLE })
    } }

    @Test fun pausedDownloadContinuesWithRangeAndDoesNotDuplicateBytes() = downloadFixture(slowFirst = true) { _, store, _, manager, downloader, plan, _, requests -> runBlocking {
        val job = async { downloader.download(plan.record) }
        withTimeout(8_000) { while (store.partial(plan.record.id).length() < 8_000) delay(30) }
        manager.pause(plan.record.id)
        withTimeout(3_000) { job.await() }
        val partialBytes = store.partial(plan.record.id).length()
        assertEquals(DownloadStatus.PAUSED, store.get(plan.record.id)!!.status)
        assertEquals(partialBytes, store.get(plan.record.id)!!.bytes)
        store.update(plan.record.id) { it.copy(status = DownloadStatus.QUEUED) }
        downloader.download(store.get(plan.record.id)!!)
        val record = store.get(plan.record.id)!!
        assertEquals(record.message, DownloadStatus.COMPLETED, record.status)
        assertArrayEquals(waveBytes(), store.video(record).readBytes())
        assertTrue(requests.any { it.getHeader("Range") == "bytes=$partialBytes-" && it.getHeader("If-Range") == "\"fixture-v1\"" })
    } }

    @Test fun deniedSubtitleIsVisibleButDoesNotInvalidateCompletedVideo() = downloadFixture(subtitleCode = 403) { _, store, _, _, downloader, plan, _, _ -> runBlocking {
        downloader.download(plan.record)
        val record = store.get(plan.record.id)!!
        assertEquals(DownloadStatus.COMPLETED, record.status)
        assertTrue(record.message!!.contains("1 条外置字幕"))
        val source = store.playback(record.id)
        assertTrue(source.subtitles.isEmpty()); assertTrue(source.tracks.none { it.isExternal })
    } }

    private fun downloadFixture(slowFirst: Boolean = false, subtitleCode: Int = 200,
        test: (File, OfflineStore, SessionStore, OfflineManager, OfflineDownloader, OfflinePlan, MockWebServer, List<RecordedRequest>) -> Unit) = fixture { root, store ->
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val firstDownload = AtomicBoolean(slowFirst)
        val wave = waveBytes()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when {
                    request.path.orEmpty().startsWith("/emby/Users/fixture/Items/item") -> MockResponse().setBody("""{"Id":"item","Name":"离线下载测试","Type":"Movie","MediaSources":[{"Id":"item","Container":"wav","Size":${wave.size},"RunTimeTicks":400000000,"MediaStreams":[{"Type":"Audio","Index":0},{"Type":"Subtitle","Index":1,"Codec":"srt","Language":"zh","IsExternal":true,"IsDefault":true}]}]}""")
                    request.path == "/emby/Items/item/Download" -> {
                        val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        MockResponse().setHeader("ETag", "\"fixture-v1\"").setHeader("Content-Type", "audio/wav")
                            .setBody(Buffer().write(wave, start, wave.size - start)).also {
                                if (start > 0) it.setResponseCode(206).setHeader("Content-Range", "bytes $start-${wave.lastIndex}/${wave.size}")
                                if (firstDownload.getAndSet(false)) it.throttleBody(4096, 100, TimeUnit.MILLISECONDS)
                            }
                    }
                    request.path.orEmpty().contains("/Subtitles/") -> MockResponse().setResponseCode(subtitleCode).setBody("1\n00:00:00,000 --> 00:00:39,000\n离线缓存字幕\n")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
        try {
            val sessions = SessionStore(PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "isolated-download-profile.preferences_pb") }), TokenCipher())
            val client = OkHttpClient()
            val api = api(client)
            val repository = MediaRepository(api, sessions, PlaybackProgressReporter(api, DeviceIdentity(instrument.targetContext)))
            val transport = OfflineTransport(client)
            val manager = OfflineManager(instrument.targetContext, store, repository, sessions, transport)
            store.wifiOnly(false)
            org.junit.Assume.assumeTrue("Emulator network capability is required for download constraints", manager.networkAllowed())
            val plan = runBlocking {
                sessions.save(Session(server.url("/emby/").toString(), "Fixture", "fixture-server", "fixture", "Fixture", "fixture-token"))
                repository.getOfflinePlan("item", PlaybackSelection(audioIndex = 0, subtitleIndex = 1)).let { it.copy(record = it.record.copy(status = DownloadStatus.QUEUED)) }
            }
            store.put(plan.record)
            test(root, store, sessions, manager, OfflineDownloader(store, manager, sessions, repository, transport), plan, server, requests)
        } finally { runCatching { server.shutdown() }; scope.cancel() }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun localPlayer(mpv: Boolean) = fixture { root, store ->
        val source = ready(store)
        val networkCalls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { networkCalls.incrementAndGet(); throw java.io.IOException("Offline test forbids networking") }.build()
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, PlayerTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var engine: PlaybackEngine? = null
        var cache: SimpleCache? = null
        try {
            instrument.runOnMainSync {
                engine = if (mpv) MpvPlaybackEngine(activity, source, PlaybackEngineKind.MPV_SOFTWARE, client, true)
                    else {
                        cache = SimpleCache(File(root, "temporary-play-cache"), LeastRecentlyUsedCacheEvictor(1024 * 1024), StandaloneDatabaseProvider(activity))
                        Media3PlaybackEngine(MediaPlayerFactory(activity, cache!!, client).create(source), source)
                    }
                activity.setContentView(engine!!.createVideoView(activity))
            }
            val active = requireNotNull(engine)
            await(active) { it.isPlaying && !it.isBuffering && it.positionMs >= 5_000 && it.failure == null }
            await(active) { snapshot -> snapshot.tracks.any { it.kind == PlaybackTrackKind.SUBTITLE && it.selected } }
            instrument.runOnMainSync { active.seekTo(20_000) }
            await(active) { it.positionMs >= 19_500 }
            instrument.runOnMainSync { active.pause() }
            await(active) { !it.isPlaying }
            assertEquals(0, networkCalls.get())
        } finally {
            instrument.runOnMainSync { engine?.release(); cache?.release(); activity.finish() }
        }
        assertTrue(File(java.net.URI(source.uri)).exists())
        assertTrue(source.subtitles.all { File(java.net.URI(it.uri)).exists() })
    }

    private fun ready(store: OfflineStore, user: String = "fixture"): PlaybackSource {
        val record = record(user)
        val directory = store.directory(record.id).apply { mkdirs() }
        store.video(record).writeBytes(waveBytes())
        val subtitle = File(directory, "subtitle-1.srt").apply { writeText("1\n00:00:00,000 --> 00:00:39,000\n离线字幕测试\n") }
        store.put(record.copy(bytes = store.video(record).length(), totalBytes = store.video(record).length(),
            subtitles = listOf(SubtitleTrack(subtitle.toURI().toString(), "application/x-subrip", "zh", "简体中文字幕", true, false, 1))))
        return store.playback(record.id)
    }

    private fun await(engine: PlaybackEngine, predicate: (PlaybackSnapshot) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            instrument.runOnMainSync { engine.refresh() }
            if (predicate(engine.snapshot.value)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for local playback: ${engine.snapshot.value}")
    }

    private fun fixture(test: (File, OfflineStore) -> Unit) {
        val root = Files.createTempDirectory(instrument.targetContext.cacheDir.toPath(), "offline-fixture-").toFile()
        try { OfflineStore(root).use { store -> test(root, store) } }
        finally { root.deleteRecursively() }
    }
    private fun waveBytes(): ByteArray {
        val size = 8000 * 2 * 40
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
        }.array()
    }
}
