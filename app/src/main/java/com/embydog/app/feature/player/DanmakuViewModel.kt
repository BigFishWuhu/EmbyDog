package com.embydog.app.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.danmaku.*
import com.embydog.app.core.model.PlaybackSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

data class DanmakuUiState(
    val display: DanmakuDisplay = DanmakuDisplay(),
    val appId: String = "",
    val configured: Boolean = false,
    val selected: DanmakuEpisode? = null,
    val comments: List<DanmakuComment> = emptyList(),
    val query: String = "",
    val episodeQuery: String = "",
    val results: List<DanmakuEpisode> = emptyList(),
    val hasMore: Boolean = false,
    val searched: Boolean = false,
    val searching: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DanmakuViewModel @Inject constructor(private val repository: DandanRepository, private val store: DanmakuStore) : ViewModel() {
    private val _state = MutableStateFlow(DanmakuUiState())
    val state = _state.asStateFlow()
    private var credentials = DanmakuCredentials()
    private var scope: String? = null
    private var offline = false
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var attachJob: Job? = null

    init {
        viewModelScope.launch {
            combine(store.credentials, store.display) { auth, display -> auth to display }.collect { (auth, display) ->
                val credentialsChanged = credentials != auth
                val wasEnabled = _state.value.display.enabled
                credentials = auth
                _state.update { it.copy(display = display, appId = auth.appId, configured = auth.configured,
                    comments = if (credentialsChanged) emptyList() else it.comments,
                    results = if (credentialsChanged) emptyList() else it.results) }
                if (credentialsChanged) { searchJob?.cancel(); loadJob?.cancel(); _state.update { it.copy(searching = false, loading = false) } }
                if (!display.enabled || !auth.configured) { loadJob?.cancel(); _state.update { it.copy(loading = false) } }
                else if (credentialsChanged || !wasEnabled) loadComments()
            }
        }
    }

    fun attach(source: PlaybackSource) {
        val key = danmakuScope(source.serverUrl, source.userId, source.itemId)
        if (scope == key && offline == (source.offlineId != null)) return
        offline = source.offlineId != null
        scope = key
        attachJob?.cancel(); searchJob?.cancel(); loadJob?.cancel()
        _state.update { it.copy(selected = null, comments = emptyList(), results = emptyList(), loading = false,
            searching = false, searched = false, error = null, message = null,
            query = (source.seriesTitle ?: source.title).take(120), episodeQuery = source.episodeNumber?.toString().orEmpty()) }
        attachJob = viewModelScope.launch {
            val selected = store.binding(key)
            if (scope == key) { _state.update { it.copy(selected = selected) }; loadComments() }
        }
    }

    fun search(query: String, episode: String) {
        if (offline) { _state.update { it.copy(error = "离线模式仅显示已缓存的弹幕，不进行联网搜索") }; return }
        searchJob?.cancel()
        _state.update { it.copy(query = query, episodeQuery = episode, searching = true, searched = false, error = null) }
        val auth = credentials
        searchJob = viewModelScope.launch {
            try {
                val result = repository.search(query, episode, auth)
                _state.update { it.copy(results = result.episodes, hasMore = result.hasMore, searched = true, searching = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(searching = false, error = error.danmakuMessage()) } }
        }
    }

    fun select(episode: DanmakuEpisode?) {
        val key = scope ?: return
        attachJob?.cancel(); loadJob?.cancel()
        _state.update { it.copy(selected = episode, comments = emptyList(), message = null, error = null, loading = false) }
        loadComments()
        viewModelScope.launch { try { store.bind(key, episode) } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(error = "弹幕关联保存失败，请重试") } } }
    }

    fun reload() = loadComments(refresh = true)

    private fun loadComments(refresh: Boolean = false) {
        loadJob?.cancel()
        val selected = _state.value.selected ?: return
        if (!credentials.configured || !_state.value.display.enabled) return
        if (!refresh && _state.value.comments.isNotEmpty()) return
        _state.update { it.copy(loading = true, error = null) }
        val auth = credentials
        loadJob = viewModelScope.launch {
            try {
                val result = if (offline) repository.cachedComments(selected.id, auth) else repository.comments(selected.id, auth, refresh)
                _state.update { it.copy(comments = result.comments, loading = false,
                    message = when { result.comments.isEmpty() -> "该弹幕库暂无弹幕"; result.fromCache -> "已加载 ${result.comments.size} 条弹幕（缓存）"; else -> "已加载 ${result.comments.size} 条弹幕" }) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(loading = false, error = error.danmakuMessage()) } }
        }
    }

    fun updateDisplay(display: DanmakuDisplay) {
        val normalized = display.normalized()
        _state.update { it.copy(display = normalized) }
        if (!normalized.enabled) { loadJob?.cancel(); _state.update { it.copy(loading = false) } }
        viewModelScope.launch {
            try { store.saveDisplay(normalized); if (normalized.enabled) loadComments() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(error = "弹幕设置保存失败，请重试") } }
        }
    }

    fun saveCredentials(appId: String, secret: String) {
        if (_state.value.saving) return
        val preserved = if (secret.isBlank() && appId.trim() == credentials.appId) credentials.secret else secret
        _state.update { it.copy(saving = true, error = null, message = null) }
        viewModelScope.launch {
            try { store.saveCredentials(appId, preserved); _state.update { it.copy(saving = false, message = "接入配置已保存") } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(saving = false, error = error.danmakuMessage()) } }
        }
    }

    private fun Exception.danmakuMessage(): String = if (this is SerializationException) "弹幕数据格式不受支持"
        else message?.takeIf { it.length <= 160 } ?: "弹幕加载失败，请稍后重试"
}
