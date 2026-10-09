package com.embydog.app.feature.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.model.MediaItem
import com.embydog.app.core.model.MediaTrackOption
import com.embydog.app.core.model.PlaybackSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

data class DetailUiState(
    val isLoading: Boolean = true,
    val item: MediaItem? = null,
    val errorMessage: String? = null,
    val tracks: List<MediaTrackOption> = emptyList(),
    val selection: PlaybackSelection = PlaybackSelection(),
    val isFavoriteUpdating: Boolean = false,
    val actionMessage: String? = null,
    val playbackTarget: MediaItem? = null,
    val isTargetLoading: Boolean = false,
    val targetError: String? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
) : ViewModel() {
    private val itemId = requireNotNull(savedStateHandle.get<String>("itemId"))
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun retry() = load()
    fun refresh() = load(refresh = true)

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        if (_state.value.isFavoriteUpdating) return
        _state.update { it.copy(isFavoriteUpdating = true, actionMessage = null) }
        viewModelScope.launch {
            try {
                repository.setFavorite(item.id, !item.isFavorite)
                _state.update { it.copy(item = it.item?.copy(isFavorite = !item.isFavorite), isFavoriteUpdating = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(isFavoriteUpdating = false, actionMessage = "收藏操作失败：${error.toMediaMessage()}") } }
        }
    }

    fun selectAudio(index: Int?) {
        savedStateHandle["selectedAudio"] = index
        _state.update { it.copy(selection = it.selection.copy(audioIndex = index)) }
    }

    fun selectSubtitle(index: Int?) {
        savedStateHandle["selectedSubtitle"] = index
        _state.update { it.copy(selection = it.selection.copy(subtitleIndex = index)) }
    }

    private fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = !refresh && it.item == null, errorMessage = null) }
            try {
                val detail = repository.getDetail(itemId)
                _state.value = DetailUiState(
                    isLoading = false, item = detail.item, tracks = detail.tracks,
                    selection = PlaybackSelection(savedStateHandle["selectedAudio"],
                        savedStateHandle["selectedSubtitle"], detail.mediaSourceId),
                    playbackTarget = detail.item.takeIf { it.canPlay }, isTargetLoading = detail.item.type == "Series",
                )
                if (detail.item.type == "Series") {
                    try {
                        val target = repository.getSeriesPlaybackTarget(detail.item.id)
                        _state.update { it.copy(playbackTarget = target, isTargetLoading = false,
                            targetError = if (target == null) "暂无可播放单集" else null) }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { _state.update { it.copy(isTargetLoading = false, targetError = "播放记录加载失败，请重试") } }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(isLoading = false,
                errorMessage = if (it.item == null) error.toMediaMessage() else null,
                actionMessage = if (it.item != null) "播放记录刷新失败" else it.actionMessage) } }
        }
    }
}

internal fun detailPlayLabel(item: MediaItem?): String = if ((item?.playbackPositionMs ?: 0) > 0) {
    "从上次 ${com.embydog.app.feature.player.formatPlaybackTime(item!!.playbackPositionMs)} 继续播放"
} else "播放"
