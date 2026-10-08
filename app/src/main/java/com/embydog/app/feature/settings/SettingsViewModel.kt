package com.embydog.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.settings.AppSettingsStore
import com.embydog.app.core.settings.PlaybackOrientation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(private val store: AppSettingsStore) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun setOrientation(orientation: PlaybackOrientation) {
        viewModelScope.launch {
            try {
                store.setPlaybackOrientation(orientation)
                _error.value = null
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _error.value = "设置保存失败，请重试" }
        }
    }
}
