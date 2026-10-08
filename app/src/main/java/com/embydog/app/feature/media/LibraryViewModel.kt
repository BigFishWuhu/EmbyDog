package com.embydog.app.feature.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val title: String = "媒体库",
    val isLoading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val errorMessage: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
) : ViewModel() {
    private val parentId = requireNotNull(savedStateHandle.get<String>("parentId"))
    private val _state = MutableStateFlow(
        LibraryUiState(title = savedStateHandle.get<String>("title") ?: "媒体库"),
    )
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.getLibraryItems(parentId) }
                .onSuccess { items -> _state.update { it.copy(isLoading = false, items = items) } }
                .onFailure { error ->
                    _state.update {
                        it.copy(isLoading = false, errorMessage = error.toMediaMessage())
                    }
                }
        }
    }
}
