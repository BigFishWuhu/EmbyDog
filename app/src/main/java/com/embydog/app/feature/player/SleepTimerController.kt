package com.embydog.app.feature.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SleepTimerAction(val label: String) { PAUSE("暂停播放"), CLOSE("停止并退出播放页") }

data class SleepTimerState(val minutes: Int, val action: SleepTimerAction, val remainingMs: Long)

internal fun sleepTimerMinutes(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..1440 }

/** One session-local timer, driven by elapsed real time rather than the device wall clock. */
internal class SleepTimerController(private val scope: CoroutineScope, private val nowMs: () -> Long,
    private val onExpired: (SleepTimerAction) -> Unit) {
    private val _state = MutableStateFlow<SleepTimerState?>(null)
    val state = _state.asStateFlow()
    private var deadlineMs: Long? = null
    private var timerJob: Job? = null

    fun start(minutes: Int, action: SleepTimerAction) {
        require(minutes in 1..1440)
        cancel()
        val duration = minutes * 60_000L
        deadlineMs = nowMs() + duration
        _state.value = SleepTimerState(minutes, action, duration)
        timerJob = scope.launch {
            while (isActive && _state.value != null) {
                refresh()
                val remaining = _state.value?.remainingMs ?: break
                delay(minOf(1000L, remaining).coerceAtLeast(1L))
            }
        }
    }

    fun refresh() {
        val deadline = deadlineMs ?: return
        val current = _state.value ?: return
        val remaining = (deadline - nowMs()).coerceAtLeast(0L)
        if (remaining > 0) _state.value = current.copy(remainingMs = remaining)
        else {
            cancel()
            onExpired(current.action)
        }
    }

    fun cancel() {
        deadlineMs = null
        _state.value = null
        timerJob?.cancel()
        timerJob = null
    }
}
