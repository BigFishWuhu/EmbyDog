package com.embydog.app.core.media

import android.content.Context
import android.view.View
import kotlinx.coroutines.flow.StateFlow

enum class PlaybackEngineKind(val label: String) {
    MEDIA3("标准播放 · Media3"),
    MPV("兼容播放 · mpv"),
    MPV_SOFTWARE("软件解码 · mpv"),
}

enum class PlaybackFailureKind { DECODER, NETWORK, SOURCE, INITIALIZATION, UNKNOWN }

data class PlaybackFailure(val kind: PlaybackFailureKind, val message: String)

enum class PlaybackTrackKind { AUDIO, SUBTITLE }

data class PlaybackTrack(
    val id: String,
    val label: String,
    val kind: PlaybackTrackKind,
    val selected: Boolean,
    val streamIndex: Int? = null,
    val description: String = "",
)

data class PlaybackSnapshot(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val hasEnded: Boolean = false,
    val speed: Float = 1f,
    val tracks: List<PlaybackTrack> = emptyList(),
    val decoder: String? = null,
    val failure: PlaybackFailure? = null,
    val subtitleMessage: String? = null,
    val downloadBytesPerSecond: Long = 0,
    val automaticTracks: Set<PlaybackTrackKind> = emptySet(),
)

interface PlaybackEngine {
    val kind: PlaybackEngineKind
    val snapshot: StateFlow<PlaybackSnapshot>
    fun createVideoView(context: Context): View
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun selectTrack(kind: PlaybackTrackKind, id: String?)
    fun refresh()
    fun release()
}

const val AUTO_TRACK_ID = "auto"
