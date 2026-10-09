package com.embydog.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.model.Session
import com.embydog.app.core.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.embydog.app.core.session.ProfileState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException

sealed interface RootState {
    data object Loading : RootState
    data object SignedOut : RootState
    data class SignedIn(val session: Session) : RootState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionStore: SessionStore,
) : ViewModel() {
    val profiles = sessionStore.state.catch { emit(ProfileState()) }.stateIn(viewModelScope, SharingStarted.Eagerly, ProfileState())
    private val _profileError = MutableStateFlow<String?>(null)
    val profileError = _profileError.asStateFlow()
    private val _profileBusy = MutableStateFlow(false)
    val profileBusy = _profileBusy.asStateFlow()
    val state: StateFlow<RootState> = sessionStore.session
        .map<Session?, RootState> { session ->
            if (session == null) RootState.SignedOut else RootState.SignedIn(session)
        }
        .catch { emit(RootState.SignedOut) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RootState.Loading,
        )

    fun logout() {
        changeProfile { sessionStore.clear() }
    }
    fun selectProfile(id: String) = changeProfile { sessionStore.select(id) }
    fun removeProfile(id: String) = changeProfile { sessionStore.remove(id) }
    private fun changeProfile(action: suspend () -> Unit) {
        if (_profileBusy.value) return
        _profileBusy.value = true
        viewModelScope.launch {
            try { action(); _profileError.value = null }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { _profileError.value = "Profile 操作失败，无法读取的账号请重新登录" }
            finally { _profileBusy.value = false }
        }
    }
}
