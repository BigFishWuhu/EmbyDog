package com.embydog.app.feature.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EpisodePickerState(
    val seasons: List<MediaItem> = emptyList(),
    val seasonId: String? = null,
    val episodes: List<MediaItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class EpisodePickerViewModel @Inject constructor(private val repository: MediaRepository) : ViewModel() {
    private val _state = MutableStateFlow(EpisodePickerState())
    val state = _state.asStateFlow()
    private var seriesId: String? = null
    private var job: Job? = null
    private var generation = 0
    private var offset = 0

    fun loadSeries(id: String, initialSeason: String?) {
        if (seriesId == id && _state.value.errorMessage == null && _state.value.seasons.isNotEmpty()) return
        job?.cancel()
        seriesId = id
        val version = ++generation
        _state.value = EpisodePickerState()
        job = viewModelScope.launch {
            try {
                val seasons = repository.getSeasons(id)
                if (version != generation) return@launch
                val selected = initialSeason?.takeIf { key -> seasons.any { it.id == key } }
                    ?: seasons.firstOrNull { (it.episodeNumber ?: 0) > 0 }?.id ?: seasons.firstOrNull()?.id
                _state.update { it.copy(seasons = seasons, seasonId = selected) }
                // A series without season records can still expose episodes through the series endpoint.
                val page = repository.getEpisodes(id, selected)
                if (version != generation) return@launch
                offset = page.items.size
                _state.update { it.copy(episodes = page.items, isLoading = false, hasMore = offset < page.totalCount && page.items.isNotEmpty()) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (version == generation) _state.update { it.copy(isLoading = false, errorMessage = error.toMediaMessage()) } }
        }
    }

    fun selectSeason(id: String?) = loadEpisodes(id, false)
    fun loadMore() { if (_state.value.hasMore && !_state.value.isLoadingMore && !_state.value.isLoading) loadEpisodes(_state.value.seasonId, true) }
    fun retry() { seriesId?.let { if (_state.value.seasons.isEmpty()) loadSeries(it, _state.value.seasonId) else loadEpisodes(_state.value.seasonId, _state.value.episodes.isNotEmpty()) } }

    private fun loadEpisodes(season: String?, append: Boolean) {
        val series = seriesId ?: return
        job?.cancel()
        val version = ++generation
        if (!append) offset = 0
        _state.update { it.copy(seasonId = season, episodes = if (append) it.episodes else emptyList(),
            isLoading = !append, isLoadingMore = append, errorMessage = null) }
        job = viewModelScope.launch {
            try {
                val page = repository.getEpisodes(series, season, offset)
                if (version != generation) return@launch
                offset += page.items.size
                _state.update { it.copy(episodes = (if (append) it.episodes + page.items else page.items).distinctBy { episode -> episode.id },
                    isLoading = false, isLoadingMore = false, hasMore = page.items.isNotEmpty() && offset < page.totalCount) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (version == generation) _state.update { it.copy(isLoading = false, isLoadingMore = false, errorMessage = error.toMediaMessage()) } }
        }
    }
}
