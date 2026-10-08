package com.embydog.app.feature.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val favorites: Boolean,
    val query: String = "",
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class BrowseViewModel @Inject constructor(private val saved: SavedStateHandle, private val repository: MediaRepository) : ViewModel() {
    private val _state = MutableStateFlow(BrowseUiState(saved.get<String>("mode") == "favorites", saved["searchQuery"] ?: ""))
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var offset = 0

    fun setQuery(value: String) {
        if (value == _state.value.query) return
        saved["searchQuery"] = value
        _state.update { it.copy(query = value) }
        load(append = false, debounce = true)
    }

    fun refresh() = load(append = false)
    fun retry() = load(append = _state.value.items.isNotEmpty())
    fun loadMore() { if (_state.value.hasMore && !_state.value.isLoading && !_state.value.isLoadingMore) load(append = true) }

    private fun load(append: Boolean, debounce: Boolean = false) {
        job?.cancel()
        val currentGeneration = ++generation
        val query = _state.value.query.trim()
        if (!append) offset = 0
        if (!_state.value.favorites && query.isBlank()) {
            _state.update { it.copy(items = emptyList(), hasMore = false, isLoading = false, isLoadingMore = false, errorMessage = null) }
            return
        }
        _state.update { it.copy(isLoading = !append, isLoadingMore = append, errorMessage = null) }
        job = viewModelScope.launch {
            try {
                if (debounce) delay(350)
                val page = if (_state.value.favorites) repository.getFavorites(offset) else repository.searchItems(query, offset)
                if (generation != currentGeneration) return@launch
                offset += page.items.size
                _state.update { it.copy(items = (if (append) it.items + page.items else page.items).distinctBy { item -> item.id },
                    isLoading = false, isLoadingMore = false, hasMore = page.items.isNotEmpty() && offset < page.totalCount) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == currentGeneration) _state.update {
                it.copy(isLoading = false, isLoadingMore = false, errorMessage = error.toMediaMessage())
            } }
        }
    }
}
