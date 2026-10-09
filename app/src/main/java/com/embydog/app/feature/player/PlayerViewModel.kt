package com.embydog.app.feature.player

import android.graphics.Bitmap
import android.os.SystemClock

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.media.PlaybackEngine
import com.embydog.app.core.media.AUTO_TRACK_ID
import com.embydog.app.core.media.PlaybackPreviewLoader
import com.embydog.app.core.media.PlaybackEngineFactory
import com.embydog.app.core.media.PlaybackEngineKind
import com.embydog.app.core.media.PlaybackFailure
import com.embydog.app.core.media.PlaybackFailureKind
import com.embydog.app.core.media.PlaybackFallback
import com.embydog.app.core.media.PlaybackFallbackPolicy
import com.embydog.app.core.media.PlaybackSnapshot
import com.embydog.app.core.media.PlaybackTrackKind
import com.embydog.app.core.media.PlaybackProgressReporter
import com.embydog.app.core.media.PlaybackReportDispatcher
import com.embydog.app.core.model.PlaybackSource
import com.embydog.app.core.model.PlaybackSelection
import com.embydog.app.feature.media.MediaRepository
import com.embydog.app.feature.media.toMediaMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import okhttp3.OkHttpClient

data class SeekPreviewUiState(
    val positionMs: Long,
    val originMs: Long,
    val bitmap: Bitmap? = null,
    val frameTimeMs: Long? = null,
    val isLoading: Boolean = true,
    val message: String? = null,
)

