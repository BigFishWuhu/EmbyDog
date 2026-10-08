package com.embydog.app.core.media

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.MainActivity
import com.embydog.app.core.model.MediaTrackKind
import com.embydog.app.core.model.MediaTrackOption
import com.embydog.app.core.model.PlaybackSelection
import com.embydog.app.core.model.PlaybackSource
import com.embydog.app.core.model.SubtitleTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Silent, locally generated PCM audio: tests the real JNI adapter without server credentials. */
class MpvPlaybackRegressionTest {
    @Test fun slowSubtitleDoesNotBlockProgressSeekingOrPause() = withPlayer(subtitleIndex = 1) { engine, _, releaseSubtitle ->
        await(engine) { it.isPlaying && !it.isBuffering && it.durationMs >= 39_000 && it.positionMs > 0 }
        assertNotNull(engine.snapshot.value.subtitleMessage)
        engine.seekTo(20_000)
        await(engine) { it.positionMs >= 19_500 }
        engine.pause()
        await(engine) { !it.isPlaying }
        engine.play()
        await(engine) { it.isPlaying }
        assertNull(engine.snapshot.value.failure)
        releaseSubtitle()
        await(engine) { snapshot -> snapshot.tracks.any {
            it.kind == PlaybackTrackKind.SUBTITLE && it.streamIndex == 1 && it.selected
        } && snapshot.subtitleMessage == null }
        engine.selectTrack(PlaybackTrackKind.SUBTITLE, null)
        await(engine) { snapshot -> snapshot.tracks.none { it.kind == PlaybackTrackKind.SUBTITLE && it.selected } }
        engine.selectTrack(PlaybackTrackKind.SUBTITLE, "external:0")
        await(engine) { snapshot -> snapshot.tracks.any { it.id == "external:0" && it.selected } }
        engine.selectTrack(PlaybackTrackKind.SUBTITLE, AUTO_TRACK_ID)
        await(engine) { snapshot -> PlaybackTrackKind.SUBTITLE in snapshot.automaticTracks && snapshot.tracks.any { it.id == "external:0" && it.selected } }
        engine.selectTrack(PlaybackTrackKind.AUDIO, AUTO_TRACK_ID)
        await(engine) { snapshot -> PlaybackTrackKind.AUDIO in snapshot.automaticTracks }
    }

    @Test fun disabledSubtitleIsNotDownloadedAndAudioPreselectionIsApplied() = withPlayer(subtitleIndex = -1) { engine, server, _ ->
        await(engine) { it.isPlaying && !it.isBuffering }
        val audio = engine.snapshot.value.tracks.first { it.kind == PlaybackTrackKind.AUDIO }
        assertEquals(0, audio.streamIndex)
        assertTrue(audio.selected)
        assertNull(engine.snapshot.value.subtitleMessage)
        assertTrue(engine.snapshot.value.tracks.none { it.kind == PlaybackTrackKind.SUBTITLE && it.selected })
        repeat(server.requestCount) {
            assertFalse(server.takeRequest(1, TimeUnit.SECONDS)?.path.orEmpty().contains("subtitle"))
        }
    }

    private fun withPlayer(subtitleIndex: Int, test: (MpvPlaybackEngine, MockWebServer, () -> Unit) -> Unit) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val context = instrument.targetContext
        val activity = instrument.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        val server = MockWebServer()
        val subtitleGate = CountDownLatch(1)
        val wave = waveBytes()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().contains("subtitle")) {
                    subtitleGate.await(10, TimeUnit.SECONDS)
                    return MockResponse().setBody("1\n00:00:00,000 --> 00:00:39,000\nTest subtitle\n")
                }
                val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                val response = MockResponse().setHeader("Content-Type", "audio/wav")
                    .setHeader("Accept-Ranges", "bytes")
                if (start > 0) response.setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${wave.lastIndex}/${wave.size}")
                return response.setBody(Buffer().write(wave, start, wave.size - start))
            }
        }
        server.start()
        var engine: MpvPlaybackEngine? = null
        try {
            val source = PlaybackSource("fixture", "Local regression fixture", server.url("/media.wav").toString(),
                "", 0, listOf(SubtitleTrack(server.url("/subtitle.srt").toString(), "application/x-subrip",
                    "en", "Slow external subtitle", true, false, 1)), server.url("/").toString(),
                durationMs = 40_000,
                tracks = listOf(MediaTrackOption(0, MediaTrackKind.AUDIO, "PCM audio", null, false),
                    MediaTrackOption(1, MediaTrackKind.SUBTITLE, "External subtitle", "en", true, isDefault = true)),
                selection = PlaybackSelection(audioIndex = 0, subtitleIndex = subtitleIndex))
            instrument.runOnMainSync {
                engine = MpvPlaybackEngine(context, source, PlaybackEngineKind.MPV_SOFTWARE, OkHttpClient(), true)
                activity.setContentView(requireNotNull(engine).createVideoView(activity))
            }
            test(requireNotNull(engine), server) { subtitleGate.countDown() }
        } finally {
            instrument.runOnMainSync { engine?.release(); activity.finish() }
            subtitleGate.countDown()
            server.shutdown()
        }
    }

    private fun await(engine: MpvPlaybackEngine, predicate: (PlaybackSnapshot) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            engine.refresh()
            if (predicate(engine.snapshot.value)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for playback state: ${engine.snapshot.value}")
    }

    private fun waveBytes(): ByteArray {
        val dataSize = 8000 * 2 * 40
        return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataSize)
        }.array()
    }
}
