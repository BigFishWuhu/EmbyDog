package com.embydog.app.core.media

import com.embydog.app.core.model.PlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlaybackReportDispatcherTest {
    private fun source(id: String = "episode") = PlaybackSource(id, id, "https://server/stream", "token", 0,
        emptyList(), "https://server/emby/", userId = "user", playSessionId = "play-$id")

    @Test fun reportsAreOrderedAndStoppedPositionWinsOverQueuedProgress() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val reports = mutableListOf<Pair<PlaybackReportEvent, Long>>()
        try {
            val dispatcher = PlaybackReportDispatcher(scope) { _, event, snapshot ->
                if (event == PlaybackReportEvent.START) gate.await()
                reports.add(event to snapshot.positionMs)
            }
            val session = dispatcher.open(source(), PlaybackSnapshot(positionMs = 5000))
            repeat(100) { session.progress(PlaybackSnapshot(positionMs = it * 1000L)) }
            session.stop(PlaybackSnapshot(positionMs = 123000))
            session.stop(PlaybackSnapshot(positionMs = 999000))
            session.progress(PlaybackSnapshot(positionMs = 1))
            gate.complete(Unit)
            assertEquals(listOf(PlaybackReportEvent.START to 5000L, PlaybackReportEvent.STOP to 123000L), reports)
        } finally { scope.cancel() }
    }

    @Test fun repeatedProgressCoalescesAndCacheIsIsolatedByAccountAndServer() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val positions = mutableListOf<Long>()
        try {
            val dispatcher = PlaybackReportDispatcher(scope) { _, event, snapshot ->
                if (event == PlaybackReportEvent.START) gate.await() else positions.add(snapshot.positionMs)
            }
            val session = dispatcher.open(source(), PlaybackSnapshot())
            repeat(100) { session.progress(PlaybackSnapshot(positionMs = it * 1000L)) }
            assertEquals(99000L, dispatcher.recentPosition("https://server/emby", "user", "episode"))
            assertNull(dispatcher.recentPosition("https://server/emby", "other", "episode"))
            assertNull(dispatcher.recentPosition("https://other/emby", "user", "episode"))
            gate.complete(Unit)
            assertEquals(listOf(99000L), positions)
        } finally { scope.cancel() }
    }

    @Test fun ticksClampNegativeAndOverflowAndIncludeDefaultFlags() {
        assertEquals(123_450_000L, playbackReport(source(), PlaybackSnapshot(positionMs = 12345)).positionTicks)
        assertEquals(0L, playbackReport(source(), PlaybackSnapshot(positionMs = -1)).positionTicks)
        assertTrue(playbackReport(source(), PlaybackSnapshot(positionMs = Long.MAX_VALUE)).positionTicks > 0)
        val encoded = Json.encodeToString(playbackReport(source(), PlaybackSnapshot(isPlaying = true, durationMs = 60000)))
        assertTrue(encoded.contains("\"IsPaused\":false"))
        assertTrue(encoded.contains("\"CanSeek\":true"))
        assertTrue(encoded.contains("\"PlayMethod\":\"DirectPlay\""))
        assertTrue(encoded.contains("\"PlaySessionId\":\"play-episode\""))
        assertEquals("DirectStream", playbackReport(source().copy(isDirectStream = true), PlaybackSnapshot()).playMethod)
        assertEquals("Transcode", playbackReport(source().copy(isTranscoded = true), PlaybackSnapshot()).playMethod)
    }

    @Test fun seriesResumeUsesRecentEpisodeButNotFinishedEpisodeOrOtherUser() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val dispatcher = PlaybackReportDispatcher(scope) { _, _, _ -> }
            val session = dispatcher.open(source().copy(seriesId = "series"), PlaybackSnapshot(positionMs = 125000))
            assertEquals("episode", dispatcher.recentEpisode("https://server/emby", "user", "series"))
            assertNull(dispatcher.recentEpisode("https://server/emby", "other", "series"))
            assertNull(dispatcher.recentEpisode("https://other/emby", "user", "series"))
            session.stop(PlaybackSnapshot(positionMs = 600000, hasEnded = true))
            assertNull(dispatcher.recentEpisode("https://server/emby", "user", "series"))
            assertEquals(0L, dispatcher.recentPosition("https://server/emby", "user", "episode"))
        } finally { scope.cancel() }
    }
}
