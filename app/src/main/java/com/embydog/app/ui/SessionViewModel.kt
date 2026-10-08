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

sealed interface RootState {
    data object Loading : RootState
    data object SignedOut : RootState
    data class SignedIn(val session: Session) : RootState
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionStore: SessionStore,
) : ViewModel() {
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
        viewModelScope.launch { sessionStore.clear() }
    }
}

