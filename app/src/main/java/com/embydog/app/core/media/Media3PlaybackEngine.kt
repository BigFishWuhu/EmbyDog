package com.embydog.app.core.media

import android.content.Context
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.embydog.app.core.model.PlaybackSource
import com.embydog.app.core.model.SubtitleMetadata
import com.embydog.app.core.model.describeSubtitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(UnstableApi::class)
class Media3PlaybackEngine(private val player: ExoPlayer, private val source: PlaybackSource,
    private val meter: PlaybackBandwidthMeter = PlaybackBandwidthMeter()) : PlaybackEngine {
    override val kind = PlaybackEngineKind.MEDIA3
    private val state = MutableStateFlow(PlaybackSnapshot())
    override val snapshot = state.asStateFlow()
    private var released = false
    private val initialSelectionApplied = mutableSetOf<PlaybackTrackKind>()
    private val automaticModes = PlaybackTrackKind.entries.filterTo(mutableSetOf()) {
        (if (it == PlaybackTrackKind.AUDIO) source.selection.audioIndex else source.selection.subtitleIndex) == null
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onPlayerError(error: PlaybackException) {
            val kind = when (error.errorCode) {
                in 4000..4999 -> PlaybackFailureKind.DECODER
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackFailureKind.NETWORK
                in 2000..3999 -> PlaybackFailureKind.SOURCE
                else -> PlaybackFailureKind.UNKNOWN
            }
            state.value = state.value.copy(
                isBuffering = false,
                failure = PlaybackFailure(kind, "标准播放失败（${error.errorCodeName}）"),
            )
        }
    }

    init {
        player.addListener(listener)
        val parameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, source.selection.subtitleIndex == -1)
        source.tracks.firstOrNull { it.index == source.selection.audioIndex }?.language?.let {
            parameters.setPreferredAudioLanguage(it)
        }
        source.tracks.firstOrNull { it.index == source.selection.subtitleIndex }?.language?.let {
            parameters.setPreferredTextLanguage(it)
        }
        player.trackSelectionParameters = parameters.build()
        player.prepare()
        player.playWhenReady = true
    }

    override fun createVideoView(context: Context): View = PlayerView(context).apply {
        player = this@Media3PlaybackEngine.player
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    override fun play() { if (!released) player.play() }
    override fun pause() { if (!released) player.pause() }
    override fun seekTo(positionMs: Long) { if (!released) player.seekTo(positionMs.coerceAtLeast(0)) }
    override fun setSpeed(speed: Float) { if (!released) player.setPlaybackSpeed(speed) }

    override fun selectTrack(kind: PlaybackTrackKind, id: String?) {
        if (released) return
        if (id == AUTO_TRACK_ID) {
            automaticModes.add(kind)
            initialSelectionApplied.remove(kind)
            val type = if (kind == PlaybackTrackKind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
            val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, false)
            if (kind == PlaybackTrackKind.AUDIO) builder.setPreferredAudioLanguage(null) else builder.setPreferredTextLanguage(null)
            player.trackSelectionParameters = builder.build()
            refresh()
            return
        }
        automaticModes.remove(kind)
        initialSelectionApplied.add(kind)
        val type = if (kind == PlaybackTrackKind.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, id == null)
        if (id != null) {
            val indices = id.split(':').mapNotNull(String::toIntOrNull)
            if (indices.size != 2) return
            val group = player.currentTracks.groups.getOrNull(indices[0]) ?: return
            if (group.type != type || indices[1] !in 0 until group.length) return
            builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, indices[1]))
        }
        player.trackSelectionParameters = builder.build()
    }

    override fun refresh() {
        if (released) return
        val ordinals = mutableMapOf<PlaybackTrackKind, Int>()
        val tracks = player.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            val kind = when (group.type) {
                C.TRACK_TYPE_AUDIO -> PlaybackTrackKind.AUDIO
                C.TRACK_TYPE_TEXT -> PlaybackTrackKind.SUBTITLE
                else -> return@flatMapIndexed emptyList()
            }
            (0 until group.length).mapNotNull { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                val externalIndex = format.id?.takeIf { it.startsWith("emby-subtitle:") }
                    ?.substringAfter(':')?.toIntOrNull()
                val ordinal = ordinals[kind] ?: 0
                if (externalIndex == null) ordinals[kind] = ordinal + 1
                val option = if (externalIndex != null) source.tracks.firstOrNull { it.index == externalIndex }
                    else mediaTrackForNative(source.tracks, kind, ordinal)
                if (!group.isTrackSupported(trackIndex)) return@mapNotNull null
                val subtitle = if (kind == PlaybackTrackKind.SUBTITLE) describeSubtitle(
                    option?.subtitleMetadata?.copy(title = option.subtitleMetadata.title ?: format.label)
                        ?: SubtitleMetadata(language = format.language, title = format.label,
                            codec = format.sampleMimeType?.substringAfterLast('/').orEmpty())
                ) else null
                PlaybackTrack(
                    id = "$groupIndex:$trackIndex",
                    label = subtitle?.label ?: option?.label ?: format.label ?: format.language ?: "${kind.name} ${trackIndex + 1}",
                    kind = kind,
                    selected = group.isTrackSelected(trackIndex),
                    streamIndex = option?.index ?: externalIndex,
                    description = subtitle?.details ?: option?.description.orEmpty(),
                )
            }
        }
        PlaybackTrackKind.entries.forEach { kind ->
            if (kind !in initialSelectionApplied) {
                val automatic = kind in automaticModes
                val index = if (automatic) automaticStreamIndex(source.tracks, kind)
                    else if (kind == PlaybackTrackKind.AUDIO) source.selection.audioIndex else source.selection.subtitleIndex
                when {
                    index == null -> {
                        initialSelectionApplied.add(kind)
                        if (automatic && kind == PlaybackTrackKind.SUBTITLE && source.tracks.isNotEmpty()) {
                            selectTrack(kind, null)
                            automaticModes.add(kind)
                        }
                    }
                    index == -1 -> { selectTrack(kind, null); if (automatic) automaticModes.add(kind) }
                    else -> tracks.firstOrNull { it.kind == kind && it.streamIndex == index }?.let {
                        selectTrack(kind, it.id)
                        if (automatic) automaticModes.add(kind)
                    }
                }
            }
        }
        state.value = state.value.copy(
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            hasEnded = player.playbackState == Player.STATE_ENDED,
            speed = player.playbackParameters.speed,
            tracks = tracks,
            automaticTracks = automaticModes.toSet(),
            downloadBytesPerSecond = meter.bytesPerSecond(),
        )
    }

    override fun release() {
        if (released) return
        released = true
        player.removeListener(listener)
        player.release()
    }
}
