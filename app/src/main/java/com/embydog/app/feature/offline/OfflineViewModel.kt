package com.embydog.app.feature.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.offline.*
import com.embydog.app.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class OfflineUiState(val records: List<OfflineRecord> = emptyList(), val profiles: List<Pair<String, String>> = emptyList(),
    val selected: String? = null, val wifiOnly: Boolean = true, val readError: String? = null)

@HiltViewModel
class OfflineViewModel @Inject constructor(private val manager: OfflineManager, sessions: SessionStore) : ViewModel() {
    private val selected = MutableStateFlow("current")
    val state = combine(manager.store.state, sessions.state.catch { emit(com.embydog.app.core.session.ProfileState()) }, selected) { manifest, profiles, filter ->
        val selectedId = when (filter) { "current" -> profiles.activeId; "all" -> null; else -> filter }
        OfflineUiState(manifest.records.filter { selectedId == null || it.profileId == selectedId },
            manifest.records.distinctBy { it.profileId }.map { it.profileId to it.profileName }, selectedId,
            manifest.wifiOnly, manager.store.readError)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineUiState(readError = manager.store.readError))
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun select(id: String?) { selected.value = id ?: "all" }
    fun resume(id: String) = perform { manager.resume(id) }
    fun pause(id: String) = perform { manager.pause(id) }
    fun delete(id: String) = perform { manager.delete(id) }
    fun wifiOnly(value: Boolean) = perform { manager.wifiOnly(value) }
    private fun perform(action: suspend () -> Unit) { viewModelScope.launch {
        try { action(); mutableError.value = null }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableError.value = error.offlineMessage() }
    } }
}
