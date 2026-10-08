package com.embydog.app.core.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.embydog.app.core.model.PlaybackSource
import com.embydog.app.core.model.SubtitleMetadata
import com.embydog.app.core.model.describeSubtitle
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

class MpvPlaybackEngine(
    context: Context,
    private val source: PlaybackSource,
    override val kind: PlaybackEngineKind,
    client: OkHttpClient,
    private var playWhenReady: Boolean,
) : PlaybackEngine {
    private val state = MutableStateFlow(PlaybackSnapshot(positionMs = source.startPositionMs, durationMs = source.durationMs))
    override val snapshot = state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-playback").apply { isDaemon = true }
    }
    private val meter = PlaybackBandwidthMeter()
    private val gateway = MpvHttpGateway(source, client, meter)
    private val subtitleLoader = MpvSubtitleLoader(client.forPlayback(source, meter), File(context.cacheDir, "mpv-subtitles"))
    private val automaticModes = PlaybackTrackKind.entries.filterTo(mutableSetOf()) {
        (if (it == PlaybackTrackKind.AUDIO) source.selection.audioIndex else source.selection.subtitleIndex) == null
    }
    private val externalFiles = mutableMapOf<Int, File>()
    private var requestedSubtitle: Int? = null
    private var subtitleMessage: String? = null
    private var mpv: MPVLib? = null
    @Volatile private var released = false
    private var opened = false
    private var surfaceAttached = false
    private var loaded = false
    private var failed = false
    private val refreshQueued = AtomicBoolean(false)
    private var pendingPositionMs = source.startPositionMs
    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun eventProperty(property: String, value: Boolean) = Unit
        override fun eventProperty(property: String, value: String) = Unit

        override fun event(eventId: Int) {
            dispatch {
                val player = mpv ?: return@dispatch
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                        fileLoaded(player)
                    }
                    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                        val ended = player.getPropertyBoolean("eof-reached") == true ||
                            state.value.hasEnded
                        if (ended) {
                            publish(state.value.copy(hasEnded = true, isPlaying = false, isBuffering = false))
                        } else {
                            fail(gateway.lastFailure ?: PlaybackFailureKind.UNKNOWN, "兼容播放器无法完成播放")
                        }
                    }
                    MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> updateSnapshot(player)
                }
            }
        }
    }

    init {
        dispatch {
            val player = requireNotNull(MPVLib.create(context.applicationContext))
            mpv = player
            val options = mpvOptions(kind, playWhenReady)
            options.forEach { (name, value) ->
                check(player.setOptionString(name, value) >= 0) { "mpv 选项不受支持：$name" }
            }
            player.addObserver(observer)
            player.init()
        }
    }

    override fun createVideoView(context: Context): View = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                dispatch {
                    val player = mpv ?: return@dispatch
                    if (!holder.surface.isValid) return@dispatch
                    if (surfaceAttached) player.detachSurface()
                    player.attachSurface(holder.surface)
                    surfaceAttached = true
                    player.setPropertyString("vo", "gpu")
                    if (!opened) {
                        opened = true
                        player.command(arrayOf("loadfile", gateway.register(source.uri)))
                    }
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                dispatch { mpv?.setPropertyString("android-surface-size", "${width}x$height") }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                dispatch {
                    if (surfaceAttached) {
                        mpv?.setPropertyString("vo", "null")
                        mpv?.detachSurface()
                        surfaceAttached = false
                    }
                }
            }
        })
    }

    override fun play() = dispatch {
        playWhenReady = true
        mpv?.setPropertyBoolean("pause", false)
    }

    override fun pause() = dispatch {
        playWhenReady = false
        mpv?.setPropertyBoolean("pause", true)
    }

    override fun seekTo(positionMs: Long) = dispatch {
        pendingPositionMs = positionMs.coerceAtLeast(0)
        if (loaded) mpv?.command(arrayOf("seek", seconds(pendingPositionMs), "absolute+exact"))
    }

    override fun setSpeed(speed: Float) = dispatch { mpv?.setPropertyDouble("speed", speed.toDouble()) }

    override fun selectTrack(kind: PlaybackTrackKind, id: String?) = dispatch {
        if (id == AUTO_TRACK_ID) {
            automaticModes.add(kind)
            if (kind == PlaybackTrackKind.SUBTITLE) { subtitleLoader.cancel(); requestedSubtitle = null; subtitleMessage = null }
            mpv?.let { player -> applyStreamIndex(player, kind, automaticStreamIndex(source.tracks, kind)); updateSnapshot(player) }
            return@dispatch
        }
        automaticModes.remove(kind)
        if (kind == PlaybackTrackKind.SUBTITLE) {
            subtitleLoader.cancel()
            requestedSubtitle = null
            subtitleMessage = null
            val index = id?.removePrefix("external:")?.toIntOrNull()
            if (id?.startsWith("external:") == true && index != null) {
                loadSubtitle(index)
                return@dispatch
            }
        }
        mpv?.setPropertyString(if (kind == PlaybackTrackKind.SUBTITLE) "sid" else "aid", id ?: "no")
        mpv?.let(::updateSnapshot)
    }

    override fun refresh() {
        if (released || !refreshQueued.compareAndSet(false, true)) return
        dispatch {
            try {
                if (opened) mpv?.let { player ->
                    // Polling also recovers if a native file-loaded event was missed.
                    if (!loaded && player.getPropertyDouble("time-pos") != null) fileLoaded(player)
                    if (loaded) updateSnapshot(player)
                }
            } finally {
                refreshQueued.set(false)
            }
        }
    }

    private fun fileLoaded(player: MPVLib) {
        if (loaded || failed) return
        loaded = true
        if (pendingPositionMs > 0) player.command(arrayOf("seek", seconds(pendingPositionMs), "absolute+exact"))
        updateSnapshot(player)
        applyStreamIndex(player, PlaybackTrackKind.AUDIO, source.selection.audioIndex
            ?: automaticStreamIndex(source.tracks, PlaybackTrackKind.AUDIO))
        applyStreamIndex(player, PlaybackTrackKind.SUBTITLE, source.selection.subtitleIndex
            ?: automaticStreamIndex(source.tracks, PlaybackTrackKind.SUBTITLE))
        player.setPropertyBoolean("pause", !playWhenReady)
        updateSnapshot(player)
    }

    private fun applyStreamIndex(player: MPVLib, kind: PlaybackTrackKind, index: Int?) {
        val property = if (kind == PlaybackTrackKind.AUDIO) "aid" else "sid"
        if (index == -1) { player.setPropertyString(property, "no"); return }
        if (index == null) {
            player.setPropertyString(property, if (kind == PlaybackTrackKind.SUBTITLE && source.tracks.isNotEmpty()) "no" else "auto")
            return
        }
        val track = nativeTracks(player).firstOrNull { it.kind == kind && it.streamIndex == index }
        if (track != null && !track.id.startsWith("external:")) player.setPropertyString(property, track.id)
        else if (kind == PlaybackTrackKind.SUBTITLE) {
            val externalIndex = source.subtitles.indexOfFirst { it.streamIndex == index }
            if (externalIndex >= 0) loadSubtitle(externalIndex)
            else subtitleMessage = "所选字幕在此媒体源中不可用，请重新选择"
        }
    }

    private fun loadSubtitle(index: Int) {
        val subtitle = source.subtitles.getOrNull(index) ?: return
        requestedSubtitle = index
        subtitleMessage = "正在加载字幕：${subtitle.label}"
        mpv?.let(::updateSnapshot)
        subtitleLoader.load(index, subtitle, onReady = { file ->
            dispatch {
                if (requestedSubtitle != index) return@dispatch
                val player = mpv ?: return@dispatch
                if (externalFiles[index] == null) {
                    player.command(arrayOf("sub-add", file.absolutePath, "select", subtitle.label,
                        subtitle.language.orEmpty()))
                    val count = player.getPropertyInt("track-list/count")?.coerceIn(0, 256) ?: 0
                    val added = (0 until count).any {
                        player.getPropertyString("track-list/$it/external-filename") == file.absolutePath
                    }
                    if (!added) {
                        subtitleMessage = "字幕格式无法解析，请选择其他字幕"
                        updateSnapshot(player)
                        return@dispatch
                    }
                    externalFiles[index] = file
                } else {
                    val count = player.getPropertyInt("track-list/count") ?: 0
                    (0 until count).firstOrNull {
                        player.getPropertyString("track-list/$it/external-filename") == file.absolutePath
                    }?.let { track ->
                        player.getPropertyInt("track-list/$track/id")?.let { player.setPropertyInt("sid", it) }
                    }
                }
                subtitleMessage = null
                updateSnapshot(player)
            }
        }, onFailure = {
            dispatch {
                if (requestedSubtitle == index) {
                    requestedSubtitle = null
                    subtitleMessage = "字幕加载失败，请重新选择重试（视频播放不受影响）"
                    mpv?.let(::updateSnapshot)
                }
            }
        })
    }

    private fun nativeTracks(player: MPVLib): List<PlaybackTrack> {
        val count = player.getPropertyInt("track-list/count")?.coerceIn(0, 256) ?: 0
        val ordinals = mutableMapOf<PlaybackTrackKind, Int>()
        return (0 until count).mapNotNull { index ->
            val path = "track-list/$index"
            val kind = when (player.getPropertyString("$path/type")) {
                "audio" -> PlaybackTrackKind.AUDIO
                "sub" -> PlaybackTrackKind.SUBTITLE
                else -> return@mapNotNull null
            }
            val id = player.getPropertyInt("$path/id") ?: return@mapNotNull null
            val language = player.getPropertyString("$path/lang")
            val title = player.getPropertyString("$path/title")
            val externalIndex = if (kind == PlaybackTrackKind.SUBTITLE) {
                val filename = player.getPropertyString("$path/external-filename")
                externalFiles.entries.firstOrNull { it.value.absolutePath == filename }?.key
            } else null
            val ordinal = ordinals[kind] ?: 0
            if (externalIndex == null) ordinals[kind] = ordinal + 1
            val option = if (externalIndex != null) source.tracks.firstOrNull {
                it.index == source.subtitles[externalIndex].streamIndex
            } else mediaTrackForNative(source.tracks, kind, ordinal, player.getPropertyInt("$path/ff-index"))
            val subtitle = if (kind == PlaybackTrackKind.SUBTITLE) describeSubtitle(
                option?.subtitleMetadata?.copy(title = option.subtitleMetadata.title ?: title,
                    language = option.subtitleMetadata.language ?: language)
                    ?: SubtitleMetadata(language = language, title = title, codec = player.getPropertyString("$path/codec").orEmpty())
            ) else null
            PlaybackTrack(
                id = externalIndex?.let { "external:$it" } ?: id.toString(),
                label = subtitle?.label ?: option?.label ?: title?.takeIf(String::isNotBlank) ?: language ?: "${kind.name} $id",
                kind = kind,
                selected = player.getPropertyBoolean("$path/selected") == true,
                streamIndex = option?.index ?: externalIndex?.let { source.subtitles[it].streamIndex },
                description = subtitle?.details ?: option?.description.orEmpty(),
            )
        }
    }

    private fun updateSnapshot(player: MPVLib) {
        if (failed) return
        val nativeTracks = nativeTracks(player)
        val tracks = nativeTracks + source.subtitles.mapIndexedNotNull { index, subtitle ->
            val id = "external:$index"
            if (nativeTracks.any { it.id == id || (it.kind == PlaybackTrackKind.SUBTITLE &&
                    subtitle.streamIndex >= 0 && it.streamIndex == subtitle.streamIndex) }) null else PlaybackTrack(
                id = id, label = subtitle.label, kind = PlaybackTrackKind.SUBTITLE, selected = false,
                streamIndex = subtitle.streamIndex.takeIf { it >= 0 },
                description = subtitle.description,
            )
        }
        val paused = player.getPropertyBoolean("pause") == true
        val buffering = player.getPropertyBoolean("paused-for-cache") == true
        val ended = player.getPropertyBoolean("eof-reached") == true
        publish(state.value.copy(
            positionMs = player.getPropertyDouble("time-pos")?.let { (it * 1000).toLong() }
                ?.coerceAtLeast(0) ?: state.value.positionMs,
            durationMs = player.getPropertyDouble("duration")?.let { (it * 1000).toLong() }
                ?.coerceAtLeast(0)?.takeIf { it > 0 } ?: source.durationMs,
            isPlaying = !paused && !buffering && !ended,
            isBuffering = buffering,
            hasEnded = ended,
            speed = player.getPropertyDouble("speed")?.toFloat() ?: 1f,
            tracks = tracks,
            subtitleMessage = subtitleMessage,
            automaticTracks = automaticModes.toSet(),
            downloadBytesPerSecond = meter.bytesPerSecond(),
            decoder = player.getPropertyString("hwdec-current")?.let {
                if (it == "no") "FFmpeg 软件解码" else it
            },
        ))
    }

    private fun seconds(positionMs: Long) = (positionMs / 1000.0).toString()

    private fun publish(value: PlaybackSnapshot) {
        main.post { if (!released) state.value = value }
    }

    private fun fail(kind: PlaybackFailureKind, message: String) {
        failed = true
        publish(state.value.copy(isBuffering = false, failure = PlaybackFailure(kind, message)))
    }

    private fun dispatch(action: () -> Unit) {
        if (released) return
        try {
            worker.execute {
                if (released) return@execute
                try {
                    action()
                } catch (_: LinkageError) {
                    fail(PlaybackFailureKind.INITIALIZATION, "当前设备无法加载 mpv 原生解码库")
                } catch (_: Exception) {
                    fail(PlaybackFailureKind.INITIALIZATION, "mpv 初始化或操作失败")
                }
            }
        } catch (_: RejectedExecutionException) {
            Unit
        }
    }

    override fun release() {
        if (released) return
        released = true
        gateway.close()
        subtitleLoader.cancel()
        worker.execute {
            try {
                mpv?.removeObserver(observer)
                mpv?.destroy()
                mpv = null
            } finally {
                subtitleLoader.close()
            }
        }
        worker.shutdown()
    }
}
