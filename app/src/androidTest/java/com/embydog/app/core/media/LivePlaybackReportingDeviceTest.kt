package com.embydog.app.core.media

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.feature.media.MediaRepository
import com.embydog.app.core.network.NetworkModule
import com.embydog.app.core.security.TokenCipher
import com.embydog.app.core.session.SessionStore
import com.embydog.app.feature.login.DeviceIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/** Opt-in: requires a logged-in device and writes progress to an existing resume item. */
class LivePlaybackReportingDeviceTest {
    @Test fun progressAndStopArePersistedOnLiveServer(): Unit = runBlocking {
        assumeTrue("Live reporting is explicitly opt-in", InstrumentationRegistry.getArguments().getString("liveReporting") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = NetworkModule.provideEmbyApi(NetworkModule.provideOkHttpClient(), NetworkModule.provideJson())
        val reporter = PlaybackProgressReporter(api, DeviceIdentity(context))
        val repository = MediaRepository(api, SessionStore(context, TokenCipher()), reporter)
        val item = repository.getResumeItems().firstOrNull { it.canPlay && (it.runtimeMinutes ?: 0) > 5 }
        assertNotNull("A resumable video must exist", item)
        val source = repository.getPlaybackSource(item!!.id)
        val initial = source.startPositionMs.coerceIn(0, (source.durationMs - 120000).coerceAtLeast(0))
        val session = reporter.open(source, PlaybackSnapshot(positionMs = initial, durationMs = source.durationMs, isPlaying = true))
        try {
            val checkpoint = initial + 5000
            session.progress(PlaybackSnapshot(positionMs = checkpoint, durationMs = source.durationMs, isPlaying = false, isBuffering = false))
            awaitSaved(repository, source.itemId, checkpoint)
            val stopped = initial + 10000
            session.stop(PlaybackSnapshot(positionMs = stopped, durationMs = source.durationMs, isPlaying = false, isBuffering = false))
            awaitSaved(repository, source.itemId, stopped)
            assertEquals(stopped, repository.getDetail(source.itemId).item.playbackPositionMs)
            source.seriesId?.let { seriesId ->
                assertEquals(source.itemId, repository.getSeriesPlaybackTarget(seriesId)?.id)
            }
        } finally {
            session.stop(PlaybackSnapshot(positionMs = initial + 10000, durationMs = source.durationMs, isPlaying = false, isBuffering = false))
        }
    }

    private suspend fun awaitSaved(repository: MediaRepository, id: String, expected: Long) {
        var actual = -1L
        repeat(20) {
            delay(500)
            actual = repository.getItem(id).playbackPositionMs // Direct server read, not the in-memory resume cache.
            if (abs(actual - expected) <= 1000) {
                Log.i("PlaybackVerification", "Server persisted position: expectedMs=$expected actualMs=$actual")
                return
            }
        }
        assertEquals("Server must persist playback position", expected, actual)
    }
}