data class PlayerUiState(
    val isLoading: Boolean = true,
    val source: PlaybackSource? = null,
    val engine: PlaybackEngine? = null,
    val engineKind: PlaybackEngineKind = PlaybackEngineKind.MPV,
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val notice: String? = null,
    val errorMessage: String? = null,
    val seekPreview: SeekPreviewUiState? = null,
    val sleepTimer: SleepTimerState? = null,
    val closeRequested: Boolean = false,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
    private val engineFactory: PlaybackEngineFactory,
    private val httpClient: OkHttpClient,
    private val progressReporter: PlaybackProgressReporter,
) : ViewModel() {
    private val itemId = requireNotNull(savedStateHandle.get<String>("itemId"))
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private val fallbackPolicy = PlaybackFallbackPolicy()
    private var engineJob: Job? = null
    private var loadJob: Job? = null
    private var activeEngine: PlaybackEngine? = null
    private var lastPositionMs: Long? = null
    private var wantedPlayback = true
    private var foreground = true
    private var playbackSpeed = 1f
    private var preferredEngine = PlaybackEngineKind.MPV
    private var previewLoader: PlaybackPreviewLoader? = null
    private var previewJob: Job? = null
    private var reportSession: PlaybackReportDispatcher.Session? = null
    private var lastReportAt = 0L
    private val sleepTimer = SleepTimerController(viewModelScope, SystemClock::elapsedRealtime) { action ->
        wantedPlayback = false
        activeEngine?.pause()
        cancelSeekPreview()
        if (action == SleepTimerAction.CLOSE) {
            loadJob?.cancel()
            releaseEngine(reportStop = true)
        }
        if (action == SleepTimerAction.PAUSE) reportProgress()
        _state.update { it.copy(engine = activeEngine, closeRequested = action == SleepTimerAction.CLOSE,
            notice = if (action == SleepTimerAction.PAUSE) "定时已到，播放已暂停" else it.notice) }
    }
    private var selection = PlaybackSelection(
        audioIndex = savedStateHandle.get<Int>("audio")?.takeIf { it >= 0 },
        subtitleIndex = savedStateHandle.get<Int>("subtitle")?.takeIf { it >= -1 },
        mediaSourceId = savedStateHandle.get<String>("sourceId")?.takeIf { it.isNotBlank() },
    )

    init {
        viewModelScope.launch { sleepTimer.state.collect { timer -> _state.update { it.copy(sleepTimer = timer) } } }
        load(forceTranscode = false)
        viewModelScope.launch {
            while (isActive) {
                activeEngine?.refresh()
                if (activeEngine != null && SystemClock.elapsedRealtime() - lastReportAt >= 10_000) reportProgress()
                delay(500)
            }
        }
    }

    fun setSleepTimer(minutes: Int, action: SleepTimerAction) = sleepTimer.start(minutes, action)
    fun cancelSleepTimer() = sleepTimer.cancel()

    fun retry() {
        fallbackPolicy.reset()
        load(forceTranscode = false)
    }

    fun switchEngine(kind: PlaybackEngineKind) {
        if (kind == _state.value.engineKind && activeEngine != null) return
        preferredEngine = kind
        fallbackPolicy.reset()
        if (_state.value.isLoading) return
        val source = _state.value.source
        if (source != null) {
            rememberPosition()
            startEngine(source.copy(startPositionMs = lastPositionMs ?: source.startPositionMs), kind)
        } else retry()
    }

    fun togglePlayback() {
        wantedPlayback = !_state.value.playback.isPlaying
        if (_state.value.playback.hasEnded) {
            seekTo(0)
            _state.value.source?.let { source ->
                reportSession = progressReporter.open(source, reportSnapshot(0).copy(hasEnded = false, isPlaying = true))
            }
        }
        if (wantedPlayback && foreground) activeEngine?.play() else activeEngine?.pause()
        reportProgress()
    }

    fun stopPlayback() {
        wantedPlayback = false
        sleepTimer.cancel()
        loadJob?.cancel()
        releaseEngine(reportStop = true)
        _state.update { it.copy(engine = null) }
    }

    fun seekTo(positionMs: Long) {
        cancelSeekPreview()
        lastPositionMs = clampSeekPosition(positionMs, _state.value.playback.durationMs)
        activeEngine?.seekTo(lastPositionMs!!)
        reportProgress(positionMs = lastPositionMs)
    }

    fun previewSeek(positionMs: Long, originMs: Long? = null) {
        val target = clampSeekPosition(positionMs, _state.value.playback.durationMs)
        val origin = originMs ?: _state.value.seekPreview?.originMs ?: _state.value.playback.positionMs
        _state.update { it.copy(seekPreview = SeekPreviewUiState(target, origin,
            bitmap = it.seekPreview?.bitmap, frameTimeMs = it.seekPreview?.frameTimeMs)) }
        if (previewJob?.isActive == true) return
        previewJob = viewModelScope.launch {
            while (isActive) {
                val position = _state.value.seekPreview?.positionMs ?: break
                val loader = previewLoader ?: break
                val preview = suspendCancellableCoroutine { continuation ->
                    loader.request(position) { if (continuation.isActive) continuation.resume(it) }
                    continuation.invokeOnCancellation { loader.cancel() }
                }
                _state.update { current ->
                    current.seekPreview?.let { seeking -> current.copy(seekPreview = seeking.copy(bitmap = preview.bitmap,
                        frameTimeMs = preview.frameTimeMs, isLoading = false, message = preview.message)) } ?: current
                }
                delay(200)
                if (_state.value.seekPreview?.positionMs == position) break
            }
        }
    }

    fun commitSeekPreview() { _state.value.seekPreview?.positionMs?.let(::seekTo) }

    fun cancelSeekPreview() {
        previewJob?.cancel()
        previewJob = null
        previewLoader?.cancel()
        _state.update { it.copy(seekPreview = null) }
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.25f, 3f)
        activeEngine?.setSpeed(playbackSpeed)
    }

    fun selectTrack(kind: PlaybackTrackKind, id: String?) {
        val streamIndex = _state.value.playback.tracks.firstOrNull { it.kind == kind && it.id == id }?.streamIndex
        selection = if (kind == PlaybackTrackKind.SUBTITLE) {
            selection.copy(subtitleIndex = if (id == AUTO_TRACK_ID) null else if (id == null) -1 else streamIndex)
        } else selection.copy(audioIndex = streamIndex)
        activeEngine?.selectTrack(kind, id)
    }

    fun setForeground(value: Boolean) {
        sleepTimer.refresh()
        foreground = value
        if (value && wantedPlayback) activeEngine?.play() else activeEngine?.pause()
        reportProgress()
    }

    private fun rememberPosition() {
        val playback = activeEngine?.snapshot?.value ?: return
        if (playback.positionMs > 0 || !playback.isBuffering) lastPositionMs = playback.positionMs
    }

    private fun reportSnapshot(positionMs: Long? = null): PlaybackSnapshot {
        val current = activeEngine?.snapshot?.value ?: _state.value.playback
        val source = _state.value.source
        val position = positionMs ?: current.positionMs.takeIf { it > 0 || !current.isBuffering }
            ?: lastPositionMs ?: source?.startPositionMs ?: 0L
        return current.copy(positionMs = position.coerceAtLeast(0), durationMs = current.durationMs.takeIf { it > 0 } ?: source?.durationMs ?: 0,
            isPlaying = current.isPlaying && foreground && wantedPlayback)
    }

    private fun reportProgress(positionMs: Long? = null) {
        reportSession?.progress(reportSnapshot(positionMs))
        lastReportAt = SystemClock.elapsedRealtime()
    }

    private fun stopReporting() {
        reportSession?.stop(reportSnapshot())
        reportSession = null
    }

    private fun releaseEngine(reportStop: Boolean = false) {
        if (reportStop) stopReporting()
        cancelSeekPreview()
        previewLoader?.close()
        previewLoader = null
        rememberPosition()
        engineJob?.cancel()
        engineJob = null
        activeEngine?.release()
        activeEngine = null
    }

    private fun startEngine(source: PlaybackSource, kind: PlaybackEngineKind, notice: String? = null) {
        if (reportSession?.source?.playSessionId != source.playSessionId || reportSession?.source?.itemId != source.itemId) stopReporting()
        releaseEngine()
        previewLoader = PlaybackPreviewLoader(source, httpClient)
        fallbackPolicy.record(kind)
        _state.value = PlayerUiState(
            isLoading = false, source = source, engineKind = kind,
            playback = PlaybackSnapshot(positionMs = source.startPositionMs, durationMs = source.durationMs), notice = notice,
            sleepTimer = sleepTimer.state.value,
        )
        val engine = try {
            engineFactory.create(source.copy(selection = selection), kind, wantedPlayback && foreground)
        } catch (_: Exception) {
            handleFailure(PlaybackFailure(PlaybackFailureKind.INITIALIZATION, "播放器初始化失败"))
            return
        }
        activeEngine = engine
        if (reportSession == null) reportSession = progressReporter.open(source,
            reportSnapshot(source.startPositionMs).copy(isPlaying = wantedPlayback && foreground))
        engine.setSpeed(playbackSpeed)
        _state.update { it.copy(engine = engine) }
        engineJob = viewModelScope.launch {
            engine.snapshot.collect { snapshot ->
                if (activeEngine !== engine) return@collect
                _state.update { it.copy(playback = snapshot.copy(
                    durationMs = snapshot.durationMs.takeIf { duration -> duration > 0 } ?: source.durationMs,
                )) }
                if (snapshot.hasEnded) stopReporting()
                snapshot.failure?.let(::handleFailure)
            }
        }
    }

    private fun handleFailure(failure: PlaybackFailure) {
        val source = _state.value.source ?: return
        rememberPosition()
        when (val next = fallbackPolicy.next(failure.kind, source.isTranscoded)) {
            is PlaybackFallback.SwitchEngine -> startEngine(
                source.copy(startPositionMs = lastPositionMs ?: source.startPositionMs),
                next.kind,
                "${failure.message}，已切换${next.kind.label}",
            )
            PlaybackFallback.Transcode -> load(forceTranscode = true)
            PlaybackFallback.Stop -> {
                releaseEngine(reportStop = true)
                _state.update { it.copy(engine = null, errorMessage = failure.message) }
            }
        }
    }

    private fun load(forceTranscode: Boolean) {
        loadJob?.cancel()
        releaseEngine(reportStop = true)
        _state.update { it.copy(isLoading = true, engine = null, errorMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val source = repository.getPlaybackSource(itemId, forceTranscode, selection)
                selection = source.selection
                startEngine(
                    source.copy(startPositionMs = lastPositionMs ?: source.startPositionMs),
                    preferredEngine,
                    if (forceTranscode) "本地解码失败，正在使用服务器转码" else null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = error.toMediaMessage()) }
            }
        }
    }

    override fun onCleared() {
        sleepTimer.cancel()
        releaseEngine(reportStop = true)
        super.onCleared()
    }
}
