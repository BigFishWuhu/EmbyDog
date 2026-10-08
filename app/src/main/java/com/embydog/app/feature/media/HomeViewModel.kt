package com.embydog.app.feature.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val views: List<MediaItem> = emptyList(),
    val resumeItems: List<MediaItem> = emptyList(),
    val latestItems: List<MediaItem> = emptyList(),
    val errorMessage: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() = load(isRefresh = true)

    private fun load(isRefresh: Boolean = false) {
        if (_state.value.isRefreshing) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = !isRefresh && it.views.isEmpty(),
                    isRefreshing = isRefresh,
                    errorMessage = null,
                )
            }
            val results = coroutineScope {
                val views = async { runCatching { repository.getViews() } }
                val resume = async { runCatching { repository.getResumeItems() } }
                val latest = async { runCatching { repository.getLatestItems() } }
                Triple(views.await(), resume.await(), latest.await())
            }
            val errors = listOfNotNull(
                results.first.exceptionOrNull(),
                results.second.exceptionOrNull(),
                results.third.exceptionOrNull(),
            )
            _state.value = HomeUiState(
                isLoading = false,
                views = results.first.getOrDefault(emptyList()),
                resumeItems = results.second.getOrDefault(emptyList()),
                latestItems = results.third.getOrDefault(emptyList()),
                errorMessage = errors.firstOrNull()?.toMediaMessage(),
            )
        }
    }
}

internal fun Throwable.toMediaMessage(): String = when (this) {
    is HttpException -> when (code()) {
        401, 403 -> "登录状态已失效，请重新登录"
        else -> "服务器返回错误（HTTP ${code()}）"
    }
    is IOException -> "网络连接失败，请检查服务器和网络"
    is SerializationException -> "服务器响应格式不兼容"
    is IllegalStateException -> message ?: "媒体状态无效"
    else -> "加载失败，请稍后重试"
}
