package com.embydog.app.core.media

import com.embydog.app.core.model.PlaybackSource
import com.embydog.app.core.network.PlaybackReportDto
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal enum class PlaybackReportEvent(val path: String) {
    START("Sessions/Playing"), PROGRESS("Sessions/Playing/Progress"), STOP("Sessions/Playing/Stopped")
}

internal fun playbackReport(source: PlaybackSource, snapshot: PlaybackSnapshot) = PlaybackReportDto(
    itemId = source.itemId, mediaSourceId = source.selection.mediaSourceId, playSessionId = source.playSessionId,
    positionTicks = snapshot.positionMs.coerceIn(0, Long.MAX_VALUE / 10_000L) * 10_000L,
    isPaused = !snapshot.isPlaying, canSeek = snapshot.durationMs > 0,
    playMethod = when { source.isTranscoded -> "Transcode"; source.isDirectStream -> "DirectStream"; else -> "DirectPlay" },
    audioStreamIndex = source.selection.audioIndex, subtitleStreamIndex = source.selection.subtitleIndex,
)

/** Application-scoped queue survives player disposal. Progress is coalesced per session. */
internal class PlaybackReportDispatcher(scope: CoroutineScope,
    private val send: suspend (PlaybackSource, PlaybackReportEvent, PlaybackSnapshot) -> Unit) {
    private data class Task(val session: Session, val event: PlaybackReportEvent)
    private val tasks = Channel<Task>(Channel.UNLIMITED)
    private data class Recent(val source: PlaybackSource, val positionMs: Long, val ended: Boolean, val updatedAt: Long)
    private val recent = ConcurrentHashMap<String, Recent>()
    private fun key(source: PlaybackSource) = "${source.serverUrl.trimEnd('/')}|${source.userId}|${source.itemId}"

    init {
        scope.launch {
            for (task in tasks) {
                val snapshot = task.session.take(task.event) ?: continue
                send(task.session.source, task.event, snapshot)
            }
        }
    }

    fun recentPosition(serverUrl: String, userId: String, itemId: String): Long? =
        recent["${serverUrl.trimEnd('/')}|$userId|$itemId"]?.takeIf { System.currentTimeMillis() - it.updatedAt < 120_000 }?.positionMs

    fun recentEpisode(serverUrl: String, userId: String, seriesId: String): String? = recent.values
        .filter { it.source.serverUrl.trimEnd('/') == serverUrl.trimEnd('/') && it.source.userId == userId &&
            it.source.seriesId == seriesId && !it.ended && it.positionMs > 0 && System.currentTimeMillis() - it.updatedAt < 120_000 }
        .maxByOrNull { it.updatedAt }?.source?.itemId

    fun open(source: PlaybackSource, snapshot: PlaybackSnapshot): Session = Session(source, snapshot).also {
        remember(source, snapshot)
        tasks.trySend(Task(it, PlaybackReportEvent.START))
    }

    private fun remember(source: PlaybackSource, snapshot: PlaybackSnapshot) {
        if (recent.size > 64) recent.entries.removeIf { System.currentTimeMillis() - it.value.updatedAt >= 120_000 }
        recent[key(source)] = Recent(source, if (snapshot.hasEnded) 0 else snapshot.positionMs.coerceAtLeast(0), snapshot.hasEnded, System.currentTimeMillis())
    }

    inner class Session internal constructor(val source: PlaybackSource, initial: PlaybackSnapshot) {
        private val started = initial
        private var latest = initial
        private var closed = false
        private var progressQueued = false

        @Synchronized fun progress(snapshot: PlaybackSnapshot) {
            if (closed) return
            latest = snapshot
            remember(source, snapshot)
            if (!progressQueued) {
                progressQueued = true
                tasks.trySend(Task(this, PlaybackReportEvent.PROGRESS))
            }
        }

        @Synchronized fun stop(snapshot: PlaybackSnapshot) {
            if (closed) return
            latest = snapshot; closed = true
            remember(source, snapshot)
            tasks.trySend(Task(this, PlaybackReportEvent.STOP))
        }

        @Synchronized internal fun take(event: PlaybackReportEvent): PlaybackSnapshot? = when (event) {
            PlaybackReportEvent.START -> started
            PlaybackReportEvent.STOP -> latest
            PlaybackReportEvent.PROGRESS -> {
                progressQueued = false
                if (closed) null else latest
            }
        }
    }
}
