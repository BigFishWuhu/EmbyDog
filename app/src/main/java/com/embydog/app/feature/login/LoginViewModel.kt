package com.embydog.app.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val completed: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: EmbyRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun setServerUrl(value: String) = updateInput { copy(serverUrl = value) }
    fun setUsername(value: String) = updateInput { copy(username = value) }
    fun setPassword(value: String) = updateInput { copy(password = value) }

    fun login() {
        val snapshot = _state.value
        if (snapshot.username.isBlank()) {
            _state.update { it.copy(errorMessage = "请输入用户名") }
            return
        }
        if (snapshot.isLoading) return

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val session = repository.login(snapshot.serverUrl, snapshot.username, snapshot.password)
                sessionStore.save(session)
                _state.update { it.copy(isLoading = false, password = "", completed = true) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                _state.update {
                    it.copy(isLoading = false, errorMessage = error.toUserMessage())
                }
            }
        }
    }

    private fun updateInput(transform: LoginUiState.() -> LoginUiState) {
        _state.update { it.transform().copy(errorMessage = null) }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is IllegalArgumentException -> message ?: "输入信息不正确"
        is HttpException -> when (code()) {
            401, 403 -> "用户名或密码不正确"
            404 -> "该地址不是可用的 Emby 服务器"
            else -> "服务器返回错误（HTTP ${code()}）"
        }
        is IOException -> "无法连接服务器，请检查地址和网络"
        else -> "登录失败，请稍后重试"
    }
}
